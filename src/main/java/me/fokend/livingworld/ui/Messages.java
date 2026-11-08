package me.fokend.livingworld.ui;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads {@code messages.yml} (user file over bundled defaults) and renders MiniMessage templates.
 * Dynamic values must be passed with {@code Placeholder.unparsed(...)} (plain text) or
 * {@code Placeholder.component(...)} so player-controlled text can never inject markup.
 */
public final class Messages {

    private final MiniMessage mini = MiniMessage.miniMessage();
    private YamlConfiguration yaml = new YamlConfiguration();
    private Component prefix = Component.empty();

    public void load(File file, InputStream bundled, Logger logger) {
        YamlConfiguration defaults = new YamlConfiguration();
        if (bundled != null) {
            try (InputStreamReader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
                defaults = YamlConfiguration.loadConfiguration(reader);
            } catch (Exception ex) {
                logger.warning("Cannot read bundled messages.yml: " + ex.getMessage());
            }
        }
        YamlConfiguration user = file != null && file.exists() ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        user.setDefaults(defaults);
        this.yaml = user;
        this.prefix = parse(raw("prefix"));
    }

    public String raw(String key) {
        String s = yaml.getString(key);
        return s == null ? key : s;
    }

    public List<String> rawList(String key) {
        List<String> list = yaml.getStringList(key);
        return list == null ? List.of() : list;
    }

    public Component get(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    public Component prefixed(String key, TagResolver... resolvers) {
        return prefix.append(get(key, resolvers));
    }

    public List<Component> list(String key, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>();
        for (String line : rawList(key)) {
            out.add(parse(line, resolvers));
        }
        return out;
    }

    public Component parse(String template, TagResolver... resolvers) {
        try {
            return mini.deserialize(template, resolvers);
        } catch (RuntimeException ex) {
            return Component.text(template);
        }
    }
}
