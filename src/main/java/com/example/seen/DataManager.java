package com.example.seen;

import com.example.seen.database.DatabaseManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;

@Singleton
public class DataManager {
    private final DatabaseManager databaseManager;
    private final com.example.seen.config.SmartConfig smartConfig;
    private final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> exactNames = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> msgToggles = new ConcurrentHashMap<>();
    private final Map<UUID, Long> firstJoins = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignoreLists = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> socialSpyStatus = new ConcurrentHashMap<>();
    private final Set<UUID> staffChatToggled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> autoVanishPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> joinIndices = new ConcurrentHashMap<>();
    private final Map<UUID, String> discordLinks = new ConcurrentHashMap<>();
    private final Map<UUID, String> prefixes = new ConcurrentHashMap<>();
    private final Map<UUID, String> groupNames = new ConcurrentHashMap<>();
    private final Map<UUID, String> suffixes = new ConcurrentHashMap<>();

    @Inject
    public DataManager(DatabaseManager databaseManager, com.example.seen.config.SmartConfig smartConfig) {
        this.databaseManager = databaseManager;
        this.smartConfig = smartConfig;
    }

    public void load() {
        // Loads are on-demand via SQL queries. No bulk load required.
    }

    public void save() {
        // SQL updates happen in setters. No bulk save needed.
    }

    // ── Last Seen ─────────────────────────────────────────────────────────────

    public void setLastSeen(UUID uuid, long timestamp) {
        lastSeen.put(uuid, timestamp);
        updateData(uuid);
    }

    public Long getLastSeen(UUID uuid) {
        Long val = lastSeen.get(uuid);
        if (val == null) {
            loadData(uuid);
            return lastSeen.get(uuid);
        }
        return val;
    }

    // ── Name Cache ────────────────────────────────────────────────────────────

    /**
     * Updates the in-memory name caches only. Does NOT write to the database.
     * Database writes happen via {@link #createProfileIfMissing} on first join,
     * and via {@link #updateData} for subsequent updates.
     */
    public void cacheName(String name, UUID uuid) {
        nameCache.put(name.toLowerCase(), uuid);
        exactNames.put(uuid, name);
    }

    // ── Existence & Record Checks ─────────────────────────────────────────────

    /**
     * Returns {@code true} if a {@code seen_data} row already exists for this UUID.
     */
    public boolean hasRecord(UUID uuid) {
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM seen_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Async variant of {@link #hasRecord(UUID)}.
     */
    public CompletableFuture<Boolean> hasRecordAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> hasRecord(uuid));
    }

    // ── Profile Creation (First Join Only) ────────────────────────────────────

    /**
     * The single authoritative method for recording a player's first login.
     *
     * <ul>
     * <li>MariaDB: {@code INSERT ... ON DUPLICATE KEY UPDATE exact_name} only —
     * {@code first_join} and {@code join_index} are never touched on conflict.
     * </li>
     * <li>H2: SELECT-before-INSERT guard for the same immutability guarantee.</li>
     * </ul>
     */
    /**
     * Records a new player's first login. The INSERT is executed asynchronously
     * on a shared pool thread so Velocity's event thread is never blocked.
     *
     * <p>
     * The returned {@link CompletableFuture} resolves to the DB-assigned
     * {@code join_index} after the INSERT (and generated-key read) completes.
     * If the index cannot be determined within 2 seconds, the fallback path
     * queries {@code MAX(join_index)} so the welcome broadcast always has
     * a number to display.
     *
     * <p>
     * <strong>Immutability contract:</strong> {@code first_join} and
     * {@code join_index} are in the INSERT VALUES list ONLY — they are NOT in
     * the {@code ON DUPLICATE KEY UPDATE} clause and are therefore never
     * touched after the initial row creation.
     */
    public CompletableFuture<Integer> createProfileIfMissing(UUID uuid, String name, long firstJoinTime) {
        // Populate caches immediately so other code can read the name synchronously
        nameCache.put(name.toLowerCase(), uuid);
        exactNames.put(uuid, name);
        firstJoins.put(uuid, firstJoinTime);

        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection()) {
                if (databaseManager.isMariaDB()) {
                    // first_join and join_index are intentionally absent from UPDATE clause.
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO seen_data (uuid, exact_name, first_join, prefix, group_name, suffix) "
                                    + "VALUES (?, ?, ?, ?, ?, ?) "
                                    + "ON DUPLICATE KEY UPDATE exact_name = VALUES(exact_name)",
                            java.sql.Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, name);
                        ps.setLong(3, firstJoinTime);
                        ps.setString(4, "");
                        ps.setString(5, "default");
                        ps.setString(6, "");
                        ps.executeUpdate();

                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (rs.next()) {
                                int idx = rs.getInt(1);
                                joinIndices.put(uuid, idx);
                                return idx;
                            }
                        }
                    }
                } else {
                    // H2: explicit check to protect immutable fields
                    try (PreparedStatement check = conn.prepareStatement(
                            "SELECT uuid FROM seen_data WHERE uuid = ?")) {
                        check.setString(1, uuid.toString());
                        if (!check.executeQuery().next()) {
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO seen_data (uuid, exact_name, first_join, prefix, group_name, suffix) "
                                            + "VALUES (?, ?, ?, ?, ?, ?)",
                                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                                ps.setString(1, uuid.toString());
                                ps.setString(2, name);
                                ps.setLong(3, firstJoinTime);
                                ps.setString(4, "");
                                ps.setString(5, "default");
                                ps.setString(6, "");
                                ps.executeUpdate();

                                try (ResultSet rs = ps.getGeneratedKeys()) {
                                    if (rs.next()) {
                                        int idx = rs.getInt(1);
                                        joinIndices.put(uuid, idx);
                                        return idx;
                                    }
                                }
                            }
                        } else {
                            // Row exists — update only name
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE seen_data SET exact_name = ? WHERE uuid = ?")) {
                                up.setString(1, name);
                                up.setString(2, uuid.toString());
                                up.executeUpdate();
                            }
                        }
                    }
                }

                // Generated key not returned (ON DUPLICATE KEY hit) — read from DB
                loadJoinIndex(uuid);
                return joinIndices.getOrDefault(uuid, -1);

            } catch (SQLException e) {
                e.printStackTrace();
                return -1;
            }
        });

        // 2-second fallback: if DB is slow, fetch MAX(join_index) so the broadcast
        // always has a real number to display.
        return future.applyToEither(
                CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    int cached = joinIndices.getOrDefault(uuid, -1);
                    if (cached > 0)
                        return cached;
                    return fetchMaxJoinIndex();
                }),
                idx -> {
                    // Ensure cache is always up-to-date with whatever we resolved
                    if (idx > 0)
                        joinIndices.put(uuid, idx);
                    return idx;
                });
    }

    /**
     * Fetches {@code MAX(join_index)} from the database. Used as a fallback when
     * {@code RETURN_GENERATED_KEYS} is not available within the timeout window.
     */
    public int fetchMaxJoinIndex() {
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT MAX(join_index) AS max_idx FROM seen_data")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next())
                return rs.getInt("max_idx");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    // ── Player Profile Snapshot ───────────────────────────────────────────────

    /**
     * Immutable snapshot of a player's database record, used by commands that
     * need the DB-authoritative values (exact_name, first_join, join_index) in
     * a single query.
     */
    public record PlayerProfile(String exactName, long firstJoin, int joinIndex) {
    }

    /**
     * Fetches a {@link PlayerProfile} directly from the database for the given
     * UUID. Never relies on in-memory caches so it is always authoritative.
     *
     * @return a completed future containing the profile, or an empty future
     *         ({@code null} value) if the UUID has no row in {@code seen_data}.
     */
    public CompletableFuture<PlayerProfile> getProfileAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT exact_name, first_join, join_index FROM seen_data WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String exactName = rs.getString("exact_name");
                    long firstJoin = rs.getLong("first_join");
                    int joinIndex = rs.getInt("join_index");
                    // Sync cache while we have the data
                    if (exactName != null) {
                        exactNames.put(uuid, exactName);
                        nameCache.put(exactName.toLowerCase(), uuid);
                    }
                    if (joinIndex > 0)
                        joinIndices.put(uuid, joinIndex);
                    return new PlayerProfile(
                            exactName != null ? exactName : uuid.toString(),
                            firstJoin,
                            joinIndex);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return null; // No row found
        });
    }

    private void loadJoinIndex(UUID uuid) {
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT join_index FROM seen_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                joinIndices.put(uuid, rs.getInt("join_index"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getJoinIndex(UUID uuid) {
        Integer index = joinIndices.get(uuid);
        if (index == null || index <= 0) {
            // Try DB fallback synchronously before giving up
            loadJoinIndex(uuid);
            index = joinIndices.getOrDefault(uuid, -1);
        }
        return index;
    }

    public String getJoinIndexFormatted(UUID uuid) {
        int index = getJoinIndex(uuid);
        return index > 0 ? String.valueOf(index) : "0";
    }

    // ── UUID / Name Resolution ────────────────────────────────────────────────

    public UUID getUUID(String name) {
        UUID cached = nameCache.get(name.toLowerCase());
        if (cached != null)
            return cached;

        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT uuid, exact_name FROM seen_data WHERE LOWER(exact_name) = ?")) {
            ps.setString(1, name.toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                String exactName = rs.getString("exact_name");
                nameCache.put(name.toLowerCase(), uuid);
                exactNames.put(uuid, exactName != null ? exactName : name);
                return uuid;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ── Msg Toggle ────────────────────────────────────────────────────────────

    public boolean isMsgEnabled(UUID uuid) {
        Boolean val = msgToggles.get(uuid);
        if (val == null) {
            loadData(uuid);
            return msgToggles.getOrDefault(uuid, true);
        }
        return val;
    }

    public void setMsgEnabled(UUID uuid, boolean enabled) {
        msgToggles.put(uuid, enabled);
        updateData(uuid);
    }

    // ── First Join ────────────────────────────────────────────────────────────

    /**
     * Fetches the {@code first_join} timestamp asynchronously, hitting the DB
     * directly to guarantee freshness.
     *
     * @return the epoch-millis timestamp, or {@code null} if the player has no
     *         record or their {@code first_join} column is 0/null.
     */
    public CompletableFuture<Long> getFirstJoinAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT first_join FROM seen_data WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    long val = rs.getLong("first_join");
                    return val == 0 ? null : val;
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    public Long getFirstJoin(UUID uuid) {
        Long val = firstJoins.get(uuid);
        if (val == null) {
            loadData(uuid);
            return firstJoins.get(uuid);
        }
        return val;
    }

    // ── Ignore List ───────────────────────────────────────────────────────────

    public boolean isIgnored(UUID player, UUID target) {
        Set<UUID> ignored = ignoreLists.get(player);
        if (ignored == null) {
            loadIgnores(player);
            ignored = ignoreLists.get(player);
        }
        return ignored != null && ignored.contains(target);
    }

    public void addIgnore(UUID player, UUID target) {
        ignoreLists.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(target);
        if (!isModuleEnabled("ignore-system"))
            return;
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT IGNORE INTO ignore_list (player_uuid, ignored_uuid) VALUES (?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, target.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void removeIgnore(UUID player, UUID target) {
        Set<UUID> ignored = ignoreLists.get(player);
        if (ignored != null) {
            ignored.remove(target);
        }
        if (!isModuleEnabled("ignore-system"))
            return;
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM ignore_list WHERE player_uuid = ? AND ignored_uuid = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, target.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ── Social Spy ────────────────────────────────────────────────────────────

    public boolean isSocialSpyEnabled(UUID uuid) {
        Boolean val = socialSpyStatus.get(uuid);
        if (val == null) {
            loadData(uuid);
            return socialSpyStatus.getOrDefault(uuid, false);
        }
        return val;
    }

    public void setSocialSpyEnabled(UUID uuid, boolean enabled) {
        socialSpyStatus.put(uuid, enabled);
        updateData(uuid);
    }

    // ── Internal DB Load ──────────────────────────────────────────────────────

    private void loadData(UUID uuid) {
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM seen_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                lastSeen.put(uuid, rs.getLong("last_seen"));
                firstJoins.put(uuid, rs.getLong("first_join"));
                msgToggles.put(uuid, rs.getBoolean("msg_enabled"));
                socialSpyStatus.put(uuid, rs.getBoolean("social_spy"));
                joinIndices.put(uuid, rs.getInt("join_index"));
                prefixes.put(uuid, rs.getString("prefix") != null ? rs.getString("prefix") : "");
                groupNames.put(uuid,
                        rs.getString("group_name") != null ? rs.getString("group_name") : "default");
                suffixes.put(uuid, rs.getString("suffix") != null ? rs.getString("suffix") : "");

                boolean vanishStatus = rs.getBoolean("vanish_status");
                if (vanishStatus) {
                    vanishedPlayers.add(uuid);
                }

                String name = rs.getString("exact_name");
                if (name != null) {
                    exactNames.put(uuid, name);
                    nameCache.put(name.toLowerCase(), uuid);
                }
            }

            // Load discord link
            try (PreparedStatement psLink = conn.prepareStatement(
                    "SELECT discord_id FROM discord_links WHERE uuid = ?")) {
                psLink.setString(1, uuid.toString());
                ResultSet rsLink = psLink.executeQuery();
                if (rsLink.next()) {
                    discordLinks.put(uuid, rsLink.getString("discord_id"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ── Core Update (Persists mutable fields only) ────────────────────────────

    /**
     * Persists all mutable data for {@code uuid} to the database asynchronously.
     *
     * <p>
     * <strong>Immutability contract:</strong> {@code first_join} and
     * {@code join_index} are NEVER included in the UPDATE portion of this method.
     * They are written once by {@link #createProfileIfMissing} and never touched
     * again.
     *
     * <p>
     * MariaDB: Uses {@code INSERT ... ON DUPLICATE KEY UPDATE} with only
     * mutable columns in the UPDATE clause. A new row (e.g. after a clear) will
     * receive a fresh {@code join_index} via AUTO_INCREMENT, but {@code first_join}
     * will be 0 until {@link #createProfileIfMissing} is called.
     */
    private void updateData(UUID uuid) {
        if (!isModuleEnabled("seen-tracking"))
            return;

        CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseManager.getConnection()) {
                if (databaseManager.isMariaDB()) {
                    // INSERT new row if absent, UPDATE only mutable fields on conflict.
                    // first_join and join_index are intentionally absent from UPDATE list.
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO seen_data " +
                                    "(uuid, last_seen, exact_name, vanish_status, prefix, group_name, suffix) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                                    "ON DUPLICATE KEY UPDATE " +
                                    "last_seen = VALUES(last_seen), " +
                                    "exact_name = VALUES(exact_name), " +
                                    "vanish_status = VALUES(vanish_status), " +
                                    "prefix = VALUES(prefix), " +
                                    "group_name = VALUES(group_name), " +
                                    "suffix = VALUES(suffix)")) {

                        ins.setString(1, uuid.toString());
                        ins.setLong(2, lastSeen.getOrDefault(uuid, 0L));
                        ins.setString(3, exactNames.get(uuid));
                        ins.setBoolean(4, vanishedPlayers.contains(uuid));
                        ins.setString(5, prefixes.getOrDefault(uuid, ""));
                        ins.setString(6, groupNames.getOrDefault(uuid, "default"));
                        ins.setString(7, suffixes.getOrDefault(uuid, ""));
                        ins.executeUpdate();
                    }

                    // Update msg_enabled and social_spy separately (don't conflict with insert)
                    try (PreparedStatement up = conn.prepareStatement(
                            "UPDATE seen_data SET msg_enabled = ?, social_spy = ? WHERE uuid = ?")) {
                        up.setBoolean(1, msgToggles.getOrDefault(uuid, true));
                        up.setBoolean(2, socialSpyStatus.getOrDefault(uuid, false));
                        up.setString(3, uuid.toString());
                        up.executeUpdate();
                    }
                } else {
                    // H2: SELECT-before-UPDATE to protect immutable fields
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT uuid FROM seen_data WHERE uuid = ?")) {
                        ps.setString(1, uuid.toString());
                        if (ps.executeQuery().next()) {
                            // Row exists — UPDATE only mutable fields
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE seen_data SET last_seen = ?, msg_enabled = ?, social_spy = ?, " +
                                            "exact_name = ?, vanish_status = ?, prefix = ?, group_name = ?, " +
                                            "suffix = ? WHERE uuid = ?")) {
                                up.setLong(1, lastSeen.getOrDefault(uuid, 0L));
                                up.setBoolean(2, msgToggles.getOrDefault(uuid, true));
                                up.setBoolean(3, socialSpyStatus.getOrDefault(uuid, false));
                                up.setString(4, exactNames.get(uuid));
                                up.setBoolean(5, vanishedPlayers.contains(uuid));
                                up.setString(6, prefixes.getOrDefault(uuid, ""));
                                up.setString(7, groupNames.getOrDefault(uuid, "default"));
                                up.setString(8, suffixes.getOrDefault(uuid, ""));
                                up.setString(9, uuid.toString());
                                up.executeUpdate();
                            }
                        } else {
                            // Row missing — INSERT (first_join not in cache at this point, leave 0)
                            try (PreparedStatement ins = conn.prepareStatement(
                                    "INSERT INTO seen_data " +
                                            "(uuid, last_seen, msg_enabled, social_spy, exact_name, vanish_status, " +
                                            "prefix, group_name, suffix) " +
                                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                                ins.setString(1, uuid.toString());
                                ins.setLong(2, lastSeen.getOrDefault(uuid, 0L));
                                ins.setBoolean(3, msgToggles.getOrDefault(uuid, true));
                                ins.setBoolean(4, socialSpyStatus.getOrDefault(uuid, false));
                                ins.setString(5, exactNames.get(uuid));
                                ins.setBoolean(6, vanishedPlayers.contains(uuid));
                                ins.setString(7, prefixes.getOrDefault(uuid, ""));
                                ins.setString(8, groupNames.getOrDefault(uuid, "default"));
                                ins.setString(9, suffixes.getOrDefault(uuid, ""));
                                ins.executeUpdate();
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    // ── Module Guard ──────────────────────────────────────────────────────────

    private boolean isModuleEnabled(String module) {
        if (smartConfig == null || smartConfig.getConfig() == null)
            return true;
        return smartConfig.getConfig().getBoolean("modules." + module + ".enabled", true);
    }

    // ── Shutdown Flush ────────────────────────────────────────────────────────

    /**
     * Flushes all cached data to the database synchronously on proxy shutdown.
     *
     * <p>
     * <strong>Immutability contract:</strong> {@code first_join} and
     * {@code join_index} are excluded from the UPDATE portion.
     */
    public void saveAll() {
        System.out.println("[MinewarVelocity] Flushing player data to database...");
        Set<UUID> allUuids = new HashSet<>();
        allUuids.addAll(lastSeen.keySet());
        allUuids.addAll(exactNames.keySet());
        allUuids.addAll(msgToggles.keySet());
        allUuids.addAll(socialSpyStatus.keySet());
        allUuids.addAll(joinIndices.keySet());

        try (Connection conn = databaseManager.getConnection()) {
            if (databaseManager.isMariaDB()) {
                // Mutable fields only in UPDATE — first_join and join_index never touched
                String sql = "INSERT INTO seen_data " +
                        "(uuid, last_seen, exact_name, vanish_status, prefix, group_name, suffix) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "last_seen = VALUES(last_seen), " +
                        "exact_name = VALUES(exact_name), " +
                        "vanish_status = VALUES(vanish_status), " +
                        "prefix = VALUES(prefix), " +
                        "group_name = VALUES(group_name), " +
                        "suffix = VALUES(suffix)";

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (UUID uuid : allUuids) {
                        ps.setString(1, uuid.toString());
                        ps.setLong(2, lastSeen.getOrDefault(uuid, 0L));
                        ps.setString(3, exactNames.get(uuid));
                        ps.setBoolean(4, vanishedPlayers.contains(uuid));
                        ps.setString(5, prefixes.getOrDefault(uuid, ""));
                        ps.setString(6, groupNames.getOrDefault(uuid, "default"));
                        ps.setString(7, suffixes.getOrDefault(uuid, ""));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // Batch update msg_enabled and social_spy
                try (PreparedStatement up = conn.prepareStatement(
                        "UPDATE seen_data SET msg_enabled = ?, social_spy = ? WHERE uuid = ?")) {
                    for (UUID uuid : allUuids) {
                        up.setBoolean(1, msgToggles.getOrDefault(uuid, true));
                        up.setBoolean(2, socialSpyStatus.getOrDefault(uuid, false));
                        up.setString(3, uuid.toString());
                        up.addBatch();
                    }
                    up.executeBatch();
                }
            } else {
                // H2: individual updates for mutable fields only
                for (UUID uuid : allUuids) {
                    try (PreparedStatement up = conn.prepareStatement(
                            "UPDATE seen_data SET last_seen = ?, msg_enabled = ?, social_spy = ?, " +
                                    "exact_name = ?, vanish_status = ?, prefix = ?, group_name = ?, " +
                                    "suffix = ? WHERE uuid = ?")) {
                        up.setLong(1, lastSeen.getOrDefault(uuid, 0L));
                        up.setBoolean(2, msgToggles.getOrDefault(uuid, true));
                        up.setBoolean(3, socialSpyStatus.getOrDefault(uuid, false));
                        up.setString(4, exactNames.get(uuid));
                        up.setBoolean(5, vanishedPlayers.contains(uuid));
                        up.setString(6, prefixes.getOrDefault(uuid, ""));
                        up.setString(7, groupNames.getOrDefault(uuid, "default"));
                        up.setString(8, suffixes.getOrDefault(uuid, ""));
                        up.setString(9, uuid.toString());
                        up.executeUpdate();
                    }
                }
            }
            System.out.println("[MinewarVelocity] Successfully saved data for " + allUuids.size() + " players.");
        } catch (SQLException e) {
            System.err.println("[MinewarVelocity] CRITICAL: Failed to flush data on shutdown!");
            e.printStackTrace();
        }
    }

    // ── Discord ───────────────────────────────────────────────────────────────

    public CompletableFuture<Map<UUID, String>> getLinkedUsers() {
        if (!isModuleEnabled("discord-sync"))
            return CompletableFuture.completedFuture(Collections.emptyMap());
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, String> users = new HashMap<>();
            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement("SELECT uuid, discord_id FROM discord_links")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    users.put(UUID.fromString(rs.getString("uuid")), rs.getString("discord_id"));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return users;
        });
    }

    public String getDiscordId(UUID uuid) {
        String id = discordLinks.get(uuid);
        if (id == null) {
            loadData(uuid);
            id = discordLinks.get(uuid);
        }
        return id;
    }

    public void unlinkAccount(UUID uuid) {
        discordLinks.remove(uuid);
        if (!isModuleEnabled("discord-sync"))
            return;
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM discord_links WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void linkAccount(UUID uuid, String discordId) {
        if (discordId == null) {
            unlinkAccount(uuid);
            return;
        }
        discordLinks.put(uuid, discordId);
        if (!isModuleEnabled("discord-sync"))
            return;
        try (Connection conn = databaseManager.getConnection()) {
            if (databaseManager.isMariaDB()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO discord_links (uuid, discord_id) VALUES (?, ?) " +
                                "ON DUPLICATE KEY UPDATE discord_id = VALUES(discord_id)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, discordId);
                    ps.executeUpdate();
                }
            } else {
                // H2: check-then-insert/update (MERGE INTO is H2-only dialect)
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT uuid FROM discord_links WHERE uuid = ?")) {
                    check.setString(1, uuid.toString());
                    if (check.executeQuery().next()) {
                        try (PreparedStatement up = conn.prepareStatement(
                                "UPDATE discord_links SET discord_id = ? WHERE uuid = ?")) {
                            up.setString(1, discordId);
                            up.setString(2, uuid.toString());
                            up.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO discord_links (uuid, discord_id) VALUES (?, ?)")) {
                            ins.setString(1, uuid.toString());
                            ins.setString(2, discordId);
                            ins.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public UUID getUUIDByDiscordId(String discordId) {
        for (Map.Entry<UUID, String> entry : discordLinks.entrySet()) {
            if (entry.getValue().equals(discordId))
                return entry.getKey();
        }
        if (!isModuleEnabled("discord-sync"))
            return null;

        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT uuid FROM discord_links WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                discordLinks.put(uuid, discordId);
                return uuid;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void createLinkToken(UUID uuid, String token) {
        if (!isModuleEnabled("discord-sync"))
            return;
        try (Connection conn = databaseManager.getConnection()) {
            if (databaseManager.isMariaDB()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO discord_tokens (token, uuid, expires_at) VALUES (?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE uuid = VALUES(uuid), expires_at = VALUES(expires_at)")) {
                    ps.setString(1, token);
                    ps.setString(2, uuid.toString());
                    ps.setLong(3, System.currentTimeMillis() + (10 * 60 * 1000));
                    ps.executeUpdate();
                }
            } else {
                // H2: check-then-insert/update
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT token FROM discord_tokens WHERE token = ?")) {
                    check.setString(1, token);
                    if (check.executeQuery().next()) {
                        try (PreparedStatement up = conn.prepareStatement(
                                "UPDATE discord_tokens SET uuid = ?, expires_at = ? WHERE token = ?")) {
                            up.setString(1, uuid.toString());
                            up.setLong(2, System.currentTimeMillis() + (10 * 60 * 1000));
                            up.setString(3, token);
                            up.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO discord_tokens (token, uuid, expires_at) VALUES (?, ?, ?)")) {
                            ins.setString(1, token);
                            ins.setString(2, uuid.toString());
                            ins.setLong(3, System.currentTimeMillis() + (10 * 60 * 1000));
                            ins.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public UUID validateLinkToken(String token) {
        if (!isModuleEnabled("discord-sync"))
            return null;
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT uuid FROM discord_tokens WHERE token = ? AND expires_at > ?")) {
            ps.setString(1, token);
            ps.setLong(2, System.currentTimeMillis());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM discord_tokens WHERE token = ?")) {
                    del.setString(1, token);
                    del.executeUpdate();
                }
                return uuid;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ── Ignore Lists ──────────────────────────────────────────────────────────

    public Set<UUID> getIgnoreList(UUID player) {
        Set<UUID> ignored = ignoreLists.get(player);
        if (ignored == null) {
            loadIgnores(player);
            ignored = ignoreLists.get(player);
        }
        return ignored != null ? ignored : Collections.emptySet();
    }

    private void loadIgnores(UUID uuid) {
        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT ignored_uuid FROM ignore_list WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            Set<UUID> ignored = ConcurrentHashMap.newKeySet();
            while (rs.next()) {
                ignored.add(UUID.fromString(rs.getString("ignored_uuid")));
            }
            ignoreLists.put(uuid, ignored);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ── Staff Chat ────────────────────────────────────────────────────────────

    public Map<String, UUID> getNameCache() {
        return nameCache;
    }

    public boolean isStaffChatToggled(UUID uuid) {
        return staffChatToggled.contains(uuid);
    }

    public void setStaffChatToggled(UUID uuid, boolean enabled) {
        if (enabled) {
            staffChatToggled.add(uuid);
        } else {
            staffChatToggled.remove(uuid);
        }
    }

    // ── Name ──────────────────────────────────────────────────────────────────

    public String getName(UUID uuid) {
        String exact = exactNames.get(uuid);
        if (exact != null) {
            return exact;
        }
        loadData(uuid);
        return exactNames.get(uuid);
    }

    // ── Vanish ────────────────────────────────────────────────────────────────

    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.contains(uuid);
    }

    public void setVanished(UUID uuid, boolean vanished) {
        if (vanished) {
            vanishedPlayers.add(uuid);
        } else {
            vanishedPlayers.remove(uuid);
        }
        updateData(uuid);
    }

    public int getVanishedCount() {
        return vanishedPlayers.size();
    }

    public boolean isAutoVanish(UUID uuid) {
        return autoVanishPlayers.contains(uuid);
    }

    public void setAutoVanish(UUID uuid, boolean enabled) {
        if (enabled) {
            autoVanishPlayers.add(uuid);
        } else {
            autoVanishPlayers.remove(uuid);
        }
    }

    // ── Player Data Clear ─────────────────────────────────────────────────────

    /**
     * Wipes ALL stored data for the given UUID, both in-memory and in the database.
     *
     * <p>
     * After this call the player is treated as if they have never joined, so the
     * first-join welcome broadcast will fire on their next login.
     *
     * @return {@code true} if a seen_data row existed and was deleted.
     */
    public CompletableFuture<Boolean> clearPlayer(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            boolean found = false;
            try (Connection conn = databaseManager.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM seen_data WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    found = ps.executeUpdate() > 0;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM ignore_list WHERE player_uuid = ? OR ignored_uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, uuid.toString());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            // Evict all in-memory caches
            String name = exactNames.remove(uuid);
            if (name != null)
                nameCache.remove(name.toLowerCase());
            lastSeen.remove(uuid);
            firstJoins.remove(uuid);
            msgToggles.remove(uuid);
            socialSpyStatus.remove(uuid);
            ignoreLists.remove(uuid);
            staffChatToggled.remove(uuid);
            vanishedPlayers.remove(uuid);
            autoVanishPlayers.remove(uuid);
            joinIndices.remove(uuid);

            return found;
        });
    }

    // ── Prefix / Group / Suffix ───────────────────────────────────────────────

    public String getPrefix(UUID uuid) {
        String p = prefixes.get(uuid);
        if (p == null) {
            loadData(uuid);
            p = prefixes.getOrDefault(uuid, "");
        }
        return p;
    }

    public void setPrefix(UUID uuid, String prefix) {
        prefixes.put(uuid, prefix != null ? prefix : "");
        updateData(uuid);
    }

    public String getGroupName(UUID uuid) {
        String g = groupNames.get(uuid);
        if (g == null) {
            loadData(uuid);
            g = groupNames.getOrDefault(uuid, "default");
        }
        return g;
    }

    public void setGroupName(UUID uuid, String groupName) {
        groupNames.put(uuid, groupName != null ? groupName : "default");
        updateData(uuid);
    }

    public String getSuffix(UUID uuid) {
        String s = suffixes.get(uuid);
        if (s == null) {
            loadData(uuid);
            s = suffixes.getOrDefault(uuid, "");
        }
        return s;
    }

    public void setSuffix(UUID uuid, String suffix) {
        suffixes.put(uuid, suffix != null ? suffix : "");
        updateData(uuid);
    }
}
