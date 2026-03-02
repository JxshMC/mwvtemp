package com.example.seen.listeners;

import com.example.seen.VanishManager;
import com.example.seen.config.SmartConfig;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Optional;

public class ServerListPingListener {

    private final VanishManager vanishManager;
    private final SmartConfig smartConfig;
    private static final int MAX_WIDTH = 45; // Approx chars for standard MOTD width
    private static final double BOLD_MULTIPLIER = 1.15;

    public ServerListPingListener(VanishManager vanishManager, SmartConfig smartConfig) {
        this.vanishManager = vanishManager;
        this.smartConfig = smartConfig;
    }

    @Subscribe(order = PostOrder.LAST) // Run AFTER MiniMOTD or other plugins
    public void onProxyPing(ProxyPingEvent event) {
        ServerPing ping = event.getPing();
        int onlineCount = vanishManager.getVisibleCount(Optional.empty());

        ServerPing.Builder builder = ping.asBuilder();
        builder.onlinePlayers(onlineCount);

        // Ensure vanished players are not in the sample if it exists
        if (onlineCount == 0) {
            builder.samplePlayers(new ServerPing.SamplePlayer[0]);
        } else {
            ping.getPlayers().ifPresent(players -> {
                if (!players.getSample().isEmpty()) {
                    builder.samplePlayers(players.getSample().stream()
                            .filter(p -> !vanishManager.isVanished(p.getId()))
                            .toArray(ServerPing.SamplePlayer[]::new));
                }
            });
        }

        // MOTD Centering Logic
        if (smartConfig.getConfig().getBoolean("modules.motd-centering.enabled", true)) {
            Component description = ping.getDescriptionComponent();
            if (description != null) {
                // First Serialize to string to check for placeholders
                String rawMessage = MiniMessage.miniMessage().serialize(description);
                // Also check if it's legacy
                String legacyMessage = LegacyComponentSerializer.legacyAmpersand().serialize(description);

                boolean hasCentre = rawMessage.contains("<centre>") || rawMessage.contains("<center>")
                        || legacyMessage.contains("<centre>") || legacyMessage.contains("<center>");

                if (hasCentre) {
                    // Remove the centering tags
                    String cleanMessage = rawMessage.replace("<centre>", "").replace("<center>", "");
                    // Also clean from legacy just in case
                    cleanMessage = cleanMessage.replace("<centre>", "").replace("<center>", "");

                    // Deserialize to get the actual component without center tag
                    Component cleanComponent = MiniMessage.miniMessage().deserialize(cleanMessage);

                    // Calculate Width based on PlaneText or Legacy (Legacy handles bold better for
                    // estimation)
                    int width = calculateWidth(cleanComponent);
                    int padding = Math.max(0, (MAX_WIDTH - width) / 2);

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < padding; i++) {
                        sb.append(" ");
                    }

                    // Prepend padding
                    Component padded = Component.text(sb.toString()).append(cleanComponent);
                    builder.description(padded);
                } else {
                    // Even if no center tag, ensure deserialization happens if it looks like
                    // MiniMessage but wasn't parsed?
                    // MiniMOTD usually handles this.
                    // But user said: "The MOTD is showing literal <#CCFFFF>... because the string
                    // isn't being deserialized."
                    // So we should try to force deserialize if it looks like MM
                    if (rawMessage.contains("<") && rawMessage.contains(">")) {
                        // Try to deserialize again? Or is it that it CAME as literal string component?
                        // If description component matches raw text exact, it might be a literal text
                        // component.
                        String plainCheck = PlainTextComponentSerializer.plainText().serialize(description);
                        if (plainCheck.equals(rawMessage) && (plainCheck.contains("<") || plainCheck.contains("&"))) {
                            // It's likely a litteral string that needs parsing
                            builder.description(MiniMessage.miniMessage().deserialize(plainCheck));
                        }
                    }
                }
            }
        }

        event.setPing(builder.build());
    }

    private int calculateWidth(Component component) {
        // Convert to Legacy to handle bold and length easily
        String legacy = LegacyComponentSerializer.legacyAmpersand().serialize(component);
        int length = 0;
        boolean isBold = false;

        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '&' && i + 1 < legacy.length()) {
                char code = legacy.charAt(i + 1);
                if (code == 'l' || code == 'L') {
                    isBold = true;
                } else if (code == 'r' || code == 'R') {
                    isBold = false;
                } else if ("0123456789abcdefABCDEF".indexOf(code) != -1) {
                    isBold = false;
                }
                i++; // Skip code
            } else {
                length += (isBold ? BOLD_MULTIPLIER : 1);
            }
        }
        return length;
    }
}
