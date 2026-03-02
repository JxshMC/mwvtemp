package com.example.seen;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.william278.velocitab.api.VelocitabAPI;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class VanishManager {

    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;
    private final Path dataDirectory;
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    public VanishManager(ProxyServer server, DataManager dataManager, ConfigManager configManager,
            @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDirectory) {
        this.server = server;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.dataDirectory = dataDirectory;
    }

    private boolean isVelocitabPresent() {
        return server.getPluginManager().getPlugin("velocitab").isPresent();
    }

    /**
     * Returns true if the Minewar-Sahorah AI bot plugin is currently active and
     * enabled.
     */
    public boolean isSahorahPresent() {
        var pluginOpt = server.getPluginManager().getPlugin("minewar-sahorah");
        if (pluginOpt.isEmpty())
            return false;
        try {
            Object instance = pluginOpt.get().getInstance().get();
            return (boolean) instance.getClass().getMethod("isEnabled").invoke(instance);
        } catch (Exception e) {
            return true; // Fallback to present if check fails
        }
    }

    private void safeVanishPlayer(Player player) {
        if (isVelocitabPresent()) {
            try {
                VelocitabAPI.getInstance().vanishPlayer(player);
            } catch (Exception e) {
                System.err.println("[VanishManager] Velocitab vanish failed: " + e.getMessage());
            }
        }
    }

    private void safeUnvanishPlayer(Player player) {
        if (isVelocitabPresent()) {
            try {
                VelocitabAPI.getInstance().unVanishPlayer(player);
            } catch (Exception e) {
                System.err.println("[VanishManager] Velocitab unvanish failed: " + e.getMessage());
            }
        }
    }

    public boolean isVanished(UUID uuid) {
        return dataManager.isVanished(uuid);
    }

    // Required by VanishCommand
    public void toggleVanish(Player player) {
        if (isVanished(player.getUniqueId())) {
            showPlayer(player);
        } else {
            hidePlayer(player, false);
        }
    }

    public void hidePlayer(Player player) {
        hidePlayer(player, false);
    }

    public void hidePlayer(Player player, boolean autoVanish) {
        boolean alreadyVanished = dataManager.isVanished(player.getUniqueId());
        if (alreadyVanished && !autoVanish)
            return;

        dataManager.setVanished(player.getUniqueId(), true);
        dataManager.setLastSeen(player.getUniqueId(), System.currentTimeMillis());

        safeVanishPlayer(player);
        notifyBackend(player, true);
        logVanishEvent(player, true, autoVanish);

        // Send fake leave message if enabled in messages.yml and they weren't already
        // vanished
        if (!alreadyVanished) {
            String fakeLeave = configManager.getMessage("vanish-fake-leave");
            if (fakeLeave != null && !fakeLeave.isEmpty() && !fakeLeave.equals("vanish-fake-leave")) {
                // Parse placeholders via MessageUtils to ensure %suffix_other% works
                // Note: MessageUtils.parsePapi might be asynchronous or blocking depending on
                // impl.
                // Velocity doesn't have a main thread in the same way, but PAPI requests go to
                // backend.
                // Assuming parsePapi handles this correctly or is "safe enough".
                // Ideally this should be run async if it does IO.
                server.getScheduler().buildTask(server.getPluginManager().getPlugin("minewarvelocity").get(), () -> {
                    String broadcast = MessageUtils.parsePapi(player, fakeLeave);
                    broadcast = broadcast.replace("%player%", player.getUsername());
                    final String finalBroadcast = broadcast;
                    server.getAllPlayers().forEach(p -> {
                        if (!p.equals(player)) {
                            p.sendMessage(
                                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                            .deserialize(finalBroadcast));
                        }
                    });
                }).schedule();
            }
        }

        String msg = configManager.getMessage("vanish-enabled");
        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(msg));
    }

    public void showPlayer(Player player) {
        if (!dataManager.isVanished(player.getUniqueId()))
            return;
        dataManager.setVanished(player.getUniqueId(), false);

        safeUnvanishPlayer(player);
        notifyBackend(player, false);
        logVanishEvent(player, false, false);

        // Send fake join message if enabled
        String fakeJoin = configManager.getMessage("vanish-fake-join");
        if (fakeJoin != null && !fakeJoin.isEmpty() && !fakeJoin.equals("vanish-fake-join")) {
            server.getScheduler().buildTask(server.getPluginManager().getPlugin("minewarvelocity").get(), () -> {
                String broadcast = MessageUtils.parsePapi(player, fakeJoin);
                broadcast = broadcast.replace("%player%", player.getUsername());
                final String finalBroadcast = broadcast;
                server.getAllPlayers().forEach(p -> {
                    if (!p.equals(player)) {
                        p.sendMessage(
                                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                        .deserialize(finalBroadcast));
                    }
                });
            }).schedule();
        }

        String msg = configManager.getMessage("vanish-disabled");
        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(msg));
    }

    public boolean canSee(Player viewer, Player target) {
        if (viewer.getUniqueId().equals(target.getUniqueId()))
            return true;
        if (!isVanished(target.getUniqueId()))
            return true;

        String vanishPerm = configManager.getConfigString("permissions.vanish_see", "minewar.vanish.see");
        String adminVanishPerm = configManager.getConfigString("permissions.vanish_admins", "minewar.vanish.admins");
        String adminSeePerm = configManager.getConfigString("permissions.vanish_admins_see",
                "minewar.vanish.admins.see");

        boolean targetIsAdminVanished = target.hasPermission(adminVanishPerm);

        if (targetIsAdminVanished) {
            // If target has admin vanish perm, viewer MUST have admin see perm
            return viewer.hasPermission(adminSeePerm);
        } else {
            // Standard vanish logic with hierarchy support
            // Viewer can see if:
            // 1. They have standard see perm AND target is NOT admin vanished (already
            // handled above)
            // 2. They have admin see perm (super user sees all)
            return viewer.hasPermission(vanishPerm) || viewer.hasPermission(adminSeePerm);
        }
    }

    // Required by ServerListPingListener and ListCommand
    public int getVisibleCount(Optional<String> serverName) {
        if (serverName.isPresent()) {
            String srv = serverName.get();
            int realCount = server.getServer(srv)
                    .map(s -> (int) s.getPlayersConnected().stream()
                            .filter(p -> !isVanished(p.getUniqueId()))
                            .count())
                    .orElse(0);
            // Add LoshFP NPCs registered on this specific backend server
            // int npcCount = fakePlayerRegistry.countForServer(srv);

            // STRICT: Sub-server counts return ONLY the physical player count of the
            // specified server.
            return realCount;
        }

        // Global proxy count
        int botOffset = isSahorahPresent() ? 1 : 0;
        int total = server.getPlayerCount();
        int vanished = (int) server.getAllPlayers().stream()
                .filter(p -> isVanished(p.getUniqueId()))
                .count();

        // GLOBAL: Return (Online - Vanished) + 1 (if bot)
        return Math.max(0, total - vanished) + botOffset;
    }

    public int getVanishedCount() {
        return (int) server.getAllPlayers().stream()
                .filter(p -> isVanished(p.getUniqueId()))
                .count();
    }

    // Required by JoinListener
    public void refreshAllVanishedStates() {
        server.getAllPlayers().forEach(player -> {
            if (dataManager.isVanished(player.getUniqueId())) {
                safeVanishPlayer(player);
            }
        });
    }

    private void notifyBackend(Player player, boolean vanished) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("vanish");
        out.writeUTF(player.getUniqueId().toString());
        out.writeBoolean(vanished);
        player.getCurrentServer()
                .ifPresent(s -> s.sendPluginMessage(MinewarVelocity.VANISH_CHANNEL, out.toByteArray()));
    }

    private void logVanishEvent(Player player, boolean vanished, boolean autoVanish) {
        try {
            java.io.File dbDir = dataDirectory.resolve("database").toFile();
            if (!dbDir.exists())
                dbDir.mkdirs();
            java.io.File logFile = new java.io.File(dbDir, "vanish.log");
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
                String timestamp = LocalDateTime.now().format(LOG_TIME_FORMAT);
                String action = vanished ? "VANISHED" : "UNVANISHED";
                String trigger = autoVanish ? "(AUTO)" : "(MANUAL)";
                writer.println(String.format("[%s] %s %s %s", timestamp, player.getUsername(), action, trigger));
            }
        } catch (IOException ignored) {
        }
    }
}