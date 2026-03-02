package com.example.seen.listeners;

import com.example.seen.MessageManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.plugin.PluginContainer;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Listens for SahorahResponseEvent from the minewar-sahorah plugin.
 * Uses reflection to avoid a compile-time dependency.
 */
public class SahorahResponseListener {
    private final MessageManager messageManager;
    private final ProxyServer server;

    public SahorahResponseListener(MessageManager messageManager, ProxyServer server) {
        this.messageManager = messageManager;
        this.server = server;
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onSahorahResponse(Object event) {
        // Check if the event class name matches
        if (!event.getClass().getName().equals("net.hotblox.velocityaibot.SahorahResponseEvent")) {
            return;
        }

        try {
            // Use reflection to get player and message
            Method getPlayerMethod = event.getClass().getMethod("getPlayer");
            Method getMessageMethod = event.getClass().getMethod("getMessage");
            Method isPrivateMethod = event.getClass().getMethod("isPrivate");

            Player targetPlayer = (Player) getPlayerMethod.invoke(event);
            String rawMessage = (String) getMessageMethod.invoke(event);
            boolean isPrivate = (boolean) isPrivateMethod.invoke(event);

            if (targetPlayer == null || rawMessage == null)
                return;

            // Fetch format from Sahorah directly for true unified config
            String formatKey = isPrivate ? "private-message-format" : "message-format";
            String defaultFormat = isPrivate
                    ? "<gray>(From <#0adef7>Sahorah<gray>) <#ccffff>{message}"
                    : "<gray>{suffix_other}Sahorah: <white>{message}";

            String format = defaultFormat;
            try {
                var pluginOpt = server.getPluginManager().getPlugin("minewar-sahorah");
                if (pluginOpt.isPresent()) {
                    PluginContainer plugin = pluginOpt.get();
                    var instance = plugin.getInstance().get();
                    var getFormat = instance.getClass().getMethod("getMessageFormat", String.class);
                    String fetched = (String) getFormat.invoke(instance, formatKey);
                    if (fetched != null && !fetched.isEmpty()) {
                        format = fetched;
                    }
                }
            } catch (Exception ignored) {
            }

            final String finalFormat = format;
            final String finalMessage = rawMessage;

            // Absolute debugging
            server.getConsoleCommandSource().sendMessage(net.kyori.adventure.text.Component.text(
                    "[Sahorah-Debug] Caught ResponseEvent for " + targetPlayer.getUsername() + ": " + finalMessage));

            if (isPrivate) {
                // Resolve placeholders for human recipient (the actual player talking to the
                // bot)
                com.example.seen.MessageUtils.loadUser(targetPlayer.getUniqueId()).thenAccept(user -> {
                    String suffix = "";
                    if (user != null) {
                        suffix = user.getCachedData().getMetaData().getSuffix();
                        if (suffix == null)
                            suffix = "";
                    }

                    String rendered = finalFormat
                            .replace("{player}", targetPlayer.getUsername()) // Human Username
                            .replace("{suffix_other}", suffix) // Human Suffix
                            .replace("{message}", finalMessage);

                    targetPlayer.sendMessage(
                            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(rendered));

                    // Update conversation tracking so /reply works
                    messageManager.setLastMessagedBy(targetPlayer.getUniqueId(),
                            UUID.nameUUIDFromBytes("Minewar-Sahorah-Bot".getBytes()));
                });
            } else {
                // Public Broadcast - Use bot's data
                String rendered = finalFormat
                        .replace("{player}", "Sahorah")
                        .replace("{suffix_other}", "")
                        .replace("{message}", finalMessage);

                server.getAllPlayers().forEach(p -> p.sendMessage(
                        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(rendered)));
            }

        } catch (Exception e) {
            // Silently fail if reflection fails
        }
    }
}
