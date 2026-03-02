package com.example.seen.listeners;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.DiscordManager;
import com.example.seen.MessageUtils;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

import java.util.UUID;

/**
 * Handles proxy-wide global chat forwarding received via the
 * {@code minewar:globalchat}
 * plugin-message channel.
 *
 * <p>
 * <b>Rank Logic:</b> If the sender is in the {@code "owner"} group (checked via
 * LuckPerms),
 * the {@code owner-global-chat-format} from messages.yml is used; otherwise the
 * default
 * {@code global-chat-format} is applied.
 */
public class GlobalChatListener {

    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;
    private final DiscordManager discordManager;
    private final MinecraftChannelIdentifier channel;

    @com.google.inject.Inject
    public GlobalChatListener(ProxyServer server, DataManager dataManager, ConfigManager configManager,
            DiscordManager discordManager) {
        this.server = server;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.discordManager = discordManager;
        this.channel = MinecraftChannelIdentifier.from("minewar:globalchat");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel))
            return;
        if (!(event.getSource() instanceof com.velocitypowered.api.proxy.ServerConnection))
            return;

        com.velocitypowered.api.proxy.ServerConnection connection = (com.velocitypowered.api.proxy.ServerConnection) event
                .getSource();
        String originServerName = connection.getServerInfo().getName();

        byte[] data = event.getData();
        if (data == null)
            return;
        ByteArrayDataInput in = ByteStreams.newDataInput(data);
        String subChannel = in.readUTF();

        if (!subChannel.equals("GlobalChat"))
            return;

        String senderName = in.readUTF();
        String message = in.readUTF();
        String formatString = in.readUTF();
        String mentionFormatParam = in.readUTF();
        String mentionSoundParam = in.readUTF();
        float mentionVolume = in.readFloat();
        float mentionPitch = in.readFloat();

        // Broadcast to every player NOT on the origin server
        server.getAllPlayers().forEach(p -> {
            if (p.getCurrentServer().isPresent()
                    && p.getCurrentServer().get().getServerInfo().getName().equals(originServerName)) {
                return;
            }

            // Mention detection: highlight the viewer's own username in the message
            boolean isMentioned = message.toLowerCase().contains(p.getUsername().toLowerCase());
            String textToForward = message;
            if (isMentioned) {
                String highlight = mentionFormatParam.replace("%player%", p.getUsername());
                // Simple replacement in the raw message before applying format
                textToForward = textToForward.replaceAll("(?i)" + java.util.regex.Pattern.quote(p.getUsername()),
                        highlight);
            }

            // Apply the format string received from Paper
            String finalText = formatString.replace("%message%", textToForward)
                    .replace("%player%", senderName);

            // Deserialize via MiniMessage
            net.kyori.adventure.text.Component component = net.kyori.adventure.text.minimessage.MiniMessage
                    .miniMessage().deserialize(finalText);

            p.sendMessage(component);

            if (isMentioned) {
                try {
                    Key key = Key.key(mentionSoundParam);
                    Sound sound = Sound.sound(key, Sound.Source.PLAYER, mentionVolume, mentionPitch);
                    p.playSound(sound);
                } catch (Exception ignored) {
                }
            }
        });

        // MC to Discord Sync (Optional, depends on config)
        if (configManager.isModuleEnabled("discord-sync")) {
            server.getPlayer(senderName).ifPresent(p -> {
                if (discordManager.isLinked(p.getUniqueId())) {
                    discordManager.sendToDiscord(p, message);
                }
            });
        }
    }
}
