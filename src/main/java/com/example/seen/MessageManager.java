package com.example.seen;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class MessageManager {
        private final ProxyServer server;
        private final DataManager dataManager;
        private final ConfigManager configManager;
        private final Map<UUID, UUID> lastMessagedBy = new ConcurrentHashMap<>();

        @Inject
        public MessageManager(ProxyServer server, DataManager dataManager, ConfigManager configManager) {
                this.server = server;
                this.dataManager = dataManager;
                this.configManager = configManager;
        }

        /**
         * Sends a private message from sender to receiver.
         * 
         * @param sender   The player sending the message
         * @param receiver The player receiving the message
         * @param message  The message content
         * @return true if the message was sent, false if it was blocked
         */
        public boolean sendMessage(Player sender, Player receiver, String message) {
                // Check if receiver has messaging disabled
                if (!dataManager.isMsgEnabled(receiver.getUniqueId())) {
                        String msg = configManager.getMessage("pm-disabled-receiver")
                                        .replace("%player%", receiver.getUsername());
                        MessageUtils.parseWithPapi(msg, sender, receiver.getUniqueId())
                                        .thenAccept(sender::sendMessage);
                        return false;
                }

                // Check if sender is ignored by receiver
                if (dataManager.isIgnored(receiver.getUniqueId(), sender.getUniqueId())) {
                        String msg = configManager.getMessage("pm-ignored")
                                        .replace("%player%", receiver.getUsername());
                        MessageUtils.parseWithPapi(msg, sender, receiver.getUniqueId())
                                        .thenAccept(sender::sendMessage);
                        return false;
                }

                // Update conversation tracking
                lastMessagedBy.put(receiver.getUniqueId(), sender.getUniqueId());

                // Send message to sender (formatted as sent)
                String sentMsg = configManager.getMessage("message-sent")
                                .replace("%player%", receiver.getUsername())
                                .replace("%message%", message);
                MessageUtils.parseWithPapi(sentMsg, sender, receiver.getUniqueId())
                                .thenAccept(sender::sendMessage);

                // Send message to receiver (formatted as received)
                String receivedMsg = configManager.getMessage("message-received")
                                .replace("%player%", sender.getUsername())
                                .replace("%message%", message);
                MessageUtils.parseWithPapi(receivedMsg, receiver, sender.getUniqueId())
                                .thenAccept(receiver::sendMessage);

                // Social Spy
                String spyPerm = configManager.getConfigString("permissions.socialspy", "minewarvelocity.socialspy");
                boolean anyoneSpying = server.getAllPlayers().stream()
                                .anyMatch(p -> !p.getUniqueId().equals(sender.getUniqueId())
                                                && !p.getUniqueId().equals(receiver.getUniqueId())
                                                && p.hasPermission(spyPerm)
                                                && dataManager.isSocialSpyEnabled(p.getUniqueId()));

                if (anyoneSpying) {
                        MessageUtils.loadUser(sender.getUniqueId()).thenAcceptBoth(
                                        MessageUtils.loadUser(receiver.getUniqueId()),
                                        (senderUser, receiverUser) -> {
                                                String senderPrefix = senderUser != null
                                                                ? senderUser.getCachedData().getMetaData().getPrefix()
                                                                : "";
                                                String senderSuffix = senderUser != null
                                                                ? senderUser.getCachedData().getMetaData().getSuffix()
                                                                : "";
                                                String receiverPrefix = receiverUser != null
                                                                ? receiverUser.getCachedData().getMetaData().getPrefix()
                                                                : "";
                                                String receiverSuffix = receiverUser != null
                                                                ? receiverUser.getCachedData().getMetaData().getSuffix()
                                                                : "";

                                                String spyFormat = configManager.getMessage("social-spy-format")
                                                                .replace("%sender_prefix%",
                                                                                senderPrefix != null ? senderPrefix
                                                                                                : "")
                                                                .replace("%sender_suffix%",
                                                                                senderSuffix != null ? senderSuffix
                                                                                                : "")
                                                                .replace("%receiver_prefix%",
                                                                                receiverPrefix != null ? receiverPrefix
                                                                                                : "")
                                                                .replace("%receiver_suffix%",
                                                                                receiverSuffix != null ? receiverSuffix
                                                                                                : "")
                                                                .replace("%sender%", sender.getUsername())
                                                                .replace("%receiver%", receiver.getUsername())
                                                                .replace("%message%", message);

                                                server.getAllPlayers().stream()
                                                                .filter(p -> !p.getUniqueId()
                                                                                .equals(sender.getUniqueId())
                                                                                && !p.getUniqueId().equals(
                                                                                                receiver.getUniqueId()))
                                                                .filter(p -> p.hasPermission(spyPerm))
                                                                .filter(p -> dataManager
                                                                                .isSocialSpyEnabled(p.getUniqueId()))
                                                                .forEach(spy -> {
                                                                        MessageUtils.parseWithPapi(spyFormat, spy, null)
                                                                                        .thenAccept(spy::sendMessage);
                                                                });
                                        });
                }

                return true;
        }

        public Optional<UUID> getLastMessagedBy(UUID uuid) {
                return Optional.ofNullable(lastMessagedBy.get(uuid));
        }

        public void setLastMessagedBy(UUID target, UUID sender) {
                lastMessagedBy.put(target, sender);
        }

        /**
         * Sends a bot message with custom formatting.
         * 
         * @param player  The player involved
         * @param message The message content
         */
        public void sendBotMessage(Player player, String message, boolean outgoing) {
                String formatKey = outgoing ? "private-message-sent-format" : "private-message-format";
                String defaultFormat = outgoing
                                ? "<gray>(To <#0adef7>Sahorah<gray>) <#ccffff>{message}"
                                : "<gray>(From <#0adef7>Sahorah<gray>) <#ccffff>{message}";

                String format = defaultFormat;

                try {
                        // Fetch format from Sahorah directly for true unified config
                        var plugin = server.getPluginManager().getPlugin("minewar-sahorah");
                        if (plugin.isPresent()) {
                                var instance = plugin.get().getInstance().get();
                                var getFormat = instance.getClass().getMethod("getMessageFormat", String.class);
                                String fetched = (String) getFormat.invoke(instance, formatKey);
                                if (fetched != null && !fetched.isEmpty()) {
                                        format = fetched;
                                }
                        }
                } catch (Exception ignored) {
                        // Use defaultFormat if Sahorah API is unavailable
                }

                if (format.contains("@{player}")) {
                        format = defaultFormat;
                }

                final String finalFormat = format;
                // Fetch human LuckPerms data
                MessageUtils.loadUser(player.getUniqueId()).thenAccept(user -> {
                        String suffix = "";
                        if (user != null) {
                                suffix = user.getCachedData().getMetaData().getSuffix();
                                if (suffix == null)
                                        suffix = "";
                        }

                        String rendered = finalFormat
                                        .replace("{player}", player.getUsername())
                                        .replace("{suffix_other}", suffix)
                                        .replace("{message}", message);

                        // Update conversation tracking so /reply works
                        UUID botUuid = UUID.nameUUIDFromBytes("Minewar-Sahorah-Bot".getBytes());
                        if (outgoing) {
                                // Player -> Bot: Next /reply from bot goes to player
                                lastMessagedBy.put(botUuid, player.getUniqueId());
                        } else {
                                // Bot -> Player: Next /reply from player goes to bot
                                lastMessagedBy.put(player.getUniqueId(), botUuid);
                        }

                        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                        .deserialize(rendered));
                });
        }
}
