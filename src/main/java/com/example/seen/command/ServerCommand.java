package com.example.seen.command;

import com.example.seen.config.SmartConfig;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Optional;

public class ServerCommand implements RawCommand {

    private final ProxyServer server;
    private final SmartConfig smartConfig;

    public ServerCommand(ProxyServer server, SmartConfig smartConfig) {
        this.server = server;
        this.smartConfig = smartConfig;
    }

    @Override
    public void execute(Invocation invocation) {
        String perm = smartConfig.getConfig().getString("permissions.server_command", "minewar.server");
        if (!invocation.source().hasPermission(perm)) {
            String noPerm = smartConfig.getMessages().getString("no-permission", "<red>No permission.");
            invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(noPerm));
            return;
        }

        if (!(invocation.source() instanceof Player player)) {
            String msg = smartConfig.getMessages().getString("server.only-players",
                    "&cOnly players can use this command.");
            invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(msg));
            return;
        }

        String rawArgs = invocation.arguments();

        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        if (args.length == 0) {
            // List servers from config
            List<String> validServers = smartConfig.getConfig().getStringList("Servers");
            if (validServers == null || validServers.isEmpty()) {
                String msg = smartConfig.getMessages().getString("server.whitelist-empty",
                        "<red>No servers configured in whitelist.");
                player.sendMessage(MiniMessage.miniMessage().deserialize(msg));
                return;
            }

            String header = smartConfig.getMessages().getString("server.list-header", "<gold>Available Servers:");
            player.sendMessage(MiniMessage.miniMessage().deserialize(header));

            String itemFormat = smartConfig.getMessages().getString("server.list-item-format",
                    " <gray>- <click:run_command:'/server %server%'><hover:show_text:'<green>Click to connect'><yellow>%server%</yellow></hover></click>");

            for (String srvName : validServers) {
                // Check if server exists on proxy
                Optional<RegisteredServer> rs = server.getServer(srvName);
                if (rs.isPresent()) {
                    String item = itemFormat.replace("%server%", srvName);
                    player.sendMessage(MiniMessage.miniMessage().deserialize(item));
                }
            }
            return;
        }

        // Try to connect
        String targetName = args[0];
        Optional<RegisteredServer> target = server.getServer(targetName);

        if (target.isEmpty()) {
            String msg = smartConfig.getMessages().getString("server.not-found",
                    "<red>Server %server% does not exist.");
            msg = msg.replace("%server%", targetName);
            player.sendMessage(MiniMessage.miniMessage().deserialize(msg));
            return;
        }

        player.createConnectionRequest(target.get()).fireAndForget();
    }
}
