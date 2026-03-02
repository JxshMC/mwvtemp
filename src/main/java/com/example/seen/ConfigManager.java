package com.example.seen;

import com.example.seen.config.SmartConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class ConfigManager {

    private final SmartConfig smartConfig;

    @Inject
    public ConfigManager(SmartConfig smartConfig) {
        this.smartConfig = smartConfig;
    }

    public void load() {
        // SmartConfig loads itself via MinewarVelocity onProxyInitialization or lazy
        // loading.
        // We can ensure it's loaded here if needed, but MinewarVelocity calls
        // smartConfig.load() explicitly.
    }

    public dev.dejvokep.boostedyaml.YamlDocument getConfig() {
        return smartConfig.getConfig();
    }

    public boolean isDebug() {
        return smartConfig.getConfig().getBoolean("debug", false);
    }

    public String getMessage(String key) {
        return smartConfig.getMessages().getString(key, key);
    }

    public String getConfigString(String path) {
        return smartConfig.getConfig().getString(path, "");
    }

    public String getConfigString(String path, String defaultValue) {
        return smartConfig.getConfig().getString(path, defaultValue);
    }

    public String getVelocitabFormat() {
        return smartConfig.getConfig().getString("Velocitab-Format", "%rank%%username%");
    }

    public Map<String, String> getPermissions() {
        Map<String, String> perms = new HashMap<>();
        // permissions section is flat key-value
        dev.dejvokep.boostedyaml.block.implementation.Section section = smartConfig.getConfig()
                .getSection("permissions");
        if (section != null) {
            for (Object key : section.getKeys()) {
                perms.put(key.toString(), section.getString(key.toString()));
            }
        }
        return perms;
    }

    public Map<String, String> getRoles() {
        Map<String, String> roles = new HashMap<>();
        dev.dejvokep.boostedyaml.block.implementation.Section section = smartConfig.getConfig()
                .getSection("discord.roles");
        if (section != null) {
            for (Object key : section.getKeys()) {
                roles.put(key.toString(), section.getString(key.toString()));
            }
        }
        return roles;
    }

    public boolean isModuleEnabled(String moduleName) {
        return smartConfig.getConfig().getBoolean("modules." + moduleName + ".enabled", false);
    }

    // Keep strict signature for backward compatibility
    public <T> T getDeepValue(Map<String, Object> map, String path, Class<T> type, T defaultValue) {
        // This method was used when ConfigManager manually managed Maps.
        // Now we should just use SmartConfig result, ignoring the 'map' argument which
        // is likely null or ignored.
        // BUT, existing calls might pass 'config' map.
        // Since we replaced the internal 'config' map with SmartConfig, any Code
        // calling getDeepValue
        // likely got the map from ConfigManager (which we removed getters for?).
        // If they passed null, we use SmartConfig directly.

        Object val = smartConfig.getConfig().get(path);
        if (val == null)
            return defaultValue;

        if (type.isInstance(val)) {
            return type.cast(val);
        }
        // Basic conversion attempt (e.g. Integer to Long or String)
        if (type == String.class)
            return type.cast(val.toString());

        return defaultValue;
    }

    // helper for cleaner access if map is not relevant
    public <T> T getDeepValue(String path, Class<T> type, T defaultValue) {
        return smartConfig.getConfig().get(path) != null ? (T) smartConfig.getConfig().get(path) : defaultValue;
        // Cast warning ignored for brevity, assumes type correctness or catches logic
        // above
    }

    public void reload() {
        smartConfig.reloadAsync();
    }

    // ── Global Chat Helpers ──────────────────────────────────────────────────

    /** Returns the raw MiniMessage owner color tag, e.g. {@code <#0adef7>}. */
    public String getOwnerColor() {
        return smartConfig.getConfig().getString("global-chat.owner-color", "<#0adef7>");
    }

    /** Returns the raw MiniMessage mention color tag, e.g. {@code <gold>}. */
    public String getMentionColor() {
        return smartConfig.getConfig().getString("global-chat.mention-color", "<gold>");
    }

    /**
     * Whether to broadcast a welcome message to all players when a new player joins
     * for the first time.
     */
    public boolean isFirstJoinBroadcastEnabled() {
        return smartConfig.getConfig().getBoolean("global-chat.first-join-broadcast-enabled", true);
    }

    /** Whether the global-chat module is enabled. */
    public boolean isGlobalChatEnabled() {
        return smartConfig.getConfig().getBoolean("global-chat.enabled", true);
    }
}