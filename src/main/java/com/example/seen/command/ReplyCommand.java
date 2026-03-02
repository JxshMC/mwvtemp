package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.MessageManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Optional;
import java.util.UUID;

/**
 * /reply (/r) command - replies to the last person who messaged you.
 * Uses RawCommand to avoid signed command signature validation.
 */
public class ReplyCommand implements RawCommand {
    private final ProxyServer server;
    private final MessageManager messageManager;
    private final ConfigManager configManager;

    public ReplyCommand(ProxyServer server, MessageManager messageManager, ConfigManager configManager) {
        this.server = server;
        this.messageManager = messageManager;
        this.configManager = configManager;
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
            MessageUtils.parseWithPapi(configManager.getMessage("usage-reply"), sender, null)
                    .thenAccept(sender::sendMessage);
            return;
        }

        UUID lastMessagedBy = messageManager.getLastMessagedBy(sender.getUniqueId()).orElse(null);

        if (lastMessagedBy == null) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("no-one-to-reply")));
            return;
        }

        java.util.UUID botUuid = java.util.UUID.nameUUIDFromBytes("Minewar-Sahorah-Bot".getBytes());
        if (lastMessagedBy.equals(botUuid)) {
            String message = rawArgs.trim();

            // Display outgoing message to player
            messageManager.sendBotMessage(sender, message, true);

            // Trigger AI response loop in Sahorah plugin
            server.getPluginManager().getPlugin("minewar-sahorah").ifPresent(botPlugin -> {
                try {
                    Class<?> eventClass = Class.forName("net.hotblox.velocityaibot.SahorahMessageEvent");
                    Object event = eventClass.getConstructor(Player.class, String.class).newInstance(sender, message);
                    server.getEventManager().fire(event);
                } catch (Exception e) {
                    // Fail silently
                }
            });
            return;
        }

        Optional<Player> target = server.getPlayer(lastMessagedBy);

        if (target.isPresent()) {
            Player receiver = target.get();
            String message = rawArgs.trim();

            // Send message (MessageManager handles blocking logic)
            messageManager.sendMessage(sender, receiver, message);
        } else {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("reply-target-offline")));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return false;
        }
        Player player = (Player) invocation.source();
        String perm = configManager.getConfigString("permissions.reply", "minewarvelocity.reply");
        return player.hasPermission(perm);
    }
}
