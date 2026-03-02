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

public class UnignoreCommand implements RawCommand {
    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;

    public UnignoreCommand(ProxyServer server, DataManager dataManager, ConfigManager configManager) {
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
                    .deserialize(configManager.getMessage("usage-unignore")));
            return;
        }

        String targetName = args[0];
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

        if (!dataManager.isIgnored(player.getUniqueId(), targetUuid)) {
            String msg = configManager.getMessage("ignore-not-ignored").replace("%player%", targetName);
            MessageUtils.parseWithPapi(msg, player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        dataManager.removeIgnore(player.getUniqueId(), targetUuid);
        dataManager.save();

        String msg = configManager.getMessage("ignore-removed").replace("%player%", targetName);
        MessageUtils.parseWithPapi(msg, player, targetUuid)
                .thenAccept(player::sendMessage);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player))
            return Collections.emptyList();

        // Permission check
        String perm = configManager.getConfigString("permissions.ignore", "minewarvelocity.ignore");
        if (!invocation.source().hasPermission(perm)) {
            return Collections.emptyList();
        }

        Player player = (Player) invocation.source();
        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            Set<UUID> ignored = dataManager.getIgnoreList(player.getUniqueId());
            List<String> suggestions = new ArrayList<>();

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
                if (name != null && name.toLowerCase().startsWith(prefix)) {
                    suggestions.add(name);
                } else if (name == null && uuid.toString().startsWith(prefix)) {
                    suggestions.add(uuid.toString());
                }
            }
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
