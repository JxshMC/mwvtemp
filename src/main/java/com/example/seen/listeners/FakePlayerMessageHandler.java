package com.example.seen.listeners;

import com.example.seen.FakePlayerRegistry;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.util.GameProfile;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Handles incoming {@code minewar:fakeplayer} plugin messages from Paper
 * backends.
 *
 * <h3>Packet format (DataOutputStream UTF, in order):</h3>
 * <ol>
 * <li>action — CREATE | DELETE | UPDATE | CLEAR</li>
 * <li>name — NPC display name (empty string for CLEAR)</li>
 * <li>serverName — backend Velocity server key (must match velocity.toml)</li>
 * <li>rank — LuckPerms group name / rank prefix string</li>
 * </ol>
 *
 * <p>
 * On CREATE: the entry is added to {@link FakePlayerRegistry} (player-count
 * automatically rises because {@code VanishManager.getVisibleCount()} now reads
 * from the registry) and the NPC is injected into <em>every online
 * player's</em>
 * raw Velocity tab-list using a deterministic UUID derived from the NPC name.
 * </p>
 *
 * <p>
 * On DELETE / CLEAR: the entry/entries are removed from the registry and the
 * tab-list entries are removed from every online player.
 * </p>
 */
public class FakePlayerMessageHandler {

    public static final String CHANNEL = "minewar:fakeplayer";

    private final FakePlayerRegistry registry;
    private final ProxyServer server;
    private final com.example.seen.ConfigManager configManager;
    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public FakePlayerMessageHandler(FakePlayerRegistry registry, ProxyServer server,
            com.example.seen.ConfigManager configManager, Logger logger) {
        this.registry = registry;
        this.server = server;
        this.configManager = configManager;
        this.logger = logger;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UUID helper — deterministic v3 from NPC name so add/remove is stable
    // ─────────────────────────────────────────────────────────────────────────

    private static UUID npcUuid(String name) {
        return UUID.nameUUIDFromBytes(("FakePlayer:" + name.toLowerCase()).getBytes());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet handler
    // ─────────────────────────────────────────────────────────────────────────

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection))
            return;
        if (!event.getIdentifier().getId().equals(CHANNEL))
            return;

        // Mark handled — don't forward to other backends
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        try {
            byte[] data = event.getData();
            if (data == null)
                return;
            ByteArrayDataInput in = ByteStreams.newDataInput(data);
            String action = in.readUTF();
            String name = in.readUTF();
            String rank = in.readUTF();
            String serverName = in.readUTF();
            String skinName = in.readUTF();

            logger.info("[FakePlayer] Packet Received: {} | {} | {} | {} | {} (from {})",
                    action, name, rank, serverName, skinName, serverName);

            switch (action.toUpperCase()) {
                case "SYNC_UPDATE" -> handleSyncUpdate(name, rank, skinName, serverName);
                case "CREATE" -> handleCreate(name, serverName);
                case "DELETE" -> handleDelete(name);
                case "UPDATE" -> handleUpdate(name, serverName);
                case "CLEAR" -> handleClear(serverName);
                default -> logger.warn("[FakePlayer] Unknown action: '{}'", action);
            }

        } catch (Exception e) {
            logger.error("[FakePlayer] Failed to parse packet: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Action handlers
    // ─────────────────────────────────────────────────────────────────────────

    private void handleSyncUpdate(String name, String rank, String skinName, String serverName) {
        fetchSkinAsync(skinName).thenAccept(props -> {
            FakePlayerRegistry.FakePlayerEntry entry = fetchLuckPermsDataForRank(name, serverName, rank, props);
            registry.add(entry);

            syncNametagToPaper(entry, "NAMETAG_ACK");
            logger.info("[FakePlayer] Synced NPC '{}' with rank '{}' and skin '{}'", name, rank, skinName);
        });
    }

    private void handleCreate(String name, String serverName) {
        FakePlayerRegistry.FakePlayerEntry entry = fetchLuckPermsData(name, serverName);
        registry.add(entry);
        syncNametagToPaper(entry);
        logger.info("[FakePlayer] Registered NPC '{}' on '{}' (Weight: {})", name, serverName, entry.weight());
    }

    private void handleDelete(String name) {
        FakePlayerRegistry.FakePlayerEntry entry = registry.get(name);
        registry.remove(name);
        if (entry != null) {
            logger.info("[FakePlayer] Removed NPC '{}' from '{}'", name, entry.serverName());
        }
    }

    private void handleUpdate(String name, String serverName) {
        FakePlayerRegistry.FakePlayerEntry updated = fetchLuckPermsData(name, serverName);
        registry.add(updated);
        syncNametagToPaper(updated);
        logger.info("[FakePlayer] Updated NPC '{}' (Weight: {})", name, updated.weight());
    }

    private void handleClear(String serverName) {
        List<FakePlayerRegistry.FakePlayerEntry> cleared = registry.clearServer(serverName);
        logger.info("[FakePlayer] Cleared {} NPCs from server '{}'", cleared.size(), serverName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LuckPerms & Formatting Logic
    // ─────────────────────────────────────────────────────────────────────────

    private FakePlayerRegistry.FakePlayerEntry fetchLuckPermsDataForRank(String name, String serverName, String rank,
            List<GameProfile.Property> skin) {
        String group = rank;
        String prefix = "";
        int weight = 0;

        try {
            net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.group.Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj != null) {
                prefix = groupObj.getCachedData().getMetaData().getPrefix();
                if (prefix == null)
                    prefix = "";
                weight = groupObj.getWeight().orElse(0);
            }
        } catch (Exception e) {
            logger.warn("[FakePlayer] LuckPerms group lookup failed for '{}': {}", group, e.getMessage());
        }

        return new FakePlayerRegistry.FakePlayerEntry(name, serverName, rank, prefix, group, weight, skin);
    }

    private FakePlayerRegistry.FakePlayerEntry fetchLuckPermsData(String name, String serverName) {
        String group = "default";
        String prefix = "";
        int weight = 0;

        try {
            net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.User user = lp.getUserManager().getUser(name);
            if (user == null) {
                user = lp.getUserManager().loadUser(npcUuid(name), name).get();
            }

            if (user != null) {
                group = user.getPrimaryGroup();
                net.luckperms.api.cacheddata.CachedMetaData meta = user.getCachedData().getMetaData();
                prefix = meta.getPrefix() != null ? meta.getPrefix() : "";

                net.luckperms.api.model.group.Group groupObj = lp.getGroupManager().getGroup(group);
                if (groupObj != null) {
                    weight = groupObj.getWeight().orElse(0);
                }
            }
        } catch (Exception e) {
            logger.warn("[FakePlayer] LuckPerms lookup failed for '{}': {}", name, e.getMessage());
        }

        return new FakePlayerRegistry.FakePlayerEntry(name, serverName, group, prefix, group, weight,
                Collections.emptyList());
    }

    private CompletableFuture<List<GameProfile.Property>> fetchSkinAsync(String skinName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest uuidReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + skinName))
                        .GET().build();

                HttpResponse<String> uuidRes = httpClient.send(uuidReq, HttpResponse.BodyHandlers.ofString());
                if (uuidRes.statusCode() != 200)
                    return Collections.emptyList();

                JsonObject uuidJson = JsonParser.parseString(uuidRes.body()).getAsJsonObject();
                String mojangUuid = uuidJson.get("id").getAsString();

                HttpRequest profileReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + mojangUuid
                                + "?unsigned=false"))
                        .GET().build();

                HttpResponse<String> profileRes = httpClient.send(profileReq, HttpResponse.BodyHandlers.ofString());
                if (profileRes.statusCode() != 200)
                    return Collections.emptyList();

                JsonObject profileJson = JsonParser.parseString(profileRes.body()).getAsJsonObject();
                JsonArray properties = profileJson.getAsJsonArray("properties");

                List<GameProfile.Property> props = new ArrayList<>();
                for (int i = 0; i < properties.size(); i++) {
                    JsonObject prop = properties.get(i).getAsJsonObject();
                    props.add(new GameProfile.Property(
                            prop.get("name").getAsString(),
                            prop.get("value").getAsString(),
                            prop.get("signature").getAsString()));
                }
                return props;
            } catch (Exception e) {
                logger.warn("Failed to fetch skin for '{}': {}", skinName, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private void syncNametagToPaper(FakePlayerRegistry.FakePlayerEntry entry, String subChannel) {
        String display = buildDisplay(entry);
        String legacyName = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(MiniMessage.miniMessage().deserialize(display));

        server.getServer(entry.serverName()).ifPresent(s -> {
            try {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF(subChannel);
                out.writeUTF(entry.name());
                out.writeUTF(legacyName);
                s.sendPluginMessage(com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(CHANNEL),
                        out.toByteArray());
            } catch (Exception e) {
                logger.error("[FakePlayer] Failed to send {} for '{}'", subChannel, entry.name());
            }
        });
    }

    private void syncNametagToPaper(FakePlayerRegistry.FakePlayerEntry entry) {
        syncNametagToPaper(entry, "NAMETAG_UPDATE");
    }

    private String buildDisplay(FakePlayerRegistry.FakePlayerEntry entry) {
        String format = configManager.getVelocitabFormat();
        return format.replace("{rank}", entry.prefix() != null ? entry.prefix() : "")
                .replace("{username}", entry.name());
    }

    /** Re-calculates and re-applies formatting for all NPCs (called on reload). */
    public void refreshAll() {
        for (FakePlayerRegistry.FakePlayerEntry entry : registry.getAll()) {
            FakePlayerRegistry.FakePlayerEntry updated = fetchLuckPermsData(entry.name(), entry.serverName());
            registry.add(updated); // Update in registry

            // Sync nametag
            syncNametagToPaper(updated);
        }
    }
}
