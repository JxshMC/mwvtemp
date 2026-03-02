package com.example.seen.listeners;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.example.seen.VanishManager;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import java.util.Optional;

/**
 * Handles incoming plugin messages on minewar:sync and bungeecord:main.
 *
 * minewar:sync sub-channels handled here:
 * TabCompleteIgnoreMe — Paper server opts out of Velocity-side tab filtering.
 * TabCompleteResume — Paper server opts back in (e.g. after reload).
 */
public class PluginMessageBridge {

    private final VanishManager vanishManager;
    private final CommandFilterListener commandFilterListener;

    public PluginMessageBridge(VanishManager vanishManager, CommandFilterListener commandFilterListener) {
        this.vanishManager = vanishManager;
        this.commandFilterListener = commandFilterListener;
    }

    @Subscribe(order = PostOrder.LATE)
    public void onPluginMessage(PluginMessageEvent event) {
        String tag = event.getIdentifier().getId();

        // ── minewar:sync ──────────────────────────────────────────────────────
        if (tag.equals("minewar:sync")) {
            if (!(event.getSource() instanceof ServerConnection))
                return;

            ServerConnection connection = (ServerConnection) event.getSource();
            String serverName = connection.getServerInfo().getName();
            byte[] data = event.getData();
            if (data == null)
                return;

            try {
                ByteArrayDataInput in = ByteStreams.newDataInput(data);
                String subChannel = in.readUTF();

                if (subChannel.equals("TabCompleteIgnoreMe")) {
                    commandFilterListener.markServerIgnored(serverName);
                    event.setResult(PluginMessageEvent.ForwardResult.handled());
                } else if (subChannel.equals("TabCompleteResume")) {
                    commandFilterListener.markServerActive(serverName);
                    event.setResult(PluginMessageEvent.ForwardResult.handled());
                }
                // Other minewar:sync sub-channels (WhitelistSync, BulkCount, etc.)
                // are forwarded to the backend — do not mark as handled here.
            } catch (Exception ignored) {
            }
            return;
        }

        // ── bungeecord:main (BungeeCord compat — PlayerCount for PAPI) ────────
        if (!tag.equals("bungeecord:main") && !tag.equals("BungeeCord"))
            return;

        if (!(event.getSource() instanceof ServerConnection))
            return;

        ServerConnection connection = (ServerConnection) event.getSource();
        byte[] data = event.getData();
        if (data == null)
            return;

        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(data);
            String subChannel = in.readUTF();

            if (subChannel.equals("PlayerCount")) {
                String serverName = in.readUTF();
                int count;
                if (serverName.equalsIgnoreCase("ALL")) {
                    count = vanishManager.getVisibleCount(Optional.empty());
                } else {
                    count = vanishManager.getVisibleCount(Optional.of(serverName));
                }
                if (count < 0)
                    count = 0;

                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("PlayerCount");
                out.writeUTF(serverName);
                out.writeInt(count);

                connection.sendPluginMessage(event.getIdentifier(), out.toByteArray());
                event.setResult(PluginMessageEvent.ForwardResult.handled());
            }
        } catch (Exception ignored) {
        }
    }
}
