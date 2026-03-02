package com.example.seen.command;

import com.example.seen.DiscordManager;
import com.example.seen.ConfigManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class DiscordCommand implements RawCommand {

    private final DiscordManager discordManager;
    private final ConfigManager configManager;

    public DiscordCommand(DiscordManager discordManager, ConfigManager configManager) {
        this.discordManager = discordManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String rawArgs = invocation.arguments();

        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        if (args.length == 0) {
            MessageUtils
                    .parseWithPapi(configManager.getMessage("discord-cmd-usage"),
                            source instanceof Player ? (Player) source : null, null)
                    .thenAccept(source::sendMessage);
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("users")) {
            if (!source.hasPermission("minewar.discord.users")) {
                source.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<red>You do not have permission to use this command."));
                return;
            }
            source.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<yellow>Linked Discord Users:"));

            discordManager.getLinkedUsers().thenAccept(users -> {
                if (users.isEmpty()) {
                    source.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<gray>No linked users found."));
                    return;
                }
                users.forEach((uuid, discordId) -> {
                    String name = discordManager.getDataManager().getName(uuid);
                    String tag = discordManager.getDiscordTag(discordId);
                    source.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<gold>" + (name != null ? name : uuid) + " <gray>» <blue>" + tag));
                });
            });
            return;
        }

        if (!(source instanceof Player)) {
            source.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("players-only")));
            return;
        }

        Player player = (Player) source;

        if (sub.equals("link")) {
            if (discordManager.isLinked(player.getUniqueId())) {
                MessageUtils.parseWithPapi(configManager.getMessage("discord-already-linked"), player, null)
                        .thenAccept(player::sendMessage);
                return;
            }

            String token = discordManager.generateLinkCode(player.getUniqueId());

            String line1 = configManager.getMessage("discord-link-code").replace("%code%", token);
            MessageUtils.parseWithPapi(line1, player, null).thenAccept(player::sendMessage);

            String line2 = configManager.getMessage("discord-link-instruction").replace("%code%", token);
            MessageUtils.parseWithPapi(line2, player, null).thenAccept(player::sendMessage);

        } else if (sub.equals("unlink")) {
            if (!discordManager.isLinked(player.getUniqueId())) {
                MessageUtils.parseWithPapi(configManager.getMessage("discord-not-linked-game"), player, null)
                        .thenAccept(player::sendMessage);
                return;
            }

            discordManager.unlink(player.getUniqueId());
            MessageUtils.parseWithPapi(configManager.getMessage("discord-unlink-success-game"), player, null)
                    .thenAccept(player::sendMessage);

        } else {
            MessageUtils.parseWithPapi(configManager.getMessage("discord-cmd-usage"), player, null)
                    .thenAccept(player::sendMessage);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String rawArgs = invocation.arguments();

        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");
        if (args.length <= 1) {
            return List.of("link", "unlink", "users").stream()
                    .filter(s -> s.startsWith(args.length > 0 ? args[0].toLowerCase() : ""))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
