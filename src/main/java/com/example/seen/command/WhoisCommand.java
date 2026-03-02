package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.DiscordManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import java.util.stream.Collectors;
import com.velocitypowered.api.proxy.ProxyServer;

public class WhoisCommand implements RawCommand {

    private final DiscordManager discordManager;
    private final ConfigManager configManager;
    private final DataManager dataManager;
    private final ProxyServer proxyServer;

    public WhoisCommand(DiscordManager discordManager, ConfigManager configManager, DataManager dataManager,
            ProxyServer proxyServer) {
        this.discordManager = discordManager;
        this.configManager = configManager;
        this.dataManager = dataManager;
        this.proxyServer = proxyServer;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("players-only")));
            return;
        }

        Player player = (Player) invocation.source();
        String rawArgs = invocation.arguments();

        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        String perm = configManager.getConfigString("permissions.whois", "minewar.discord.whois");
        if (!player.hasPermission(perm)) {
            MessageUtils.parseWithPapi(configManager.getMessage("no-permission"), player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        if (args.length == 0) {
            MessageUtils.parseWithPapi(configManager.getMessage("discord-whois-usage"), player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        String targetName = args[0];

        // Resolve Target
        // Try online first
        UUID targetUuid = null;

        proxyServer.getPlayer(targetName);
        // Velocity proxy getPlayer
        // invocation.source().getProxy().getPlayer(targetName) but we don't have direct
        // access here easily without passing proxy or using helper?
        // Actually simplest is checking DataManager cache or trying to fetch UUID.

        // Let's use DataManager cache for exact match
        targetUuid = dataManager.getUUID(targetName);

        if (targetUuid == null) {
            MessageUtils.parseWithPapi(configManager.getMessage("discord-user-not-found"), player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        // Check link
        String discordId = discordManager.getDiscordId(targetUuid);
        if (discordId == null) {
            MessageUtils
                    .parseWithPapi(configManager.getMessage("discord-not-linked-other").replace("%player%", targetName),
                            player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        // Found!
        String discordTag = discordManager.getDiscordTag(discordId);

        MessageUtils.parseWithPapi(configManager.getMessage("discord-whois-found")
                .replace("%player%", targetName)
                .replace("%discord_tag%", discordTag), player, targetUuid)
                .thenAccept(player::sendMessage);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Permission check
        if (!(invocation.source() instanceof Player)) {
            return Collections.emptyList();
        }

        String perm = configManager.getConfigString("permissions.whois", "minewar.discord.whois");
        if (!invocation.source().hasPermission(perm)) {
            return Collections.emptyList();
        }

        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\\\s+");

        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return dataManager.getNameCache().keySet().stream()
                    .filter(n -> n.startsWith(prefix))
                    .limit(20)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String perm = configManager.getConfigString("permissions.whois", "minewar.discord.whois");
        return invocation.source().hasPermission(perm);
    }
}
