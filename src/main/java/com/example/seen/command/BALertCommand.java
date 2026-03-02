package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DiscordManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class BALertCommand implements SimpleCommand {
    private final DiscordManager discordManager;
    private final ConfigManager configManager;

    public BALertCommand(DiscordManager discordManager, ConfigManager configManager) {
        this.discordManager = discordManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.source() instanceof com.velocitypowered.api.proxy.Player) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>This command can only be used from the console."));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0 || (args.length == 1 && args[0].isEmpty())) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>Usage: /balert <channel_id (optional)> <message>"));
            return;
        }

        String targetChannelId = configManager.getConfigString("discord.balert-log-channel");
        String message;

        if (args.length > 1 && args[0].matches("\\d{17,20}")) {
            targetChannelId = args[0];
            message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        } else {
            message = String.join(" ", args);
        }

        if (targetChannelId == null || targetChannelId.isEmpty()
                || targetChannelId.equals("YOUR_BALERT_CHANNEL_ID_HERE")) {
            invocation.source().sendMessage(Component.text("BALert log channel ID not configured!",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        boolean success = discordManager.sendAlertToChannel("Console", message, targetChannelId);
        if (success) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<green>Broadcast alert sent to Discord (" + targetChannelId + ")."));
        } else {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>Failed to send alert. Check channel ID and bot status."));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return !(invocation.source() instanceof com.velocitypowered.api.proxy.Player);
    }
}
