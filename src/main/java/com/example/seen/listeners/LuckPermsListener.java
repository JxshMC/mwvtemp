package com.example.seen.listeners;

import com.example.seen.DiscordManager;
import com.example.seen.config.SmartConfig;
import com.example.seen.ConfigManager;
import com.velocitypowered.api.proxy.ProxyServer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;

import java.util.UUID;

public class LuckPermsListener {

    private final DiscordManager discordManager;
    private final ConfigManager configManager;

    public LuckPermsListener(DiscordManager discordManager, ProxyServer server, ConfigManager configManager) {
        this.discordManager = discordManager;
        this.configManager = configManager;
    }

    public void register() {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            EventBus eventBus = luckPerms.getEventBus();
            eventBus.subscribe(UserDataRecalculateEvent.class, this::onUserRecalculate);
        } catch (NoClassDefFoundError | IllegalStateException e) {
            if (configManager.isDebug())
                System.err.println("[MinewarVelocity] LuckPerms not found, role sync disabled.");
        }
    }

    private void onUserRecalculate(UserDataRecalculateEvent event) {
        User user = event.getUser();
        UUID uuid = user.getUniqueId();

        if (discordManager.isLinked(uuid)) {
            // Retrieve Discord ID and primary group
            String discordId = discordManager.getDiscordId(uuid);
            String primaryGroup = user.getPrimaryGroup();

            if (discordId != null && primaryGroup != null) {
                discordManager.updateUserRoles(discordId, primaryGroup);
            }
        }
    }
}
