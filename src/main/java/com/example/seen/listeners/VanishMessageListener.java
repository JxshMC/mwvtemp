package com.example.seen.listeners;

import com.example.seen.DataManager;
import com.example.seen.MinewarVelocity;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

import java.util.UUID;

public class VanishMessageListener {

    private final DataManager dataManager;

    public VanishMessageListener(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        // Ensure the identifier matches our channel
        if (!event.getIdentifier().equals(MinewarVelocity.VANISH_CHANNEL)) {
            return;
        }

        // We only care about messages FROM backend servers TO the proxy
        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String subChannel = in.readUTF();

        if (subChannel.equals("VanishUpdate")) {
            try {
                String uuidString = in.readUTF();
                boolean vanished = in.readBoolean();
                UUID uuid = UUID.fromString(uuidString);

                // Update local cache
                dataManager.setVanished(uuid, vanished);

                // Note: ProxyPingListener automatically reads from DataManager,
                // so the next ping will be correct.
                // We don't need to manually trigger an event unless we want to force push an
                // update,
                // but MiniMOTD usually polls on ping.

                System.out.println("[MinewarVelocity] [Bridge] Received VanishUpdate for " + uuid + ": " + vanished);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
