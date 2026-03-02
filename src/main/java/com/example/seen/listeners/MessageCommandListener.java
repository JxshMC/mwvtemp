package com.example.seen.listeners;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.MessageUtils;
import com.example.seen.VanishManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Optional;
import java.util.UUID;
import com.example.seen.MessageManager;

/**
 * Signature-safe message command interceptor.
 * Intercepts /msg and /tell commands to check message toggles and ignore lists
 * WITHOUT denying signed commands (which causes fatal errors in 1.19+).
 */
public class MessageCommandListener {
    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;
    private final VanishManager vanishManager;
    private final MessageManager messageManager;

    public MessageCommandListener(ProxyServer server, DataManager dataManager,
            ConfigManager configManager, VanishManager vanishManager, MessageManager messageManager) {
        this.server = server;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.vanishManager = vanishManager;
        this.messageManager = messageManager;
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        // Only intercept player commands
        if (!(event.getCommandSource() instanceof Player)) {
            return;
        }

        String command = event.getCommand();
        String[] parts = command.split(" ", 3);

        if (parts.length == 0) {
            return;
        }

        String cmd = parts[0].toLowerCase();

        // Check for Reply command
        boolean isReply = cmd.equals("r") || cmd.equals("reply");

        // Only intercept message commands
        if (!cmd.equals("msg") && !cmd.equals("tell") && !cmd.equals("w") && !cmd.equals("m") && !isReply) {
            return;
        }

        Player sender = (Player) event.getCommandSource();

        // Handle Reply
        if (isReply) {
            if (parts.length < 2) {
                return; // Let system show usage
            }

            // Check permission
            String msgPerm = configManager.getConfigString("permissions.msg", "minewar.msg");
            if (!sender.hasPermission(msgPerm)) {
                return; // Let system handle no-perm
            }

            Optional<UUID> lastSenderUUID = messageManager.getLastMessagedBy(sender.getUniqueId());
            if (lastSenderUUID.isEmpty()) {
                event.setResult(CommandResult.command(""));
                MessageUtils.parseWithPapi(configManager.getMessage("no-reply-target"), sender, null)
                        .thenAccept(sender::sendMessage);
                return;
            }

            Optional<Player> target = server.getPlayer(lastSenderUUID.get());
            if (target.isPresent()) {
                String message = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length));
                // Block the original command and execute via manager
                event.setResult(CommandResult.command(""));
                messageManager.sendMessage(sender, target.get(), message);
            } else {
                event.setResult(CommandResult.command(""));
                String msg = configManager.getMessage("msg-player-not-found").replace("%player%", "Player");
                MessageUtils.parseWithPapi(msg, sender, lastSenderUUID.get())
                        .thenAccept(sender::sendMessage);
            }
            return;
        }

        // Handle Msg/Tell
        // Need at least target and message
        if (parts.length < 3) {
            return; // Let command handler show usage
        }

        String targetName = parts[1];

        // Find target player
        Optional<Player> target = server.getPlayer(targetName);
        if (target.isEmpty()) {
            return; // Let command handler show "player not found"
        }

        Player receiver = target.get();

        // Check vanish
        if (!vanishManager.canSee(sender, receiver)) {
            return; // Let command handler show "player not found"
        }

        // Check if receiver has messages disabled
        if (!dataManager.isMsgEnabled(receiver.getUniqueId())) {
            event.setResult(CommandResult.command(""));
            String msg = configManager.getMessage("pm-disabled-receiver")
                    .replace("%player%", receiver.getUsername());
            MessageUtils.parseWithPapi(msg, sender, receiver.getUniqueId())
                    .thenAccept(sender::sendMessage);
            return;
        }

        // Check if sender is ignored by receiver
        if (dataManager.isIgnored(receiver.getUniqueId(), sender.getUniqueId())) {
            event.setResult(CommandResult.command(""));
            String msg = configManager.getMessage("pm-ignored")
                    .replace("%player%", receiver.getUsername());
            MessageUtils.parseWithPapi(msg, sender, receiver.getUniqueId())
                    .thenAccept(sender::sendMessage);
            return;
        }

        // All checks passed, handle the message proxy-side and block original
        String message = parts[2];

        // --- Sahorah Bot Interception ---
        UUID botUuid = UUID.nameUUIDFromBytes("Minewar-Sahorah-Bot".getBytes());
        if (receiver.getUniqueId().equals(botUuid) || receiver.getUsername().equalsIgnoreCase("Sahorah")) {
            event.setResult(CommandResult.command(""));
            message = parts[2];

            // 1. Render outgoing message (Player -> Bot)
            // This method internally updates lastMessagedBy tracking for the botUuid
            messageManager.sendBotMessage(sender, message, true);

            // 2. Fire SahorahMessageEvent to trigger AI
            try {
                Class<?> eventClass = Class.forName("net.hotblox.velocityaibot.SahorahMessageEvent");
                Object messageEvent = eventClass.getConstructor(Player.class, String.class).newInstance(sender,
                        message);
                server.getEventManager().fire(messageEvent);
            } catch (Exception e) {
                sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<red>AI Bot integration error."));
            }
            return;
        }
        // --------------------------------

        event.setResult(CommandResult.command(""));
        messageManager.sendMessage(sender, receiver, message);
    }

}
