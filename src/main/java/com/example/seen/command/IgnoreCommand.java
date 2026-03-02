package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class IgnoreCommand implements RawCommand {
    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;

    public IgnoreCommand(ProxyServer server, DataManager dataManager, ConfigManager configManager) {
        this.server = server;
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("players-only")));
            return;
        }

        Player player = (Player) invocation.source();
        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        String perm = configManager.getConfigString("permissions.ignore", "minewarvelocity.ignore");
        if (!player.hasPermission(perm)) {
            MessageUtils.parseWithPapi(configManager.getMessage("no-permission"), player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        if (args.length == 0) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("usage-ignore")));
            return;
        }

        if (args[0].equalsIgnoreCase("list")) {
            handleList(player);
            return;
        }

        handleIgnore(player, args[0]);
    }

    private void handleList(Player player) {
        // In the new DataManager, we don't have getIgnoreList(UUID) which returns a
        // Set.
        // We'll need to implement logic to show the list if required, or refactor.
        // For now, I'll update DataManager to provide getIgnoreList or similar.
        // Actually, let's just use the database directly or update DataManager.
        // I will update DataManager to include getIgnoreList(UUID).
        Set<UUID> ignored = dataManager.getIgnoreList(player.getUniqueId());
        if (ignored.isEmpty()) {
            MessageUtils.parseWithPapi(configManager.getMessage("ignore-list-empty"), player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        List<String> names = new ArrayList<>();
        for (UUID uuid : ignored) {
            String name = null;
            Optional<Player> online = server.getPlayer(uuid);
            if (online.isPresent()) {
                name = online.get().getUsername();
            } else {
                for (Map.Entry<String, UUID> entry : dataManager.getNameCache().entrySet()) {
                    if (entry.getValue().equals(uuid)) {
                        name = entry.getKey();
                        break;
                    }
                }
            }
            names.add(name != null ? name : uuid.toString());
        }

        String list = String.join(", ", names);
        String msg = configManager.getMessage("ignore-list-format").replace("%players%", list);
        MessageUtils.parseWithPapi(msg, player, null)
                .thenAccept(player::sendMessage);
    }

    private void handleIgnore(Player player, String targetName) {
        if (player.getUsername().equalsIgnoreCase(targetName)) {
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("cannot-ignore-self")));
            return;
        }

        UUID targetUuid = dataManager.getUUID(targetName);
        if (targetUuid == null) {
            Optional<Player> online = server.getPlayer(targetName);
            if (online.isPresent()) {
                targetUuid = online.get().getUniqueId();
                dataManager.cacheName(online.get().getUsername(), targetUuid);
            }
        }

        if (targetUuid == null) {
            String msg = configManager.getMessage("player-not-found-seen").replace("%player%", targetName);
            MessageUtils.parseWithPapi(msg, player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        if (dataManager.isIgnored(player.getUniqueId(), targetUuid)) {
            String msg = configManager.getMessage("ignore-already-ignored").replace("%player%", targetName);
            MessageUtils.parseWithPapi(msg, player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        dataManager.addIgnore(player.getUniqueId(), targetUuid);
        // dataManager.save() removed - handled by DB

        String msg = configManager.getMessage("ignore-added").replace("%player%", targetName);
        MessageUtils.parseWithPapi(msg, player, targetUuid)
                .thenAccept(player::sendMessage);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Permission check first
        if (!(invocation.source() instanceof Player)) {
            return Collections.emptyList();
        }

        String perm = configManager.getConfigString("permissions.ignore", "minewarvelocity.ignore");
        if (!invocation.source().hasPermission(perm)) {
            return Collections.emptyList();
        }

        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            if ("list".startsWith(prefix))
                suggestions.add("list");

            server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .forEach(suggestions::add);
            return suggestions;
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String perm = configManager.getConfigString("permissions.ignore", "minewarvelocity.ignore");
        return invocation.source().hasPermission(perm);
    }
}
