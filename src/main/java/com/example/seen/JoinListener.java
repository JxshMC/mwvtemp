package com.example.seen;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * Handles proxy-level join and disconnect events.
 *
 * <p>
 * <strong>DB-Priority first-join detection:</strong> The authoritative source
 * for whether a player is new is the database. The in-memory name cache is
 * populated ONLY after the DB query confirms existence (or creates the row).
 * This prevents false "first join" broadcasts from race conditions where the
 * cache is populated before the DB write completes.
 *
 * <p>
 * On first join ({@code first_join} is null/0 in the database), an optional
 * network-wide welcome broadcast is sent to all online players if
 * {@code global-chat.first-join-broadcast-enabled} is {@code true} in
 * config.yml.
 */
public class JoinListener {

    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;
    private final VanishManager vanishManager;

    public JoinListener(ProxyServer server, DataManager dataManager,
            ConfigManager configManager, VanishManager vanishManager) {
        this.server = server;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.vanishManager = vanishManager;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // Use getUsername() for exact casing — always correct from the auth system
        String name = player.getUsername();

        // ── LuckPerms prefix/group/suffix (async, non-blocking) ──────────────
        MessageUtils.loadUser(uuid).thenAccept(lpUser -> {
            if (lpUser != null) {
                String prefix = lpUser.getCachedData().getMetaData().getPrefix();
                String group = lpUser.getPrimaryGroup();
                String suffix = lpUser.getCachedData().getMetaData().getSuffix();

                dataManager.setPrefix(uuid, prefix);
                dataManager.setGroupName(uuid, group);
                dataManager.setSuffix(uuid, suffix);
            }

            // ── DB-Priority first-join detection ──────────────────────────────
            // We check row EXISTENCE (UUID present in seen_data), not a timestamp value.
            // Using getFirstJoinAsync caused false-positives: rows with first_join=0
            // (corrupted or unset timestamps) returned null and re-triggered the welcome
            // broadcast even for players who already had a record.
            // hasRecordAsync is authoritative: true = returning player, false = brand new.
            dataManager.hasRecordAsync(uuid).thenAccept(exists -> {

                if (!exists) {
                    // DB confirmed: NO row for this UUID. Genuinely new player.
                    long now = System.currentTimeMillis();

                    // createProfileIfMissing now returns CompletableFuture<Integer>
                    // (resolves to the DB-assigned join_index after INSERT completes,
                    // or MAX(join_index) from the 2-second fallback).
                    // The welcome broadcast is chained INSIDE this future's callback so
                    // %minewar_joinindex% is always available when the message is built.
                    dataManager.createProfileIfMissing(uuid, name, now).thenAccept(joinIndex -> {
                        if (configManager.isFirstJoinBroadcastEnabled()) {
                            String rawMsg = configManager.getMessage("first-join-welcome")
                                    .replace("%player%", name)
                                    .replace("%join_index%", String.valueOf(Math.max(0, joinIndex)));
                            rawMsg = MessageUtils.applyPlaceholders(rawMsg, name, uuid, false);
                            Component welcomeComponent = MessageUtils.renderMessage(rawMsg);
                            server.getAllPlayers().forEach(p -> p.sendMessage(welcomeComponent));
                        }
                    });
                } else {
                    // Returning player — update in-memory name cache with exact casing from auth
                    dataManager.cacheName(name, uuid);
                }
            });
        });

        // ── Vanish state restoration ──────────────────────────────────────────
        boolean wasVanished = dataManager.isVanished(player.getUniqueId());
        boolean shouldAutoVanish = dataManager.isAutoVanish(player.getUniqueId());

        if (configManager.getDeepValue(null, "Auto-vanish.enabled", Boolean.class, false)) {
            String autoVanishPerm = configManager.getConfigString("permissions.vanish_auto",
                    "minewarvelocity.vanish.auto");
            dataManager.setAutoVanish(player.getUniqueId(), player.hasPermission(autoVanishPerm));
        }

        if (wasVanished || shouldAutoVanish) {
            vanishManager.hidePlayer(player, true);
            System.out.println("[Vanish] Applied/Restored vanish state for " + player.getUsername() + " on login");
        }

        // JOIN-SHIELD: Refresh all vanished player states for this new joiner
        vanishManager.refreshAllVanishedStates();

        // Suppression: Don't broadcast join message if vanished
        if (vanishManager.isVanished(player.getUniqueId()))
            return;

        // ── Network join message ──────────────────────────────────────────────
        // Use player.getUsername() for exact case-sensitive name
        String joinMsg = configManager.getMessage("join-message")
                .replace("%player%", player.getUsername())
                .replace("%server%", player.getCurrentServer()
                        .map(s -> s.getServerInfo().getName()).orElse("Unknown"));

        server.getAllPlayers().forEach(p -> MessageUtils.parseWithPapi(joinMsg, p, player.getUniqueId())
                .thenAccept(p::sendMessage));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        // Capture exact last-seen timestamp on disconnect
        dataManager.setLastSeen(player.getUniqueId(), System.currentTimeMillis());

        if (event.getLoginStatus() == DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            if (vanishManager.isVanished(player.getUniqueId()))
                return;

            // Use player.getUsername() for exact case-sensitive name
            String leaveMsg = configManager.getMessage("leave-message")
                    .replace("%player%", player.getUsername())
                    .replace("%server%", "Network");

            server.getAllPlayers().forEach(p -> {
                if (!p.equals(player)) {
                    MessageUtils.parseWithPapi(leaveMsg, p, player.getUniqueId())
                            .thenAccept(p::sendMessage);
                }
            });
        }
    }
}
