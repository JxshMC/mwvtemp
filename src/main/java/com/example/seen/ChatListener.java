package com.example.seen;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;

public class ChatListener {
    private final ProxyServer server;
    private final DataManager dataManager;
    private final DiscordManager discordManager;

    public ChatListener(ProxyServer server, DataManager dataManager, DiscordManager discordManager) {
        this.server = server;
        this.dataManager = dataManager;
        this.discordManager = discordManager;
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (dataManager.isStaffChatToggled(player.getUniqueId())) {
            // Manually trigger the proxy /sc command
            // This ensures it stays on the proxy only and bypasses backend chat forwarding
            StringBuilder cmd = new StringBuilder("staffchat ");
            cmd.append(event.getMessage());
            server.getCommandManager().executeAsync(player, cmd.toString());

            // Definitively block the original chat packet from being forwarded
            event.setResult(PlayerChatEvent.ChatResult.denied());
        } else {
            // Strict LuckPerms Meta Refresh
            LuckPerms luckPerms = LuckPermsProvider.get();
            luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAccept(user -> {
                String prefix = (user != null) ? user.getCachedData().getMetaData().getPrefix() : "";

                // Use StringBuilder to avoid invokedynamic string concatenation which trips up
                // Shadow/ASM
                StringBuilder sb = new StringBuilder();
                if (prefix != null && !prefix.isEmpty()) {
                    sb.append(prefix).append(" ").append(player.getUsername()).append(": ").append(event.getMessage());
                } else {
                    sb.append(event.getMessage());
                }

                String formattedMessage = sb.toString();
                discordManager.sendToDiscord(player, formattedMessage);
            });
        }
    }
}
