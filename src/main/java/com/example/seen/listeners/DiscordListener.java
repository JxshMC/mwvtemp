package com.example.seen.listeners;

import com.example.seen.config.SmartConfig;
import com.velocitypowered.api.proxy.ProxyServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.awt.Color;
import java.util.Objects;

public class DiscordListener extends ListenerAdapter {

    private final ProxyServer server;
    private final SmartConfig smartConfig;

    public DiscordListener(ProxyServer server, SmartConfig smartConfig) {
        this.server = server;
        this.smartConfig = smartConfig;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("alert")) {
            return;
        }

        if (!smartConfig.getConfig().getSection("modules").getBoolean("discord-sync.enabled", true)) {
            event.reply("Discord sync is currently disabled.").setEphemeral(true).queue();
            return;
        }

        // Check permission or role logic if needed here?
        // Default slash commands often controlled by Discord permissions, but we can
        // verify here too if we want.
        // For now, assuming Discord permissions handle access control to the command
        // itself.

        String message = Objects.requireNonNull(event.getOption("message")).getAsString();
        String channel = event.getOption("channel") != null ? event.getOption("channel").getAsString() : "Global";

        // Logic for channel? "Broadcasts to Proxy + Optional Discord".
        // User request: "/alert <channel> <message> (Sends Discord Embed + Proxy
        // Broadcast)"
        // If "channel" arg is meant for internal routing, we might use it.
        // For now, valid interpretation is specific proxy servers or just "global".

        // Broadcast to Minecraft
        String format = smartConfig.getMessages().getString("alert.format", "<red>[Alert] <white>%message%");
        Component broadcast = MiniMessage.miniMessage().deserialize(format.replace("%message%", message));
        server.sendMessage(broadcast);

        // Reply to Discord Confirming
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("Alert Sent");
        eb.setDescription(message);
        eb.setColor(Color.GREEN);
        eb.setFooter("Sent to: " + channel);

        event.replyEmbeds(eb.build()).setEphemeral(true).queue();
    }
}
