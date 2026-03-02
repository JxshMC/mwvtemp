package com.example.seen.command;

import com.example.seen.DiscordManager;
import com.example.seen.MessageUtils;
import com.example.seen.config.SmartConfig;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;
import com.velocitypowered.api.proxy.ProxyServer; // Added this import, as ProxyServer is used but not imported.

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class AlertCommand implements RawCommand {

    private final ProxyServer server;
    private final SmartConfig smartConfig;
    private final DiscordManager discordManager;

    public AlertCommand(ProxyServer server, SmartConfig smartConfig, DiscordManager discordManager) {
        this.server = server;
        this.smartConfig = smartConfig;
        this.discordManager = discordManager;
    }

    @Override
    public void execute(Invocation invocation) {
        String rawArgs = invocation.arguments();

        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        // Reload Subcommand
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!invocation.source().hasPermission(
                    smartConfig.getConfig().getString("permissions.alert_reload", "minewar.alert.reload"))) {
                invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(
                        smartConfig.getMessages().getString("no-permission", "<red>No permission.")));
                return;
            }

            smartConfig.reloadAsync().thenRun(() -> {
                invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(
                        smartConfig.getMessages().getString("alert.reload-success", "<green>Configuration reloaded.")));
            });
            return;
        }

        // Alert Logic
        if (!invocation.source()
                .hasPermission(smartConfig.getConfig().getString("permissions.alert", "minewar.alert"))) {
            invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(
                    smartConfig.getMessages().getString("no-permission", "<red>No permission.")));
            return;
        }

        if (invocation.source() instanceof Player) {
            Player player = (Player) invocation.source();
            if (!discordManager.isLinked(player.getUniqueId())) {
                invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(
                        smartConfig.getMessages().getString("discord-link-discord-required",
                                "<#ccffff>You must link your account to the discord first.")));
                return;
            }
        }

        if (args.length == 0) {
            invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(
                    smartConfig.getMessages().getString("alert.usage",
                            "<red>Usage: /alert <channel_id (optional)> <message>")));
            return;
        }

        String targetChannelId = smartConfig.getConfig().getString("discord.alert-log-channel");
        String message;

        // Check if first arg is a numeric ID (Discord Channel ID)
        if (args.length > 1 && args[0].matches("\\d{17,20}")) {
            targetChannelId = args[0];
            message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        } else {
            message = String.join(" ", args);
        }

        // Broadcast to Minecraft - with placeholders
        String senderName = (invocation.source() instanceof Player) ? ((Player) invocation.source()).getUsername()
                : "Console";
        UUID senderUuid = (invocation.source() instanceof Player)
                ? ((Player) invocation.source()).getUniqueId()
                : null;

        String alertFormat = smartConfig.getMessages().getString("alert.format", "<red>[Alert] <white>%message%");
        String formattedMsg = MessageUtils.applyPlaceholders(alertFormat.replace("%message%", message),
                senderName,
                senderUuid, false);

        server.sendMessage(MessageUtils.renderMessage(formattedMsg));

        // Log to Discord
        if (smartConfig.getConfig().getBoolean("modules.alert-logging.enabled", true) && discordManager != null) {
            if (targetChannelId != null && !targetChannelId.trim().isEmpty()
                    && !targetChannelId.equals("YOUR_ALERT_LOG_CHANNEL_ID")) {
                discordManager.sendAlertToChannel(senderName, message, targetChannelId);
            }
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source()
                .hasPermission(smartConfig.getConfig().getString("permissions.alert", "minewar.alert"));
    }
}
