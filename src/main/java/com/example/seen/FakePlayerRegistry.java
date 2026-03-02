package com.example.seen;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of all active LoshFP fake players (Citizens NPCs)
 * announced by Paper backends via the {@code minewar:fakeplayer} plugin message
 * channel.
 *
 * <p>
 * All server-name keys are stored in lowercase to guarantee case-insensitive
 * matching regardless of what the Paper backend sends (e.g. "Survival" vs
 * "survival").
 * </p>
 */
@Singleton
public class FakePlayerRegistry {

    /** name (lowercase) → FakePlayerEntry */
    private final Map<String, FakePlayerEntry> byName = new ConcurrentHashMap<>();

    /** serverName (lowercase) → list of entries on that server */
    private final Map<String, List<FakePlayerEntry>> byServer = new ConcurrentHashMap<>();

    @Inject
    public FakePlayerRegistry() {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /** Add or replace a fake player entry (handles UPDATE and re-CREATE). */
    public void add(FakePlayerEntry entry) {
        String nameKey = entry.name().toLowerCase();
        String serverKey = entry.serverName().toLowerCase();

        // Remove any existing entry with this name first
        FakePlayerEntry existing = byName.remove(nameKey);
        if (existing != null)
            removeFromServerMap(existing);

        byName.put(nameKey, entry);
        byServer.computeIfAbsent(serverKey, k -> new ArrayList<>()).add(entry);
    }

    /** Remove an NPC by name. Returns true if it was present. */
    public boolean remove(String name) {
        FakePlayerEntry removed = byName.remove(name.toLowerCase());
        if (removed == null)
            return false;
        removeFromServerMap(removed);
        return true;
    }

    /**
     * Remove all NPCs registered for a specific backend server.
     * Matching is <b>case-insensitive</b>.
     *
     * @return the removed entries (empty if none found)
     */
    public List<FakePlayerEntry> clearServer(String serverName) {
        List<FakePlayerEntry> removed = byServer.remove(serverName.toLowerCase());
        if (removed == null)
            return Collections.emptyList();
        for (FakePlayerEntry e : removed)
            byName.remove(e.name().toLowerCase());
        return removed;
    }

    /** Remove all NPCs from all servers. */
    public void clearAll() {
        byName.clear();
        byServer.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────────────────────────────────

    public boolean contains(String name) {
        return byName.containsKey(name.toLowerCase());
    }

    public FakePlayerEntry get(String name) {
        return byName.get(name.toLowerCase());
    }

    /** Total number of registered fake players across all servers. */
    public int totalCount() {
        return byName.size();
    }

    /**
     * Number of registered fake players on a specific backend.
     * <b>Case-insensitive</b> server name match.
     */
    public int countForServer(String serverName) {
        List<FakePlayerEntry> list = byServer.get(serverName.toLowerCase());
        return list == null ? 0 : list.size();
    }

    /**
     * All registered NPC names in their original casing. Snapshot, safe to iterate.
     */
    public List<String> getAllNames() {
        // Return original casing (stored in the entry), not the lowercased key
        List<String> names = new ArrayList<>();
        for (FakePlayerEntry e : byName.values())
            names.add(e.name());
        return names;
    }

    /** All entries. Snapshot, safe to iterate. */
    public List<FakePlayerEntry> getAll() {
        return new ArrayList<>(byName.values());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private void removeFromServerMap(FakePlayerEntry entry) {
        String serverKey = entry.serverName().toLowerCase();
        List<FakePlayerEntry> list = byServer.get(serverKey);
        if (list != null) {
            list.removeIf(e -> e.name().equalsIgnoreCase(entry.name()));
            if (list.isEmpty())
                byServer.remove(serverKey);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner Record
    // ─────────────────────────────────────────────────────────────────────────

    /** Immutable snapshot of a single fake player's data. */
    public record FakePlayerEntry(
            String name,
            String serverName,
            String rank,
            String prefix,
            String group,
            int weight,
            java.util.List<com.velocitypowered.api.util.GameProfile.Property> skinProperties) {

        /** Returns a copy with updated rank, prefix, group, weight, and skin. */
        public FakePlayerEntry withSyncData(String newRank, String newPrefix, String newGroup, int newWeight,
                java.util.List<com.velocitypowered.api.util.GameProfile.Property> newSkin) {
            return new FakePlayerEntry(name, serverName, newRank, newPrefix, newGroup, newWeight, newSkin);
        }
    }
}
