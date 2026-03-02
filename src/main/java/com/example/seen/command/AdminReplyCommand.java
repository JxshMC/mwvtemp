package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.MessageManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /adminreply (/ar, /radmin) command - Admin reply that bypasses msg_enabled
 * and ignore list.
 * Uses RawCommand to avoid signed command signature validation.
 */
public class AdminReplyCommand implements RawCommand {
    private final ProxyServer server;
    private final MessageManager messageManager;
    private final ConfigManager configManager;
    private final DataManager dataManager;

    public AdminReplyCommand(ProxyServer server, MessageManager messageManager,
            ConfigManager configManager, DataManager dataManager) {
        this.server = server;
        this.messageManager = messageManager;
        this.configManager = configManager;
        this.dataManager = dataManager;
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

        // Permission check
        String perm = configManager.getConfigString("permissions.adminreply", "minewarvelocity.adminreply");
        if (!sender.hasPermission(perm)) {
            MessageUtils.parseWithPapi(configManager.getMessage("no-permission"), sender, null)
                    .thenAccept(sender::sendMessage);
            return;
        }

        if (rawArgs.trim().isEmpty()) {
            MessageUtils.parseWithPapi(configManager.getMessage("usage-adminreply"), sender, null)
                    .thenAccept(sender::sendMessage);
            return;
        }

        UUID lastMessagedBy = messageManager.getLastMessagedBy(sender.getUniqueId()).orElse(null);

        if (lastMessagedBy == null) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("no-reply-target")));
            return;
        }

        Optional<Player> target = server.getPlayer(lastMessagedBy);

        if (target.isPresent()) {
            Player receiver = target.get();
            String message = rawArgs.trim();

            // Send admin message (bypasses msg_enabled and ignore list)
            sendAdminReply(sender, receiver, message);
        } else {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("msg-player-not-found")
                            .replace("%player%", dataManager.getNameCache().entrySet().stream()
                                    .filter(e -> e.getValue().equals(lastMessagedBy))
                                    .map(e -> e.getKey())
                                    .findFirst().orElse("Unknown"))));
        }
    }

    /**
     * Send admin reply message, bypassing msg_enabled and ignore list.
     */
    private void sendAdminReply(Player sender, Player receiver, String message) {
        // Format messages
        String sentMsg = configManager.getMessage("admin-reply-sent")
                .replace("%player%", receiver.getUsername())
                .replace("%message%", message);

        String receivedMsg = configManager.getMessage("admin-reply-received")
                .replace("%player%", sender.getUsername())
                .replace("%message%", message);

        // Send to sender
        MessageUtils.parseWithPapi(sentMsg, sender, receiver.getUniqueId())
                .thenAccept(sender::sendMessage);

        // Send to receiver (bypasses all checks)
        MessageUtils.parseWithPapi(receivedMsg, receiver, sender.getUniqueId())
                .thenAccept(receiver::sendMessage);

        // Update reply map for both players
        messageManager.setLastMessagedBy(sender.getUniqueId(), receiver.getUniqueId());
        messageManager.setLastMessagedBy(receiver.getUniqueId(), sender.getUniqueId());

        // Social spy notification
        String spyFormat = configManager.getMessage("social-spy-format")
                .replace("%sender%", sender.getUsername())
                .replace("%receiver%", receiver.getUsername())
                .replace("%message%", "[ADMIN] " + message);

        server.getAllPlayers().forEach(p -> {
            if (!p.equals(sender) && !p.equals(receiver) && dataManager.isSocialSpyEnabled(p.getUniqueId())) {
                MessageUtils.parseWithPapi(spyFormat, p, sender.getUniqueId())
                        .thenAccept(p::sendMessage);
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // No suggestions for reply command (message is free-form)
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String perm = configManager.getConfigString("permissions.adminreply", "minewarvelocity.adminreply");
        return invocation.source().hasPermission(perm);
    }
}
