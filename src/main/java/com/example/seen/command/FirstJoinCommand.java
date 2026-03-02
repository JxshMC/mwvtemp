package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.MessageUtils;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /firstjoin &lt;player&gt;
 *
 * <p>
 * Resolution order for the target player:
 * <ol>
 * <li>In-memory name cache (populated on login)</li>
 * <li>Online player list (covers currently connected players)</li>
 * <li>{@link DataManager#getUUID(String)} — queries the database via
 * {@code LOWER(exact_name) = ?} as the final fallback</li>
 * </ol>
 *
 * <p>
 * Once the UUID is resolved, {@link DataManager#getProfileAsync(UUID)} is
 * called to fetch {@code exact_name}, {@code first_join}, and
 * {@code join_index}
 * from the database in a single query. The {@code exact_name} field is used for
 * all output so casing is always correct (e.g. "Jxsh" not "jxsh"). If no row
 * exists in the database for the resolved UUID, the "never-joined" message is
 * sent.
 */
public class FirstJoinCommand implements RawCommand {

    private final ProxyServer server;
    private final DataManager dataManager;
    private final ConfigManager configManager;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy");

    public FirstJoinCommand(ProxyServer server, DataManager dataManager, ConfigManager configManager) {
        this.server = server;
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        // ── Permission check ──────────────────────────────────────────────────
        String perm = configManager.getConfigString("permissions.firstjoin", "minewarvelocity.firstjoin");
        if (!source.hasPermission(perm)) {
            sendRaw(source, configManager.getMessage("error.no-permission"));
            return;
        }

        if (args.length == 0) {
            sendRaw(source, configManager.getMessage("usage-firstjoin"));
            return;
        }

        final String targetName = args[0];

        // ── UUID resolution ───────────────────────────────────────────────────
        // 1. In-memory cache (populated on login / cacheName calls)
        UUID uuid = dataManager.getUUID(targetName);

        // 2. Online player lookup — covers players in the current session
        if (uuid == null) {
            for (Player onlinePlayer : server.getAllPlayers()) {
                if (onlinePlayer.getUsername().equalsIgnoreCase(targetName)) {
                    dataManager.cacheName(onlinePlayer.getUsername(), onlinePlayer.getUniqueId());
                    uuid = onlinePlayer.getUniqueId();
                    break;
                }
            }
        }

        // 3. DB lookup: DataManager.getUUID() queries LOWER(exact_name) = ?
        // (already done above as getUUID hits the DB on cache miss)

        if (uuid == null) {
            // Truly unknown — not in cache or DB
            sendRaw(source, configManager.getMessage("player-not-found-seen")
                    .replace("%player%", targetName));
            return;
        }

        final UUID finalUuid = uuid;

        // ── Async DB profile fetch ────────────────────────────────────────────
        // getProfileAsync fetches exact_name, first_join, and join_index in one
        // query and is always authoritative (never relies on potentially stale cache).
        dataManager.getProfileAsync(finalUuid).thenAccept(profile -> {

            if (profile == null) {
                // UUID resolved but no row in seen_data — treat as never joined
                sendRaw(source, configManager.getMessage("never-joined")
                        .replace("%player%", targetName));
                return;
            }

            // Use DB-stored exact_name for correct casing (e.g. "Jxsh" not "jxsh")
            String exactName = profile.exactName();
            long firstJoinTime = profile.firstJoin();
            int joinIndex = profile.joinIndex();

            // If first_join is 0 (old corrupted row), show "never joined" for that field
            // but still display the player name so it doesn't silently return nothing.
            if (firstJoinTime <= 0) {
                sendRaw(source, configManager.getMessage("never-joined")
                        .replace("%player%", exactName));
                return;
            }

            // ── Format the date/duration strings ──────────────────────────────
            String formattedDate = DATE_FORMAT.format(new Date(firstJoinTime));
            String duration = buildDuration(firstJoinTime);

            // Substitute command-specific placeholders then pass through MessageUtils
            // for any remaining MiniMessage/PlaceholderAPI tags
            String rawMsg = configManager.getMessage("first-join-message")
                    .replace("%player%", exactName)
                    .replace("%date%", formattedDate)
                    .replace("%duration%", duration)
                    .replace("%join_index%", joinIndex > 0 ? String.valueOf(joinIndex) : "?");

            String resolved = MessageUtils.applyPlaceholders(rawMsg, exactName, finalUuid, false);
            Component component = MessageUtils.renderMessage(resolved);
            source.sendMessage(component);
        });
    }

    /**
     * Builds a human-readable "Years Months Days" duration string from an
     * epoch-millis timestamp to now, using label strings from messages.yml.
     */
    private String buildDuration(long epochMillis) {
        LocalDate joinDate = Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        LocalDate now = LocalDate.now();
        Period period = Period.between(joinDate, now);

        StringBuilder sb = new StringBuilder();
        if (period.getYears() > 0) {
            sb.append(period.getYears())
                    .append(configManager.getMessage("format-years-full"))
                    .append(" ");
        }
        if (period.getMonths() > 0) {
            sb.append(period.getMonths())
                    .append(configManager.getMessage("format-months-full"))
                    .append(" ");
        }
        sb.append(period.getDays())
                .append(configManager.getMessage("format-days-full"));

        return sb.toString().trim();
    }

    /**
     * Sends a pre-rendered MiniMessage string to the source (handles both
     * players and console).
     */
    private void sendRaw(CommandSource source, String miniMessage) {
        source.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return Collections.emptyList();
        }
        String perm = configManager.getConfigString("permissions.firstjoin", "minewarvelocity.firstjoin");
        if (!invocation.source().hasPermission(perm)) {
            return Collections.emptyList();
        }

        String[] args = invocation.arguments().trim().isEmpty()
                ? new String[0]
                : invocation.arguments().split("\\s+");

        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String perm = configManager.getConfigString("permissions.firstjoin", "minewarvelocity.firstjoin");
        return invocation.source().hasPermission(perm);
    }
}
