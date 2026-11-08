package me.fokend.livingworld.storage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.fokend.livingworld.task.MainThread;

/**
 * Owns the single database thread. All JDBC work runs here, sequentially, so {@link SqliteStorage} never
 * needs locking. The work queue is bounded: when the database cannot keep up, new work is rejected
 * (and the caller keeps its data dirty for the next attempt) instead of piling up in memory.
 */
public final class StorageService {

    @FunctionalInterface
    public interface StorageWork<T> {
        T run(SqliteStorage storage) throws Exception;
    }

    private final SqliteStorage storage;
    private final MainThread mainThread;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean closed;

    public StorageService(SqliteStorage storage, MainThread mainThread, Logger logger, int queueCapacity) {
        this.storage = storage;
        this.mainThread = mainThread;
        this.logger = logger;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(16, queueCapacity)), r -> {
                    Thread t = new Thread(r, "LivingWorld-Storage");
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler((th, ex) ->
                            logger.log(Level.SEVERE, "Uncaught error in storage thread", ex));
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Runs {@code work} on the storage thread; the result (or the error) is delivered on the main thread.
     *
     * @return false if the work was not accepted (queue full or service closed); callbacks are then not called
     */
    public <T> boolean submit(StorageWork<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        if (closed) {
            return false;
        }
        try {
            executor.execute(() -> {
                T result;
                try {
                    result = work.run(storage);
                } catch (Throwable ex) {
                    failures.incrementAndGet();
                    if (onError != null) {
                        mainThread.execute(() -> onError.accept(ex));
                    } else {
                        logger.log(Level.WARNING, "Storage operation failed", ex);
                    }
                    return;
                }
                if (onSuccess != null) {
                    mainThread.execute(() -> onSuccess.accept(result));
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            rejected.incrementAndGet();
            return false;
        }
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public long failureCount() {
        return failures.get();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Stops accepting work, waits for queued work to finish, then runs {@code finalWork} synchronously on the
     * calling thread (the storage thread has terminated, so the connection is not shared) and closes the
     * database.
     */
    public void shutdown(StorageWork<?> finalWork, long timeoutSeconds) {
        closed = true;
        executor.shutdown();
        boolean terminated;
        try {
            terminated = executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            if (!terminated) {
                logger.warning("Storage thread did not finish in " + timeoutSeconds
                        + "s, interrupting it (" + executor.getQueue().size() + " queued tasks dropped)");
                executor.shutdownNow();
                terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            terminated = false;
        }
        if (terminated && finalWork != null && storage.isOpen()) {
            try {
                finalWork.run(storage);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Final save failed", ex);
            }
        } else if (!terminated) {
            logger.severe("Storage thread is stuck; skipping the final save to avoid concurrent "
                    + "database access. Data since the last autosave may be lost.");
        }
        if (terminated) {
            storage.close();
        }
    }
}
