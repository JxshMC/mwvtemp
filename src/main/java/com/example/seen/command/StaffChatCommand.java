package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.MessageUtils;
import com.example.seen.MinewarVelocity;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Collections;
import java.util.List;

public class StaffChatCommand implements RawCommand {
    private final MinewarVelocity plugin;
    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;

    public StaffChatCommand(MinewarVelocity plugin, ProxyServer server, DataManager dataManager,
            ConfigManager configManager) {
        this.plugin = plugin;
        this.server = server;
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("players-only")));
            return;
        }

        Player player = (Player) invocation.source();
        String usePerm = configManager.getConfigString("permissions.staffchat_use", "minewarvelocity.staffchat.use");
        String seePerm = configManager.getConfigString("permissions.staffchat_see", "minewarvelocity.staffchat.see");

        if (!player.hasPermission(usePerm)) {
            MessageUtils.parseWithPapi(configManager.getMessage("no-permission"), player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        String rawArgs = invocation.arguments();


        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        if (args.length == 0) {
            // Toggle mode
            boolean newState = !dataManager.isStaffChatToggled(player.getUniqueId());
            dataManager.setStaffChatToggled(player.getUniqueId(), newState);

            // Sync state to backend servers
            plugin.syncStaffChat(player, newState);

            String msgKey = newState ? "staff-chat-toggle-on" : "staff-chat-toggle-off";
            MessageUtils.parseWithPapi(configManager.getMessage(msgKey), player, null)
                    .thenAccept(player::sendMessage);
        } else {
            // Send one-off message
            String message = String.join(" ", args);
            sendStaffMessage(player, message, seePerm);
        }
    }

    private void sendStaffMessage(Player sender, String message, String seePerm) {
        net.luckperms.api.LuckPerms luckPerms = net.luckperms.api.LuckPermsProvider.get();
        luckPerms.getUserManager().loadUser(sender.getUniqueId()).thenAccept(user -> {
            String rank = (user != null) ? user.getCachedData().getMetaData().getPrefix() : "Player";
            if (rank == null)
                rank = "Player";

            String format = configManager.getMessage("staff-chat-format")
                    .replace("%rank%", rank)
                    .replace("%player%", sender.getUsername())
                    .replace("%message%", message);

            server.getAllPlayers().stream()
                    .filter(p -> p.hasPermission(seePerm))
                    .forEach(staff -> {
                        MessageUtils.parseWithPapi(format, sender, null)
                                .thenAccept(staff::sendMessage);
                    });
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList();
    }
}
