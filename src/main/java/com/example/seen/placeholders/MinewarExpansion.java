package com.example.seen.placeholders;

import com.example.seen.ConfigManager;
import com.example.seen.MessageUtils;
import com.example.seen.VanishManager;
import com.velocitypowered.api.proxy.Player;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.luckperms.api.model.user.User;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * MiniPlaceholders expansion for the {@code minewar_} prefix.
 *
 * <p>
 * Registered placeholders:
 * <ul>
 * <li>{@code <minewar_total>} — visible player count (excludes vanished)</li>
 * <li>{@code <minewar_server_SERVER>} — visible count on a specific server</li>
 * <li>{@code <minewar_owner_color>} — the owner hex color tag from config
 * (global)</li>
 * <li>{@code <minewar_prefix_other>} — LuckPerms prefix for the audience
 * player</li>
 * <li>{@code <minewar_suffix_other>} — LuckPerms suffix for the audience
 * player</li>
 * <li>{@code <minewar_join_index>} / {@code <minewar_joinindex>} — join index
 * for
 * the audience player (the player whose row is being rendered in tablist).
 * Falls back to DB query if not in cache. Never returns "Unknown".</li>
 * <li>{@code <minewar_joinindex_player_Name>} — join index for a specific
 * player
 * by name (global, for use in chat/commands)</li>
 * </ul>
 */
public class MinewarExpansion {

    private final VanishManager vanishManager;
    private final ConfigManager configManager;
    private final com.example.seen.DataManager dataManager;

    public MinewarExpansion(VanishManager vanishManager, ConfigManager configManager,
            com.example.seen.DataManager dataManager) {
        this.vanishManager = vanishManager;
        this.configManager = configManager;
        this.dataManager = dataManager;
    }

    public void register() {
        Expansion.Builder builder = Expansion.builder("minewar");

        // ── Global: total visible player count ────────────────────────────────
        builder.globalPlaceholder("total", (queue, ctx) -> {
            int count = vanishManager.getVisibleCount(Optional.empty());
            return Tag.selfClosingInserting(Component.text(count));
        });

        // ── Global: per-server visible player count ───────────────────────────
        builder.globalPlaceholder("server", (queue, ctx) -> {
            if (queue.hasNext()) {
                String serverName = queue.pop().value();
                return Tag.selfClosingInserting(
                        Component.text(vanishManager.getVisibleCount(Optional.of(serverName))));
            }
            return Tag.selfClosingInserting(Component.text("0"));
        });

        // ── Global: owner color tag from config ───────────────────────────────
        builder.globalPlaceholder("owner_color", (queue, ctx) -> {
            String ownerColor = configManager.getOwnerColor();
            if (ownerColor == null || ownerColor.isEmpty())
                ownerColor = "<#0adef7>";
            Component coloredEmpty = MiniMessage.miniMessage().deserialize(ownerColor);
            return Tag.selfClosingInserting(coloredEmpty);
        });

        // ── Audience-relative: LuckPerms prefix for the viewing player ────────
        builder.audiencePlaceholder("prefix_other", (audience, queue, ctx) -> {
            if (!(audience instanceof Player player)) {
                return Tag.selfClosingInserting(Component.empty());
            }
            return Tag.selfClosingInserting(resolveLpTag(player.getUniqueId(), true));
        });

        // ── Audience-relative: LuckPerms suffix for the viewing player ────────
        builder.audiencePlaceholder("suffix_other", (audience, queue, ctx) -> {
            if (!(audience instanceof Player player)) {
                return Tag.selfClosingInserting(Component.empty());
            }
            return Tag.selfClosingInserting(resolveLpTag(player.getUniqueId(), false));
        });

        // ── Join Index: audience player (the player being rendered, e.g. in tablist)
        // In Velocitab/tablist contexts the audience IS the displayed player, so
        // this resolves the correct index. DB fallback ensures it never returns
        // "Unknown" even for brand-new players whose cache entry was just written.
        builder.audiencePlaceholder("join_index", (audience, queue, ctx) -> {
            if (!(audience instanceof Player player)) {
                return Tag.selfClosingInserting(Component.text("0"));
            }
            int index = resolveJoinIndex(player.getUniqueId());
            return Tag.selfClosingInserting(Component.text(Math.max(0, index)));
        });

        builder.audiencePlaceholder("joinindex", (audience, queue, ctx) -> {
            if (!(audience instanceof Player player)) {
                return Tag.selfClosingInserting(Component.text("0"));
            }
            int index = resolveJoinIndex(player.getUniqueId());
            return Tag.selfClosingInserting(Component.text(Math.max(0, index)));
        });

        // ── Join Index: specific player by name (global) ──────────────────────
        builder.globalPlaceholder("joinindex_player", (queue, ctx) -> {
            if (queue.hasNext()) {
                String targetName = queue.pop().value();
                UUID uuid = dataManager.getUUID(targetName);
                if (uuid != null) {
                    int index = resolveJoinIndex(uuid);
                    return Tag.selfClosingInserting(Component.text(Math.max(0, index)));
                }
            }
            return Tag.selfClosingInserting(Component.text("0"));
        });

        builder.globalPlaceholder("join_index_player", (queue, ctx) -> {
            if (queue.hasNext()) {
                String targetName = queue.pop().value();
                UUID uuid = dataManager.getUUID(targetName);
                if (uuid != null) {
                    int index = resolveJoinIndex(uuid);
                    return Tag.selfClosingInserting(Component.text(Math.max(0, index)));
                }
            }
            return Tag.selfClosingInserting(Component.text("0"));
        });

        builder.build().register();
    }

    /**
     * Resolves the join index for a UUID. Tries the in-memory cache first;
     * if the value is missing or zero, falls back to a synchronous DB query via
     * {@link com.example.seen.DataManager#getJoinIndex(UUID)} which internally
     * calls {@code loadJoinIndex()} when the cache misses.
     *
     * <p>
     * Never returns "Unknown" — returns 0 as a floor instead.
     */
    private int resolveJoinIndex(UUID uuid) {
        return dataManager.getJoinIndex(uuid); // already has DB fallback built in
    }

    /**
     * Synchronously tries to resolve the LP prefix or suffix for {@code uuid}.
     * If the user is already in LP's cache this is effectively instant.
     * Falls back to {@link Component#empty()} if not available.
     */
    private Component resolveLpTag(UUID uuid, boolean prefix) {
        try {
            CompletableFuture<User> future = MessageUtils.loadUser(uuid);
            User user = future.getNow(null);
            if (user == null)
                return Component.empty();
            String meta = prefix
                    ? user.getCachedData().getMetaData().getPrefix()
                    : user.getCachedData().getMetaData().getSuffix();
            if (meta == null || meta.isEmpty())
                return Component.empty();
            return MiniMessage.miniMessage().deserialize(meta);
        } catch (Exception e) {
            return Component.empty();
        }
    }
}
