package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * /adminmessage command - sends private messages that bypass toggles and
 * ignores. Uses RawCommand to avoid signed command signature validation.
 */
public class AdminMessageCommand implements RawCommand {
    private final ProxyServer server;
    private final ConfigManager configManager;
    private final com.example.seen.MessageManager messageManager;
    private final com.example.seen.VanishManager vanishManager;

    public AdminMessageCommand(ProxyServer server, ConfigManager configManager,
            com.example.seen.MessageManager messageManager, com.example.seen.VanishManager vanishManager) {
        this.server = server;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.vanishManager = vanishManager;
    }

    /**
     * Suggests online player names for the first argument.
     * Returns empty list once the player has typed past the player name.
     */
    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player))
            return Collections.emptyList();

        Player sender = (Player) invocation.source();
        String args = invocation.arguments();

        // Once a space is present the player is typing the message body — no
        // suggestions
        if (args.contains(" "))
            return Collections.emptyList();

        String prefix = args.toLowerCase();
        String seeVanishPerm = configManager.getConfigString("permissions.vanish_see", "minewar.vanish.see");
        boolean canSeeVanished = sender.hasPermission(seeVanishPerm);

        return server.getAllPlayers().stream()
                .filter(p -> !p.getUniqueId().equals(sender.getUniqueId()))
                .filter(p -> canSeeVanished || !vanishManager.isVanished(p.getUniqueId()))
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .collect(Collectors.toList());
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("players-only")));
            return;
        }

        Player sender = (Player) invocation.source();
        String rawArgs = invocation.arguments();

        if (rawArgs.trim().isEmpty()) {
            MessageUtils.parseWithPapi(configManager.getMessage("usage-adminmessage"), sender, null)
                    .thenAccept(sender::sendMessage);
            return;
        }

        // Manual parsing to avoid signature validation
        String[] parts = rawArgs.split("\\s+", 2);
        if (parts.length < 2) {
            MessageUtils.parseWithPapi(configManager.getMessage("usage-adminmessage"), sender, null)
                    .thenAccept(sender::sendMessage);
            return;
        }

        String targetName = parts[0];
        String message = parts[1];

        Optional<Player> target = server.getPlayer(targetName);

        if (target.isPresent()) {
            Player receiver = target.get();

            // Send message to sender (formatted as sent)
            String sentMsg = configManager.getMessage("admin-message-sent")
                    .replace("%player%", receiver.getUsername())
                    .replace("%message%", message);

            MessageUtils.parseWithPapi(sentMsg, sender, receiver.getUniqueId())
                    .thenAccept(sender::sendMessage);

            // Send message to receiver (formatted as received)
            String receivedMsg = configManager.getMessage("admin-message-received")
                    .replace("%player%", sender.getUsername())
                    .replace("%message%", message);

            MessageUtils.parseWithPapi(receivedMsg, receiver, sender.getUniqueId())
                    .thenAccept(receiver::sendMessage);

            // Set reply tracking so receiver can use /r to reply to admin
            messageManager.setLastMessagedBy(receiver.getUniqueId(), sender.getUniqueId());

        } else {
            String msg = configManager.getMessage("msg-player-not-found")
                    .replace("%player%", targetName);
            MessageUtils.parseWithPapi(msg, sender, null)
                    .thenAccept(sender::sendMessage);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return false;
        }
        Player player = (Player) invocation.source();
        String perm = configManager.getConfigString("permissions.adminmessage", "minewarvelocity.adminmessage");
        return player.hasPermission(perm);
    }
}
