package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class SeenCommand implements RawCommand {
    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;

    public SeenCommand(ProxyServer server, DataManager dataManager, ConfigManager configManager) {
        this.server = server;
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");
        Player sourcePlayer = invocation.source() instanceof Player ? (Player) invocation.source() : null;

        String perm = configManager.getConfigString("permissions.seen", "minewarvelocity.seen");
        if (!invocation.source().hasPermission(perm)) {
            MessageUtils.parseWithPapi(configManager.getMessage("no-permission"), sourcePlayer, null)
                    .thenAccept(invocation.source()::sendMessage);
            return;
        }

        if (args.length == 0) {
            MessageUtils.parseWithPapi(configManager.getMessage("usage-seen"), sourcePlayer, null)
                    .thenAccept(invocation.source()::sendMessage);
            return;
        }

        String targetName = args[0];
        Optional<Player> onlinePlayer = server.getPlayer(targetName);

        String seeVanishPerm = configManager.getConfigString("permissions.vanish_see", "minewarvelocity.vanish.see");
        boolean canSeeVanish = invocation.source().hasPermission(seeVanishPerm);

        if (onlinePlayer.isPresent()) {
            Player target = onlinePlayer.get();
            boolean isVanished = dataManager.isVanished(target.getUniqueId());

            if (!isVanished || canSeeVanish) {
                String serverName = target.getCurrentServer()
                        .map(srv -> srv.getServerInfo().getName())
                        .orElse("Unknown");

                String msg = configManager.getMessage("online-now")
                        .replace("%player%", target.getUsername())
                        .replace("%server%", serverName);
                if (isVanished)
                    msg = configManager.getMessage("vanish-prefix") + msg;

                MessageUtils.parseWithPapi(msg, sourcePlayer, target.getUniqueId())
                        .thenAccept(invocation.source()::sendMessage);
                return;
            }
        }

        UUID uuid = dataManager.getUUID(targetName);
        if (uuid == null) {
            String msg = configManager.getMessage("player-not-found-seen")
                    .replace("%player%", targetName);
            MessageUtils.parseWithPapi(msg, sourcePlayer, null)
                    .thenAccept(invocation.source()::sendMessage);
            return;
        }

        Long lastSeen = dataManager.getLastSeen(uuid);
        if (lastSeen == null || lastSeen == 0) {
            String msg = configManager.getMessage("never-seen").replace("%player%", targetName);
            MessageUtils.parseWithPapi(msg, sourcePlayer, null)
                    .thenAccept(invocation.source()::sendMessage);
            return;
        }

        String timeStr = formatTime(System.currentTimeMillis() - lastSeen);
        String msg = configManager.getMessage("seen-message")
                .replace("%player%", targetName)
                .replace("%time%", timeStr);
        MessageUtils.parseWithPapi(msg, sourcePlayer, uuid)
                .thenAccept(invocation.source()::sendMessage);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Permission check first
        if (!(invocation.source() instanceof Player)) {
            return Collections.emptyList();
        }

        String perm = configManager.getConfigString("permissions.seen", "minewarvelocity.seen");
        if (!invocation.source().hasPermission(perm)) {
            return Collections.emptyList();
        }

        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            String seeVanishPerm = configManager.getConfigString("permissions.vanish_see",
                    "minewarvelocity.vanish.see");
            boolean canSeeVanish = invocation.source().hasPermission(seeVanishPerm);

            return server.getAllPlayers().stream()
                    .filter(p -> !dataManager.isVanished(p.getUniqueId()) || canSeeVanish)
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String perm = configManager.getConfigString("permissions.seen", "minewarvelocity.seen");
        return invocation.source().hasPermission(perm);
    }

    private String formatTime(long millis) {
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        millis -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        millis -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        millis -= TimeUnit.MINUTES.toMillis(minutes);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);

        StringBuilder sb = new StringBuilder();
        if (days > 0)
            sb.append(days).append(configManager.getMessage("format-days")).append(" ");
        if (hours > 0)
            sb.append(hours).append(configManager.getMessage("format-hours")).append(" ");
        if (minutes > 0)
            sb.append(minutes).append(configManager.getMessage("format-minutes")).append(" ");
        if (seconds >= 0)
            sb.append(seconds).append(configManager.getMessage("format-seconds"));

        return sb.toString().trim();
    }
}
