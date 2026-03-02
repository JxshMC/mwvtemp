package com.example.seen.config;

import com.velocitypowered.api.plugin.annotation.DataDirectory;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import dev.dejvokep.boostedyaml.libs.org.snakeyaml.engine.v2.common.FlowStyle;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Manages config.yml and messages.yml using BoostedYAML's smart-update system.
 *
 * <p>
 * <b>Smart Update contract:</b>
 * <ul>
 * <li>New keys introduced in the bundled JAR defaults are automatically merged
 * into the
 * server's file on load/reload.</li>
 * <li>Existing user-defined values are <em>never</em> overwritten —
 * {@code setKeepAll(true)}
 * guarantees this.</li>
 * <li>Deprecated keys listed in {@code DEPRECATED_CONFIG_ROUTES} /
 * {@code DEPRECATED_MESSAGES_ROUTES} are actively removed from the server
 * file.</li>
 * <li>Bumping {@code config-version} in either YAML file triggers the updater
 * on next
 * reload, applying any pending merges/removals.</li>
 * </ul>
 */
@Singleton
public class SmartConfig {

    private final Path dataDirectory;
    private YamlDocument config;
    private YamlDocument messages;

    // ── Deprecated key lists ──────────────────────────────────────────────────
    // Add dot-separated key paths here to have BoostedYAML delete them on update.
    // Example: "old-section.old-key"
    private static final List<String> DEPRECATED_CONFIG_KEYS = List.of(
    // "some.old.key"
    );
    private static final List<String> DEPRECATED_MESSAGES_KEYS = List.of(
    // "some.old.message"
    );

    @Inject
    public SmartConfig(@DataDirectory Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    // ── Shared updater factory ────────────────────────────────────────────────

    /**
     * Builds an {@link UpdaterSettings} that:
     * <ul>
     * <li>Uses {@code config-version} as the version key.</li>
     * <li>Keeps all existing user keys ({@code setKeepAll(true)}) — values are
     * never overwritten.</li>
     * <li>Actively removes any dot-separated key paths listed in
     * {@code deprecated}.</li>
     * </ul>
     */
    private static UpdaterSettings buildUpdaterSettings(List<String> deprecated) {
        UpdaterSettings.Builder builder = UpdaterSettings.builder()
                .setVersioning(new BasicVersioning("config-version"))
                .setKeepAll(true); // ← Core of the "smart merge, never overwrite" contract
        for (String key : deprecated) {
            // "*" wildcard applies the removal across all version transitions
            builder.addIgnoredRoute("*", key, '.');
        }
        return builder.build();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    public void load() {
        try {
            dataDirectory.toFile().mkdirs();

            config = YamlDocument.create(
                    new File(dataDirectory.toFile(), "config.yml"),
                    Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                    GeneralSettings.builder().setUseDefaults(true).build(),
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.builder().setFlowStyle(FlowStyle.BLOCK).build(),
                    buildUpdaterSettings(DEPRECATED_CONFIG_KEYS));

            messages = YamlDocument.create(
                    new File(dataDirectory.toFile(), "messages.yml"),
                    Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml")),
                    GeneralSettings.builder().setUseDefaults(true).build(),
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.builder().setFlowStyle(FlowStyle.BLOCK).build(),
                    buildUpdaterSettings(DEPRECATED_MESSAGES_KEYS));

            // Ensure any new files in the future are also handled here if added to
            // resources

            System.out.println(
                    "[MinewarVelocity] Loaded config.yml (version " + config.getInt("config-version", 0) + ")");
            System.out.println(
                    "[MinewarVelocity] Loaded messages.yml (version " + messages.getInt("config-version", 0) + ")");
            System.out
                    .println("[MinewarVelocity] Smart config update active — new keys merged, user values preserved.");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Reloads config.yml and messages.yml synchronously on the calling thread.
     *
     * <p>
     * BoostedYAML's {@code update()} call merges any new keys from the bundled
     * JAR defaults into the server file while preserving all existing user values
     * ({@code setKeepAll(true)} is already set in {@link #buildUpdaterSettings}).
     *
     * <p>
     * This method is used by the {@code /mwv reload} command so that
     * {@code DatabaseManager.reconnect()} always reads the fully-updated config
     * on the same thread, with no async race.
     */
    public void reload() {
        try {
            if (config != null) {
                config.reload();
                config.update();
                System.out.println("[MinewarVelocity] Reloaded config.yml (version " +
                        config.getInt("config-version", 0) + ")");
            }
            if (messages != null) {
                messages.reload();
                messages.update();
                System.out.println("[MinewarVelocity] Reloaded messages.yml (version " +
                        messages.getInt("config-version", 0) + ")");
            }
            System.out.println("[MinewarVelocity] Configuration reload complete (sync).");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ── Async Reload (kept for legacy callers) ────────────────────────────────

    public CompletableFuture<Void> reloadAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (config != null) {
                    config.reload();
                    config.update();
                    System.out.println("[MinewarVelocity] Reloaded config.yml (version " +
                            config.getInt("config-version", 0) + ")");
                }
                if (messages != null) {
                    messages.reload();
                    messages.update();
                    System.out.println("[MinewarVelocity] Reloaded messages.yml (version " +
                            messages.getInt("config-version", 0) + ")");
                }
                System.out.println("[MinewarVelocity] Configuration reload complete.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public YamlDocument getConfig() {
        if (config == null) {
            System.err.println("[SmartConfig] getConfig() called before load() — check init order!");
            return null;
        }
        return config;
    }

    public YamlDocument getMessages() {
        if (messages == null) {
            System.err.println("[SmartConfig] getMessages() called before load() — check init order!");
            return null;
        }
        return messages;
    }

    // ── Command Whitelist Helpers ─────────────────────────────────────────────

    public List<String> getCommandWhitelist() {
        if (config == null)
            return Collections.emptyList();
        List<String> list = config.getStringList("command-whitelist.allowed");
        return list != null ? list : Collections.emptyList();
    }

    public String getWhitelistBypassPermission() {
        if (config == null)
            return "minewar.admin.tab";
        return config.getString("command-whitelist.bypass-permission", "minewar.admin.tab");
    }

    public List<String> getDisabledServers() {
        if (config == null)
            return Collections.emptyList();
        List<String> list = config.getStringList("command-whitelist.disabled-servers");
        return list != null ? list : Collections.emptyList();
    }

    public boolean isCommandWhitelistEnabled() {
        if (config == null)
            return true;
        return config.getBoolean("command-whitelist.enabled", true);
    }

    public boolean isPerPermissionEnabled() {
        if (config == null)
            return false;
        return config.getBoolean("command-whitelist.per-permission", false);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    public void saveConfig() {
        try {
            if (config != null)
                config.save();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveMessages() {
        try {
            if (messages != null)
                messages.save();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}