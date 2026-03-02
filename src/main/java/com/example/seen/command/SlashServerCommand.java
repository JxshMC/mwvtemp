package com.example.seen.command;

import com.example.seen.config.SmartConfig;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Optional;

public class SlashServerCommand implements RawCommand {

    private final ProxyServer server;
    private final String targetServerName;
    private final SmartConfig smartConfig;

    public SlashServerCommand(ProxyServer server, String targetServerName, SmartConfig smartConfig) {
        this.server = server;
        this.targetServerName = targetServerName;
        this.smartConfig = smartConfig;
    }

    @Override
    public void execute(Invocation invocation) {
        String slashPerm = smartConfig.getConfig().getString("permissions.slash_server", "minewar.slashserver");
        if (!invocation.source().hasPermission(slashPerm)) {
            String noPerm = smartConfig.getMessages().getString("no-permission", "<red>No permission.");
            invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(noPerm));
            return;
        }

        if (!(invocation.source() instanceof Player player)) {
            String msg = smartConfig.getMessages().getString("server.only-players",
                    "<red>Only players can use this command.");
            invocation.source()
                    .sendMessage(MiniMessage.miniMessage().deserialize(msg));
            return;
        }

        Optional<RegisteredServer> target = server.getServer(targetServerName);
        if (target.isEmpty()) {
            String msg = smartConfig.getMessages().getString("server.target-not-found",
                    "<red>Target server '%server%' not found on proxy!");
            msg = msg.replace("%server%", targetServerName);
            player.sendMessage(MiniMessage.miniMessage().deserialize(msg));
            return;
        }

        player.createConnectionRequest(target.get()).fireAndForget();
    }
}
