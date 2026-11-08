package me.fokend.livingworld.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.HistoryEventType;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionSnapshot;
import me.fokend.livingworld.model.SpeciesGroup;

/**
 * SQLite persistence. <b>Not thread-safe by design:</b> every method must be called from the single
 * storage thread owned by {@link StorageService} (or from the main thread after that executor has
 * terminated, during shutdown). Uses WAL journaling and one transaction per batch so a crash can only
 * lose the last unsaved batch, never corrupt older data.
 */
public final class SqliteStorage implements AutoCloseable {

    /** Ordered schema migrations; schema version = number of applied entries. Never edit old entries. */
    private static final List<String[]> MIGRATIONS = List.<String[]>of(
            new String[]{
                    "CREATE TABLE IF NOT EXISTS lw_regions ("
                            + "world TEXT NOT NULL, rx INTEGER NOT NULL, rz INTEGER NOT NULL,"
                            + "profile TEXT NOT NULL, custom_name TEXT, state TEXT NOT NULL,"
                            + "candidate_state TEXT, candidate_since INTEGER NOT NULL DEFAULT 0,"
                            + "development REAL NOT NULL DEFAULT 0, peak_development REAL NOT NULL DEFAULT 0,"
                            + "forest REAL NOT NULL DEFAULT 1, stability REAL NOT NULL DEFAULT 1,"
                            + "exploitation REAL NOT NULL DEFAULT 0,"
                            + "pop_grazers REAL NOT NULL DEFAULT 1, pop_small_game REAL NOT NULL DEFAULT 1,"
                            + "pop_predators REAL NOT NULL DEFAULT 1, pop_aquatic REAL NOT NULL DEFAULT 1,"
                            + "presence_ema REAL NOT NULL DEFAULT 0, build_ema REAL NOT NULL DEFAULT 0,"
                            + "trade_ema REAL NOT NULL DEFAULT 0, pressure_ema REAL NOT NULL DEFAULT 0,"
                            + "last_activity INTEGER NOT NULL DEFAULT 0, last_simulated INTEGER NOT NULL DEFAULT 0,"
                            + "created_at INTEGER NOT NULL DEFAULT 0,"
                            + "total_logs_cut INTEGER NOT NULL DEFAULT 0, total_trees_grown INTEGER NOT NULL DEFAULT 0,"
                            + "total_kills INTEGER NOT NULL DEFAULT 0, total_trades INTEGER NOT NULL DEFAULT 0,"
                            + "total_build INTEGER NOT NULL DEFAULT 0, flags INTEGER NOT NULL DEFAULT 0,"
                            + "dominant_group TEXT, updated_at INTEGER NOT NULL DEFAULT 0,"
                            + "PRIMARY KEY (world, rx, rz))",
                    "CREATE INDEX IF NOT EXISTS idx_lw_regions_simulated ON lw_regions(last_simulated)",
                    "CREATE TABLE IF NOT EXISTS lw_influence (world TEXT NOT NULL, rx INTEGER NOT NULL, rz INTEGER NOT NULL,"
                            + "group_id TEXT NOT NULL, display_name TEXT NOT NULL, score REAL NOT NULL,"
                            + "PRIMARY KEY (world, rx, rz, group_id))",
                    "CREATE TABLE IF NOT EXISTS lw_stewards (world TEXT NOT NULL, rx INTEGER NOT NULL, rz INTEGER NOT NULL,"
                            + "player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, trees INTEGER NOT NULL,"
                            + "milestone INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (world, rx, rz, player_uuid))",
                    "CREATE TABLE IF NOT EXISTS lw_history (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,"
                            + "type TEXT NOT NULL, world TEXT NOT NULL, rx INTEGER NOT NULL, rz INTEGER NOT NULL,"
                            + "value REAL NOT NULL DEFAULT 0, subject TEXT NOT NULL DEFAULT '', actors TEXT NOT NULL DEFAULT '')",
                    "CREATE INDEX IF NOT EXISTS idx_lw_history_region ON lw_history(world, rx, rz, id)",
                    "CREATE INDEX IF NOT EXISTS idx_lw_history_ts ON lw_history(ts)"
            }
    );

    public static final int SCHEMA_VERSION = MIGRATIONS.size();

    private static final String[] POP_COLUMNS = new String[SpeciesGroup.count()];

    static {
        for (SpeciesGroup g : SpeciesGroup.all()) {
            POP_COLUMNS[g.ordinal()] = "pop_" + g.name().toLowerCase(Locale.ROOT);
        }
    }

    private static final String REGION_COLUMNS = "world, rx, rz, profile, custom_name, state, candidate_state,"
            + " candidate_since, development, peak_development, forest, stability, exploitation, "
            + String.join(", ", POP_COLUMNS) + ", presence_ema, build_ema, trade_ema, pressure_ema, last_activity,"
            + " last_simulated, created_at, total_logs_cut, total_trees_grown, total_kills, total_trades, total_build,"
            + " flags, dominant_group, updated_at";

    private final File file;
    private final Logger logger;
    private Connection connection;

    public SqliteStorage(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    // ================================================================= lifecycle

    public void open() throws StorageException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new StorageException("Cannot create data folder " + parent);
        }
        try {
            connect();
            if (!integrityOk()) {
                quarantine("integrity check failed");
                connect();
            }
        } catch (SQLException ex) {
            if (isCorruption(ex)) {
                quarantine(ex.getMessage());
                try {
                    connect();
                } catch (SQLException again) {
                    throw new StorageException("Cannot open fresh database after quarantine", again);
                }
            } else {
                throw new StorageException("Cannot open database " + file, ex);
            }
        }
        migrate();
    }

    private void connect() throws SQLException {
        closeQuietly();
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("synchronous", "NORMAL");
        props.setProperty("busy_timeout", "5000");
        props.setProperty("foreign_keys", "false");
        connection = org.sqlite.JDBC.createConnection("jdbc:sqlite:" + file.getAbsolutePath(), props);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
        }
        connection.setAutoCommit(true);
    }

    private boolean integrityOk() throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
        }
    }

    private static boolean isCorruption(SQLException ex) {
        String msg = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("malformed") || msg.contains("not a database") || msg.contains("sqlite_corrupt")
                || msg.contains("sqlite_notadb");
    }

    /** Moves a corrupted database aside (never deletes it) so an admin can attempt a manual recovery. */
    private void quarantine(String reason) throws StorageException {
        closeQuietly();
        String suffix = ".corrupt-" + System.currentTimeMillis();
        logger.severe("Database " + file.getName() + " is corrupted (" + reason + "). Moving it to "
                + file.getName() + suffix + " and starting with a fresh database.");
        for (String ext : new String[]{"", "-wal", "-shm"}) {
            File f = new File(file.getPath() + ext);
            if (f.exists() && !f.renameTo(new File(file.getPath() + ext + suffix))) {
                throw new StorageException("Cannot move corrupted database file " + f);
            }
        }
    }

    private void migrate() throws StorageException {
        try {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS lw_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            }
            int current = (int) metaLong("schema_version", 0);
            if (current > SCHEMA_VERSION) {
                throw new StorageException("Database schema version " + current + " is newer than supported "
                        + SCHEMA_VERSION + ". Refusing to touch it; update the plugin.");
            }
            for (int v = current; v < SCHEMA_VERSION; v++) {
                connection.setAutoCommit(false);
                try (Statement st = connection.createStatement()) {
                    for (String sql : MIGRATIONS.get(v)) {
                        st.execute(sql);
                    }
                    putMeta("schema_version", String.valueOf(v + 1));
                    connection.commit();
                    logger.info("Database migrated to schema v" + (v + 1));
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException ex) {
            throw new StorageException("Database migration failed", ex);
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ex) {
                logger.log(Level.WARNING, "Error closing database", ex);
            }
            connection = null;
        }
    }

    public boolean isOpen() {
        return connection != null;
    }

    // ================================================================= meta

    public long metaLong(String key, long def) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM lw_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        return Long.parseLong(rs.getString(1).trim());
                    } catch (NumberFormatException ex) {
                        return def;
                    }
                }
            }
        }
        return def;
    }

    public void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO lw_meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ================================================================= regions

    public RegionSnapshot loadRegion(RegionKey key) throws SQLException {
        RegionSnapshot base;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + REGION_COLUMNS + " FROM lw_regions WHERE world = ? AND rx = ? AND rz = ?")) {
            bindKey(ps, 1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                base = readRegion(rs, key);
            }
        }
        List<RegionSnapshot.Influence> influence = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT group_id, display_name, score FROM lw_influence WHERE world = ? AND rx = ? AND rz = ?"
                        + " ORDER BY score DESC LIMIT 64")) {
            bindKey(ps, 1, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    influence.add(new RegionSnapshot.Influence(rs.getString(1), rs.getString(2), rs.getDouble(3)));
                }
            }
        }
        List<RegionSnapshot.Steward> stewards = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name, trees, milestone FROM lw_stewards WHERE world = ? AND rx = ? AND rz = ?"
                        + " ORDER BY trees DESC LIMIT 64")) {
            bindKey(ps, 1, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = parseUuid(rs.getString(1));
                    if (id != null) {
                        stewards.add(new RegionSnapshot.Steward(id, rs.getString(2), rs.getInt(3), rs.getInt(4)));
                    }
                }
            }
        }
        return new RegionSnapshot(base.key(), base.profile(), base.customName(), base.state(), base.candidateState(),
                base.candidateSince(), base.development(), base.peakDevelopment(), base.forest(), base.stability(),
                base.exploitation(), base.populations(), base.presenceEma(), base.buildEma(), base.tradeEma(),
                base.pressureEma(), base.lastActivity(), base.lastSimulated(), base.createdAt(), base.totalLogsCut(),
                base.totalTreesGrown(), base.totalKills(), base.totalTrades(), base.totalBuild(), base.flags(),
                base.dominantGroup(), influence, stewards);
    }

    private static RegionSnapshot readRegion(ResultSet rs, RegionKey key) throws SQLException {
        int c = 4;
        BiomeProfile profile = BiomeProfile.parse(rs.getString(c++), BiomeProfile.OTHER);
        String customName = rs.getString(c++);
        DevelopmentState state = DevelopmentState.parse(rs.getString(c++), DevelopmentState.WILDERNESS);
        String cand = rs.getString(c++);
        DevelopmentState candidate = cand == null ? null : DevelopmentState.parse(cand, null);
        long candidateSince = rs.getLong(c++);
        double development = rs.getDouble(c++);
        double peak = rs.getDouble(c++);
        double forest = rs.getDouble(c++);
        double stability = rs.getDouble(c++);
        double exploitation = rs.getDouble(c++);
        double[] pops = new double[SpeciesGroup.count()];
        for (int i = 0; i < pops.length; i++) {
            pops[i] = rs.getDouble(c++);
        }
        return new RegionSnapshot(key, profile, customName, state, candidate, candidateSince, development, peak,
                forest, stability, exploitation, pops, rs.getDouble(c++), rs.getDouble(c++), rs.getDouble(c++),
                rs.getDouble(c++), rs.getLong(c++), rs.getLong(c++), rs.getLong(c++), rs.getLong(c++),
                rs.getLong(c++), rs.getLong(c++), rs.getLong(c++), rs.getLong(c++), rs.getInt(c++),
                rs.getString(c), List.of(), List.of());
    }

    /**
     * Persists a batch of region snapshots, new chronicle entries and the simulation clock in one
     * transaction: either everything is stored or nothing is.
     */
    public void saveBatch(List<RegionSnapshot> regions, List<HistoryEvent> events, long simClock) throws SQLException {
        long now = System.currentTimeMillis();
        connection.setAutoCommit(false);
        try {
            if (!regions.isEmpty()) {
                StringBuilder placeholders = new StringBuilder();
                int columns = REGION_COLUMNS.split(",").length;
                for (int i = 0; i < columns; i++) {
                    placeholders.append(i == 0 ? "?" : ", ?");
                }
                String upsert = "INSERT OR REPLACE INTO lw_regions (" + REGION_COLUMNS + ") VALUES (" + placeholders + ")";
                try (PreparedStatement ps = connection.prepareStatement(upsert);
                     PreparedStatement delInf = connection.prepareStatement(
                             "DELETE FROM lw_influence WHERE world = ? AND rx = ? AND rz = ?");
                     PreparedStatement insInf = connection.prepareStatement(
                             "INSERT OR REPLACE INTO lw_influence (world, rx, rz, group_id, display_name, score) VALUES (?, ?, ?, ?, ?, ?)");
                     PreparedStatement delSt = connection.prepareStatement(
                             "DELETE FROM lw_stewards WHERE world = ? AND rx = ? AND rz = ?");
                     PreparedStatement insSt = connection.prepareStatement(
                             "INSERT OR REPLACE INTO lw_stewards (world, rx, rz, player_uuid, player_name, trees, milestone) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (RegionSnapshot s : regions) {
                        int c = bindKey(ps, 1, s.key());
                        ps.setString(c++, s.profile().name());
                        ps.setString(c++, s.customName());
                        ps.setString(c++, s.state().name());
                        ps.setString(c++, s.candidateState() == null ? null : s.candidateState().name());
                        ps.setLong(c++, s.candidateSince());
                        ps.setDouble(c++, finite(s.development()));
                        ps.setDouble(c++, finite(s.peakDevelopment()));
                        ps.setDouble(c++, finite(s.forest()));
                        ps.setDouble(c++, finite(s.stability()));
                        ps.setDouble(c++, finite(s.exploitation()));
                        for (int i = 0; i < SpeciesGroup.count(); i++) {
                            ps.setDouble(c++, finite(s.populations()[i]));
                        }
                        ps.setDouble(c++, finite(s.presenceEma()));
                        ps.setDouble(c++, finite(s.buildEma()));
                        ps.setDouble(c++, finite(s.tradeEma()));
                        ps.setDouble(c++, finite(s.pressureEma()));
                        ps.setLong(c++, s.lastActivity());
                        ps.setLong(c++, s.lastSimulated());
                        ps.setLong(c++, s.createdAt());
                        ps.setLong(c++, s.totalLogsCut());
                        ps.setLong(c++, s.totalTreesGrown());
                        ps.setLong(c++, s.totalKills());
                        ps.setLong(c++, s.totalTrades());
                        ps.setLong(c++, s.totalBuild());
                        ps.setInt(c++, s.flags());
                        ps.setString(c++, s.dominantGroup());
                        ps.setLong(c, now);
                        ps.addBatch();

                        bindKey(delInf, 1, s.key());
                        delInf.addBatch();
                        for (RegionSnapshot.Influence i : s.influence()) {
                            int k = bindKey(insInf, 1, s.key());
                            insInf.setString(k++, i.groupId());
                            insInf.setString(k++, i.displayName() == null ? i.groupId() : i.displayName());
                            insInf.setDouble(k, finite(i.score()));
                            insInf.addBatch();
                        }
                        bindKey(delSt, 1, s.key());
                        delSt.addBatch();
                        for (RegionSnapshot.Steward st : s.stewards()) {
                            int k = bindKey(insSt, 1, s.key());
                            insSt.setString(k++, st.playerId().toString());
                            insSt.setString(k++, st.playerName() == null ? "?" : st.playerName());
                            insSt.setInt(k++, st.treesGrown());
                            insSt.setInt(k, st.lastMilestone());
                            insSt.addBatch();
                        }
                    }
                    ps.executeBatch();
                    delInf.executeBatch();
                    insInf.executeBatch();
                    delSt.executeBatch();
                    insSt.executeBatch();
                }
            }
            if (!events.isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO lw_history (ts, type, world, rx, rz, value, subject, actors) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (HistoryEvent e : events) {
                        ps.setLong(1, e.timestamp());
                        ps.setString(2, e.type().name());
                        bindKey(ps, 3, e.region());
                        ps.setDouble(6, finite(e.value()));
                        ps.setString(7, e.subject());
                        ps.setString(8, e.actors());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            putMeta("sim_clock", String.valueOf(simClock));
            connection.commit();
        } catch (SQLException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                ex.addSuppressed(rollback);
            }
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Latest simulation timestamp of any region (0 if none); guards against a lost/corrupted clock. */
    public long maxLastSimulated() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(last_simulated), 0) FROM lw_regions")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public List<RegionKey> staleRegions(long simBefore, int limit) throws SQLException {
        List<RegionKey> keys = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world, rx, rz FROM lw_regions WHERE last_simulated < ? ORDER BY last_simulated ASC LIMIT ?")) {
            ps.setLong(1, simBefore);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(new RegionKey(rs.getString(1), rs.getInt(2), rs.getInt(3)));
                }
            }
        }
        return keys;
    }

    public void deleteRegion(RegionKey key) throws SQLException {
        connection.setAutoCommit(false);
        try {
            for (String table : new String[]{"lw_regions", "lw_influence", "lw_stewards"}) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE world = ? AND rx = ? AND rz = ?")) {
                    bindKey(ps, 1, key);
                    ps.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // ================================================================= queries

    public List<RegionSummary> top(TopMetric metric, String world, int limit) throws SQLException {
        String where = world == null ? "" : " WHERE world = ?";
        if (metric == TopMetric.DAMAGED) {
            // "damaged" only makes sense for regions that were actually touched
            where = (world == null ? " WHERE" : where + " AND") + " (total_logs_cut > 0 OR total_kills > 0 OR stability < 0.9)";
        }
        List<RegionSummary> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world, rx, rz, custom_name, profile, state, development, stability, forest, exploitation,"
                        + " trade_ema, total_trees_grown FROM lw_regions" + where + " ORDER BY " + metric.orderBy()
                        + " LIMIT ?")) {
            int c = 1;
            if (world != null) {
                ps.setString(c++, world);
            }
            ps.setInt(c, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RegionSummary(new RegionKey(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                            rs.getString(4), BiomeProfile.parse(rs.getString(5), BiomeProfile.OTHER),
                            DevelopmentState.parse(rs.getString(6), DevelopmentState.WILDERNESS), rs.getDouble(7),
                            rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getDouble(11), rs.getLong(12)));
                }
            }
        }
        return out;
    }

    public WorldStats stats() throws SQLException {
        Map<DevelopmentState, Integer> byState = new EnumMap<>(DevelopmentState.class);
        int total = 0;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT state, COUNT(*) FROM lw_regions GROUP BY state")) {
            while (rs.next()) {
                DevelopmentState s = DevelopmentState.parse(rs.getString(1), DevelopmentState.WILDERNESS);
                byState.merge(s, rs.getInt(2), Integer::sum);
                total += rs.getInt(2);
            }
        }
        double avgStability = 1;
        double avgForest = 1;
        long trees = 0;
        long logs = 0;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT AVG(stability), AVG(forest), SUM(total_trees_grown),"
                     + " SUM(total_logs_cut) FROM lw_regions")) {
            if (rs.next() && total > 0) {
                avgStability = rs.getDouble(1);
                avgForest = rs.getDouble(2);
                trees = rs.getLong(3);
                logs = rs.getLong(4);
            }
        }
        long history = 0;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lw_history")) {
            if (rs.next()) {
                history = rs.getLong(1);
            }
        }
        return new WorldStats(total, byState, avgStability, avgForest, trees, logs, history);
    }

    /** Chronicle page, newest first. {@code region == null} returns the whole world's chronicle. */
    public List<HistoryRow> history(RegionKey region, int offset, int limit) throws SQLException {
        String sql = "SELECT h.id, h.ts, h.type, h.world, h.rx, h.rz, h.value, h.subject, h.actors,"
                + " r.custom_name, r.profile FROM lw_history h"
                + " LEFT JOIN lw_regions r ON r.world = h.world AND r.rx = h.rx AND r.rz = h.rz"
                + (region == null ? "" : " WHERE h.world = ? AND h.rx = ? AND h.rz = ?")
                + " ORDER BY h.id DESC LIMIT ? OFFSET ?";
        List<HistoryRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int c = 1;
            if (region != null) {
                c = bindKey(ps, c, region);
            }
            ps.setInt(c++, limit);
            ps.setInt(c, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HistoryEventType type = HistoryEventType.parse(rs.getString(3));
                    if (type == null) {
                        continue; // written by a newer plugin version
                    }
                    HistoryEvent e = new HistoryEvent(rs.getLong(1), rs.getLong(2), type,
                            new RegionKey(rs.getString(4), rs.getInt(5), rs.getInt(6)), rs.getDouble(7),
                            rs.getString(8), rs.getString(9));
                    String profile = rs.getString(11);
                    out.add(new HistoryRow(e, rs.getString(10),
                            profile == null ? null : BiomeProfile.parse(profile, BiomeProfile.OTHER)));
                }
            }
        }
        return out;
    }

    /** Applies the retention policy. Returns the number of removed entries. */
    public int pruneHistory(long olderThanWall, int maxEvents) throws SQLException {
        int removed;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM lw_history WHERE ts < ?")) {
            ps.setLong(1, olderThanWall);
            removed = ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM lw_history WHERE id <= (SELECT id FROM lw_history ORDER BY id DESC LIMIT 1 OFFSET ?)")) {
            ps.setInt(1, Math.max(0, maxEvents));
            removed += ps.executeUpdate();
        }
        return removed;
    }

    public int clearHistory() throws SQLException {
        try (Statement st = connection.createStatement()) {
            return st.executeUpdate("DELETE FROM lw_history");
        }
    }

    // ================================================================= helpers

    private static int bindKey(PreparedStatement ps, int index, RegionKey key) throws SQLException {
        ps.setString(index, key.world());
        ps.setInt(index + 1, key.x());
        ps.setInt(index + 2, key.z());
        return index + 3;
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0;
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
