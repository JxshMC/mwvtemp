package com.example.seen.command;

import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.VanishManager;
import java.util.List;
import java.util.Collections;

public class VanishCommand implements RawCommand {

    private final ConfigManager configManager;
    private final VanishManager vanishManager;

    public VanishCommand(ProxyServer server, ConfigManager configManager, DataManager dataManager,
            VanishManager vanishManager) {
        this.configManager = configManager;
        this.vanishManager = vanishManager;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(configManager.getMessage("players-only")));
            return;
        }

        String perm = configManager.getConfigString("permissions.vanish", "minewarvelocity.vanish");
        if (!player.hasPermission(perm)) {
            String noPerm = configManager.getMessage("no-permission");
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(noPerm));
            return;
        }

        vanishManager.toggleVanish(player);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList(); // No args needed
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String perm = configManager.getConfigString("permissions.vanish", "minewarvelocity.vanish");
        return invocation.source().hasPermission(perm);
    }
}