package com.example.seen.command;

import com.example.seen.DataManager;
import com.example.seen.VanishManager;
import com.example.seen.config.SmartConfig;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import java.util.List;
import java.util.stream.Collectors;

public class ListCommand implements RawCommand {

    private final ProxyServer server;
    private final SmartConfig smartConfig;
    private final DataManager dataManager;
    private final VanishManager vanishManager;

    public ListCommand(ProxyServer server, SmartConfig smartConfig, DataManager dataManager,
            VanishManager vanishManager) {
        this.server = server;
        this.smartConfig = smartConfig;
        this.dataManager = dataManager;
        this.vanishManager = vanishManager;
    }

    @Override
    public void execute(Invocation invocation) {
        String listPerm = smartConfig.getConfig().getString("permissions.list", "minewar.list");
        if (!invocation.source().hasPermission(listPerm)) {
            String noPerm = smartConfig.getMessages().getString("no-permission-list",
                    "<red>You do not have permission.");
            invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(noPerm));
            return;
        }

        List<String> messages = smartConfig.getMessages().getStringList("list-messages");
        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<Player> playersForViewer = server.getAllPlayers().stream()
                .filter(p -> {
                    if (invocation.source() instanceof Player viewer) {
                        return vanishManager.canSee(viewer, p);
                    }
                    // Console sees everyone
                    return true;
                })
                .collect(Collectors.toList());

        int onlineCount = playersForViewer.size();
        int maxPlayers = server.getConfiguration().getShowMaxPlayers();

        LuckPerms luckPerms = LuckPermsProvider.get();

        String playerFormat = smartConfig.getMessages().getString("list-player-format", "%suffix_other%%player_name%");
        String staffFormat = smartConfig.getMessages().getString("list-staff-format", "%suffix_other%%player_name%");
        String vanishPrefix = smartConfig.getMessages().getString("list-vanish-prefix", "<gray>[INVIS] ");

        String staffPerm = smartConfig.getConfig().getString("permissions.list_staff", "minewar.list.staff");

        String staffList = playersForViewer.stream()
                .filter(p -> p.hasPermission(staffPerm))
                .map(p -> {
                    String prefix = dataManager.isVanished(p.getUniqueId()) ? vanishPrefix : "";
                    return prefix + formatPlayer(p, luckPerms, staffFormat);
                })
                .collect(Collectors.joining(", "));

        String playerList = playersForViewer.stream()
                .filter(p -> !p.hasPermission(staffPerm))
                .map(p -> {
                    String prefix = dataManager.isVanished(p.getUniqueId()) ? vanishPrefix : "";
                    return prefix + formatPlayer(p, luckPerms, playerFormat);
                })
                .collect(Collectors.joining(", "));

        if (staffList.isEmpty())
            staffList = "None";
        if (playerList.isEmpty())
            playerList = "None";

        for (String line : messages) {
            if (line == null || line.trim().isEmpty()) {
                if (line != null)
                    invocation.source().sendMessage(Component.empty());
                continue;
            }

            String formatted = line
                    .replace("%online%", String.valueOf(onlineCount))
                    .replace("%max%", String.valueOf(maxPlayers))
                    .replace("%staff%", staffList)
                    .replace("%players%", playerList);

            invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(formatted));

        }
    }

    private String formatPlayer(Player player, LuckPerms lp, String format) {
        User user = lp.getUserManager().getUser(player.getUniqueId());
        return com.example.seen.MessageUtils.replaceLuckPermsPlaceholders(format, user, user)
                .replace("%player_name%", player.getUsername());
    }
}
