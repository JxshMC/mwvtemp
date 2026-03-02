package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.Collections;
import java.util.List;

public class SocialSpyCommand implements RawCommand {
    private final DataManager dataManager;
    private final ConfigManager configManager;

    public SocialSpyCommand(DataManager dataManager, ConfigManager configManager) {
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
        String perm = configManager.getConfigString("permissions.socialspy", "minewarvelocity.socialspy");

        if (!player.hasPermission(perm)) {
            MessageUtils.parseWithPapi(configManager.getMessage("no-permission"), player, null)
                    .thenAccept(player::sendMessage);
            return;
        }

        boolean newState = !dataManager.isSocialSpyEnabled(player.getUniqueId());
        dataManager.setSocialSpyEnabled(player.getUniqueId(), newState);
        dataManager.save();

        String msgKey = newState ? "social-spy-on" : "social-spy-off";
        String msg = configManager.getMessage(msgKey);

        MessageUtils.parseWithPapi(msg, player, null)
                .thenAccept(player::sendMessage);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList(); // No args needed
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String perm = configManager.getConfigString("permissions.socialspy", "minewarvelocity.socialspy");
        return invocation.source().hasPermission(perm);
    }
}
