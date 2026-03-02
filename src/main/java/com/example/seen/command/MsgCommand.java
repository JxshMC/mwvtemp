package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.FakePlayerRegistry;
import com.example.seen.MessageManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.seen.VanishManager;

/**
 * /msg command - sends private messages.
 * Uses RawCommand to avoid signed command signature validation.
 */
public class MsgCommand implements RawCommand {
    private final ProxyServer server;
    private final MessageManager messageManager;
    private final ConfigManager configManager;
    private final VanishManager vanishManager;
    private final FakePlayerRegistry fakePlayerRegistry;

    public MsgCommand(ProxyServer server, MessageManager messageManager, ConfigManager configManager,
            VanishManager vanishManager, FakePlayerRegistry fakePlayerRegistry) {
        this.server = server;
        this.messageManager = messageManager;
        this.configManager = configManager;
        this.vanishManager = vanishManager;
        this.fakePlayerRegistry = fakePlayerRegistry;
    }

    /**
     * Suggests online player names for the first argument.
     * Returns empty list once the player has typed past the player name (space
     * present).
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
        String botName = configManager.getConfig().getString("bot.username", "Sahorah");

        List<String> suggestions = server.getAllPlayers().stream()
                .filter(p -> !p.getUniqueId().equals(sender.getUniqueId()))
                .filter(p -> canSeeVanished || !vanishManager.isVanished(p.getUniqueId()))
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .collect(Collectors.toList());

        if (botName.toLowerCase().startsWith(prefix)) {
            if (!suggestions.contains(botName)) {
                suggestions.add(botName);
            }
        }

        // Inject active LoshFP NPC names
        for (String npcName : fakePlayerRegistry.getAllNames()) {
            if (npcName.toLowerCase().startsWith(prefix) && !suggestions.contains(npcName)) {
                suggestions.add(npcName);
            }
        }

        return suggestions;
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
            MessageUtils.parseWithPapi(configManager.getMessage("usage-msg"), sender, null)
                    .thenAccept(sender::sendMessage);
            return;
        }

        // Manual parsing to avoid signature validation
        String[] parts = rawArgs.split("\\s+", 2);
        if (parts.length < 2) {
            MessageUtils.parseWithPapi(configManager.getMessage("usage-msg"), sender, null)
                    .thenAccept(sender::sendMessage);
            return;
        }

        String targetName = parts[0];
        String message = parts[1];

        // Check if sending to Sahorah
        String botName = configManager.getConfig().getString("bot.username", "Sahorah");
        if (targetName.equalsIgnoreCase(botName)) {
            java.util.UUID botUuid = java.util.UUID.nameUUIDFromBytes("Minewar-Sahorah-Bot".getBytes());

            // Set conversation tracking so /r works
            messageManager.setLastMessagedBy(sender.getUniqueId(), botUuid);

            // Display "To Sahorah" message to sender
            messageManager.sendBotMessage(sender, message, true);

            // Trigger AI response loop in Sahorah plugin
            server.getPluginManager().getPlugin("minewar-sahorah").ifPresent(botPlugin -> {
                try {
                    Class<?> eventClass = Class.forName("net.hotblox.velocityaibot.SahorahMessageEvent");
                    Object event = eventClass.getConstructor(Player.class, String.class).newInstance(sender, message);
                    server.getEventManager().fire(event);
                } catch (Exception e) {
                    // Fail silently if plugin not yet initialized
                }
            });
            return;
        }

        // ── LoshFP Fake Player Intercept ──────────────────────────────────────
        if (fakePlayerRegistry.contains(targetName)) {
            // Use the exact same "message-sent" format that MessageManager.sendMessage()
            // uses
            // so the sender cannot distinguish a fake NPC from a real player.
            String sentMsg = configManager.getMessage("message-sent")
                    .replace("%player%", targetName)
                    .replace("%message%", message);
            MessageUtils.parseWithPapi(sentMsg, sender, null)
                    .thenAccept(sender::sendMessage);

            // Track last-messaged so /r works immediately after messaging an NPC
            UUID fakeUuid = UUID.nameUUIDFromBytes(("FakePlayer:" + targetName.toLowerCase()).getBytes());
            messageManager.setLastMessagedBy(sender.getUniqueId(), fakeUuid);
            return;
        }

        Optional<Player> target = server.getPlayer(targetName);

        if (target.isPresent()) {
            Player receiver = target.get();

            // Check vanish visibility
            if (!vanishManager.canSee(sender, receiver)) {
                String msg = configManager.getMessage("msg-player-not-found")
                        .replace("%player%", targetName);
                MessageUtils.parseWithPapi(msg, sender, null)
                        .thenAccept(sender::sendMessage);
                return;
            }

            // Check self-messaging
            if (receiver.getUniqueId().equals(sender.getUniqueId())) {
                sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(configManager.getMessage("cannot-message-self")));
                return;
            }

            // Send message (MessageManager handles blocking logic)
            messageManager.sendMessage(sender, receiver, message);
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
        String perm = configManager.getConfigString("permissions.msg", "minewarvelocity.msg");
        return player.hasPermission(perm);
    }
}
