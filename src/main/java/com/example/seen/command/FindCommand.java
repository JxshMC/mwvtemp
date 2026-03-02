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
import java.util.stream.Collectors;

public class FindCommand implements RawCommand {
    private final ProxyServer server;
    private final ConfigManager configManager;
    private final DataManager dataManager;

    public FindCommand(ProxyServer server, ConfigManager configManager, DataManager dataManager) {
        this.server = server;
        this.configManager = configManager;
        this.dataManager = dataManager;
    }

    @Override
    public void execute(Invocation invocation) {
        String rawArgs = invocation.arguments();

        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");
        Player sourcePlayer = invocation.source() instanceof Player ? (Player) invocation.source() : null;

        String perm = configManager.getConfigString("permissions.find", "minewarvelocity.find");
        if (!invocation.source().hasPermission(perm)) {
            MessageUtils.parseWithPapi(configManager.getMessage("no-permission"), sourcePlayer, null)
                    .thenAccept(invocation.source()::sendMessage);
            return;
        }

        if (args.length == 0) {
            MessageUtils.parseWithPapi(configManager.getMessage("usage-find"), sourcePlayer, null)
                    .thenAccept(invocation.source()::sendMessage);
            return;
        }

        String targetName = args[0];
        Optional<Player> target = server.getPlayer(targetName);

        String seeVanishPerm = configManager.getConfigString("permissions.vanish_see", "minewarvelocity.vanish.see");
        boolean canSeeVanish = invocation.source().hasPermission(seeVanishPerm);

        if (target.isPresent()) {
            Player player = target.get();
            boolean isVanished = dataManager.isVanished(player.getUniqueId());

            if (!isVanished || canSeeVanish) {
                String serverName = player.getCurrentServer()
                        .map(srv -> srv.getServerInfo().getName())
                        .orElse("Unknown");

                String msg = configManager.getMessage("find-message")
                        .replace("%player%", player.getUsername())
                        .replace("%server%", serverName);
                if (isVanished)
                    msg = configManager.getMessage("vanish-prefix") + msg;

                MessageUtils.parseWithPapi(msg, sourcePlayer, player.getUniqueId())
                        .thenAccept(invocation.source()::sendMessage);
                return;
            }
        }

        // Show as not found if offline or vanished (and no perm)
        String msg = configManager.getMessage("find-not-found")
                .replace("%player%", targetName);
        MessageUtils.parseWithPapi(msg, sourcePlayer, null)
                .thenAccept(invocation.source()::sendMessage);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Permission check
        if (!(invocation.source() instanceof Player)) {
            return Collections.emptyList();
        }
        
        String perm = configManager.getConfigString("permissions.find", "minewarvelocity.find");
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
        String perm = configManager.getConfigString("permissions.find", "minewarvelocity.find");
        return invocation.source().hasPermission(perm);
    }
}
