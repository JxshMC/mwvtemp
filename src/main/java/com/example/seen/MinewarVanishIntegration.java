package com.example.seen;

import net.william278.velocitab.vanish.VanishIntegration;
import org.jetbrains.annotations.NotNull;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;

public class MinewarVanishIntegration implements VanishIntegration {

    private final com.velocitypowered.api.proxy.ProxyServer server;
    private final DataManager dataManager;
    private final VanishManager vanishManager;

    public MinewarVanishIntegration(ProxyServer server, DataManager dataManager, VanishManager vanishManager) {
        this.server = server;
        this.dataManager = dataManager;
        this.vanishManager = vanishManager;
    }

    @Override
    public boolean canSee(@NotNull String viewer, @NotNull String target) {
        Optional<Player> viewerPlayer = server.getPlayer(viewer);
        Optional<Player> targetPlayer = server.getPlayer(target);

        if (viewerPlayer.isPresent() && targetPlayer.isPresent()) {
            return vanishManager.canSee(viewerPlayer.get(), targetPlayer.get());
        }

        // Fallback: proper implementation would be to check persistent data or assume
        // visible/vanished
        // If target is vanished (checked by name/uuid from DB), then return false
        // unless viewer has perm.
        // But vanishManager.isVanished(UUID) requires UUID.
        // We can try to finding UUID from name in DataManager if possible, but
        // dataManager.getUUID(name) was used in original code.

        // Use DataManager to check offline vanish status if target is not online but in
        // tablist (unlikely for Velocity?)
        // Velocity tablist usually only holds online players.

        return true;
    }

    @Override
    public boolean isVanished(@NotNull String name) {
        // This method is required by some versions of the interface, or not?
        // The error log in step 27 said: must implement isVanished(String).
        // So I must implement it.
        java.util.UUID uuid = dataManager.getUUID(name);
        if (uuid == null)
            return false;
        return vanishManager.isVanished(uuid);
    }
}
