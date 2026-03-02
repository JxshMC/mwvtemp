package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.config.SmartConfig;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class ProxyRestartCancelCommand implements RawCommand {

    private final ProxyServer server;
    private final SmartConfig smartConfig;
    private final ConfigManager configManager;

    public ProxyRestartCancelCommand(ProxyServer server, SmartConfig smartConfig, ConfigManager configManager) {
        this.server = server;
        this.smartConfig = smartConfig;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        String perm = smartConfig.getConfig().getString("permissions.proxy_restart_cancel", "minewar.restart.cancel");
        if (!invocation.source().hasPermission(perm)) {
            invocation.source()
                    .sendMessage(MiniMessage.miniMessage().deserialize(configManager.getMessage("no-permission")));
            return;
        }

        if (ProxyRestartCommand.cancelRestart()) {
            server.getAllPlayers().forEach(p -> p
                    .sendMessage(MiniMessage.miniMessage().deserialize(configManager.getMessage("restart-cancelled"))));
        } else {
            invocation.source().sendMessage(
                    MiniMessage.miniMessage().deserialize(configManager.getMessage("restart-not-running")));
        }
    }
}
