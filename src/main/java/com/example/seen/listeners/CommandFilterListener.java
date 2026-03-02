package com.example.seen.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.example.seen.DataManager;
import com.example.seen.FakePlayerRegistry;
import com.example.seen.config.SmartConfig;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Centralized Tab-Complete Filter — Velocity.
 *
 * Whitelist logic (onTabComplete):
 * 1. Admin bypass (minewar.admin.tab) → pass through.
 * 2. Player on an IGNORE_ME server → pass through.
 * 3. "/" alone → inject whitelist labels.
 * 4. Transparency commands → pass through (player names, server names, etc.).
 * 5. /minewarvelocity → pass through if player has minewarvelocity.reload, else
 * clear.
 * 6. /unignore → inject names from the player's ignore list.
 * 7. /server → inject Velocity-registered server names.
 * 8. Whitelisted label → pass through.
 * 9. Non-whitelisted → clear.
 *
 * Brigadier filtering (onAvailableCommands):
 * - Filter all non-whitelisted root nodes.
 * - Filter the "reload" child of /minewarvelocity for players without the perm.
 *
 * Server kill-switch:
 * - Paper sends TabCompleteIgnoreMe / TabCompleteResume via minewar:sync.
 * - Players on ignored servers bypass all filtering.
 */
public class CommandFilterListener {

    private final String bypassPermission;
    private final ProxyServer proxy;
    private final DataManager dataManager;
    private final SmartConfig smartConfig;
    private final FakePlayerRegistry fakePlayerRegistry;

    // Thread-safe whitelist
    private volatile List<String> allowedCommands;
    private volatile boolean enabled;

    /**
     * Commands where the FIRST argument is an online player name.
     * When the player is typing this argument, we actively inject online player
     * names.
     * Once they've typed a space after the player name (second+ arg), we pass
     * through.
     */
    private static final Set<String> PLAYER_ARG_COMMANDS = Set.of(
            "msg", "message", "tell", "w", "m", "whisper",
            "adminmessage", "am", "adminmsg", "msgadmin",
            "ignore",
            "find",
            "seen",
            "adminreply");

    /**
     * Commands that are fully transparent — no intervention at all.
     * Their argument completions come entirely from the backend.
     */
    private static final Set<String> FULL_PASSTHROUGH_COMMANDS = Set.of(
            "reply", "r");

    public CommandFilterListener(List<String> allowedCommands, String bypassPermission,
            boolean enabled, ProxyServer proxy, DataManager dataManager, SmartConfig smartConfig,
            FakePlayerRegistry fakePlayerRegistry) {
        this.bypassPermission = bypassPermission;
        this.proxy = proxy;
        this.dataManager = dataManager;
        this.smartConfig = smartConfig;
        this.fakePlayerRegistry = fakePlayerRegistry;
        this.allowedCommands = allowedCommands != null
                ? new CopyOnWriteArrayList<>(allowedCommands)
                : new CopyOnWriteArrayList<>();
        this.enabled = enabled;
    }

    /** Live-update the whitelist (called after config reload). */
    public void updateWhitelist(List<String> commands, boolean enabled) {
        this.allowedCommands = commands != null
                ? new CopyOnWriteArrayList<>(commands)
                : new CopyOnWriteArrayList<>();
        this.enabled = enabled;
    }

    /**
     * Called by PluginMessageBridge — kept for compatibility but no longer used for
     * server bypass.
     */
    public void markServerIgnored(String serverName) {
        /* no-op — use disabled-servers in config */ }

    public void markServerActive(String serverName) {
        /* no-op — use disabled-servers in config */ }

    public List<String> getAllowedCommands() {
        return Collections.unmodifiableList(allowedCommands);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ── Helper: is the player on a disabled server? ───────────────────────────

    private boolean isOnIgnoredServer(Player player) {
        List<String> disabled = smartConfig.getDisabledServers();
        if (disabled.isEmpty())
            return false;
        return player.getCurrentServer()
                .map(conn -> disabled.stream()
                        .anyMatch(s -> s.equalsIgnoreCase(conn.getServerInfo().getName())))
                .orElse(false);
    }

    // ── Stage 1: Real-Time Tab-Complete ──────────────────────────────────────

    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        if (!enabled)
            return;

        Player player = event.getPlayer();

        // 1. Admin bypass
        if (player.hasPermission(bypassPermission))
            return;

        // 2. Server kill-switch
        if (isOnIgnoredServer(player))
            return;

        String input = event.getPartialMessage();
        if (!input.startsWith("/"))
            return;

        String buffer = input.substring(1); // strip leading /
        String[] parts = buffer.split(" ", 2);
        String label = parts[0].toLowerCase();
        boolean hasArgs = parts.length > 1; // space was typed after the label

        // Rule 4: Strip Namespaced Commands
        if (label.contains(":")) {
            event.getSuggestions().clear();
            return;
        }

        // 3. Empty buffer ("/" alone) → inject whitelist
        if (label.isEmpty()) {
            event.getSuggestions().clear();
            event.getSuggestions().addAll(allowedCommands);
            return;
        }

        // 4. Player-argument commands → inject online player names for first arg
        if (PLAYER_ARG_COMMANDS.contains(label)) {
            if (!hasArgs) {
                // Player typed "/msg" with no space yet — no suggestions needed
                event.getSuggestions().clear();
            } else {
                // Player is typing the first argument (player name)
                String argTyped = parts[1];
                boolean onSecondArg = argTyped.contains(" "); // space = past player name
                if (onSecondArg) {
                    // Second+ argument (the message body) — pass through naturally
                    return;
                }
                // First argument — inject matching online player names
                String argPrefix = argTyped.toLowerCase();
                String seeVanishPerm = smartConfig.getConfig().getString("permissions.vanish_see",
                        "minewar.vanish.see");
                boolean canSeeVanished = player.hasPermission(seeVanishPerm);

                List<String> names = proxy.getAllPlayers().stream()
                        .filter(p -> !p.getUniqueId().equals(player.getUniqueId())) // exclude self
                        .filter(p -> canSeeVanished || !dataManager.isVanished(p.getUniqueId())) // respect vanish
                        .map(p -> p.getUsername())
                        .filter(n -> n.toLowerCase().startsWith(argPrefix))
                        .collect(Collectors.toCollection(ArrayList::new));

                // Rule 3: The Sahorah Bot Exception
                String botName = smartConfig.getConfig().getString("bot.username", "Sahorah");
                if (botName.toLowerCase().startsWith(argPrefix)) {
                    if (!names.contains(botName)) {
                        names.add(botName);
                    }
                }

                // Rule 4: LoshFP Fake Player Exception — inject active NPC names
                for (String npcName : fakePlayerRegistry.getAllNames()) {
                    if (npcName.toLowerCase().startsWith(argPrefix) && !names.contains(npcName)) {
                        names.add(npcName);
                    }
                }

                event.getSuggestions().clear();
                event.getSuggestions().addAll(names);
            }
            return;
        }

        // 4b. Full pass-through commands (reply, etc.) — backend handles everything
        if (FULL_PASSTHROUGH_COMMANDS.contains(label))
            return;

        // 5. /minewarvelocity — permission-gated sub-commands
        if (label.equals("minewarvelocity") || label.equals("mwv")) {
            if (player.hasPermission("minewarvelocity.reload")) {
                return; // pass through — reload suggestion visible
            } else {
                event.getSuggestions().clear();
                return;
            }
        }

        // 6. /unignore — inject names from the player's ignore list
        if (label.equals("unignore")) {
            if (hasArgs) {
                // Already typing the argument — filter suggestions to ignore list names
                String argPrefix = parts[1].toLowerCase();
                Set<UUID> ignored = dataManager.getIgnoreList(player.getUniqueId());
                List<String> names = ignored.stream()
                        .map(dataManager::getName)
                        .filter(Objects::nonNull)
                        .filter(n -> n.toLowerCase().startsWith(argPrefix))
                        .collect(Collectors.toList());
                event.getSuggestions().clear();
                event.getSuggestions().addAll(names);
            } else {
                // Just typed "/unignore" — inject all ignored player names
                Set<UUID> ignored = dataManager.getIgnoreList(player.getUniqueId());
                List<String> names = ignored.stream()
                        .map(dataManager::getName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                event.getSuggestions().clear();
                event.getSuggestions().addAll(names);
            }
            return;
        }

        // 7. /server — inject Velocity-registered server names
        if (label.equals("server")) {
            if (hasArgs) {
                String argPrefix = parts[1].toLowerCase();
                List<String> servers = proxy.getAllServers().stream()
                        .map(s -> s.getServerInfo().getName())
                        .filter(n -> n.toLowerCase().startsWith(argPrefix))
                        .collect(Collectors.toList());
                event.getSuggestions().clear();
                event.getSuggestions().addAll(servers);
            } else {
                List<String> servers = proxy.getAllServers().stream()
                        .map(s -> s.getServerInfo().getName())
                        .collect(Collectors.toList());
                event.getSuggestions().clear();
                event.getSuggestions().addAll(servers);
            }
            return;
        }

        // 8. Whitelisted command OR Dynamic Permission Check → pass through completely
        if (allowedCommands.contains(label)) {
            return;
        }

        // Rule 1: LuckPerms Permission Check / Standard Permission Check
        if (player.hasPermission(label) ||
                player.hasPermission("minewar.command." + label) ||
                player.hasPermission("minecraft.command." + label) ||
                player.hasPermission("velocity.command." + label) ||
                player.hasPermission("bukkit.command." + label)) {
            return;
        }

        // 9. Non-whitelisted & No Permission → clear all suggestions
        event.getSuggestions().clear();
    }

    // ── Stage 2: Brigadier Tree Pruning ──────────────────────────────────────

    @Subscribe
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        if (!enabled)
            return;

        Player player = event.getPlayer();

        // Admin bypass
        if (player.hasPermission(bypassPermission))
            return;

        // Server kill-switch
        if (isOnIgnoredServer(player))
            return;

        if (allowedCommands.isEmpty())
            return;

        RootCommandNode<?> root = event.getRootNode();

        try {
            Field childrenField = CommandNode.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, CommandNode<?>> children = (Map<String, CommandNode<?>>) childrenField.get(root);

            // 1. Collect labels we want to KEEP (whitelist + per-perm check)
            Set<String> keepNamesSet = new HashSet<>();
            for (String name : children.keySet()) {
                String l = name.toLowerCase();
                if (allowedCommands.contains(l) ||
                        PLAYER_ARG_COMMANDS.contains(l) ||
                        FULL_PASSTHROUGH_COMMANDS.contains(l) ||
                        l.equals("server") || l.equals("unignore") ||
                        l.equals("minewarvelocity") || l.equals("mwv") ||
                        player.hasPermission("minewar.command." + l) ||
                        player.hasPermission("minecraft.command." + l) ||
                        player.hasPermission("velocity.command." + l) ||
                        player.hasPermission("bukkit.command." + l)) {
                    keepNamesSet.add(name);
                }
            }

            // 2. Remove any that aren't in keepNamesSet
            children.keySet().removeIf(name -> !keepNamesSet.contains(name));

            // Also prune specific subcommands for mwv if no reload permission
            if (!player.hasPermission("minewarvelocity.reload")) {
                CommandNode<?> mwvNode = children.get("minewarvelocity");
                if (mwvNode == null)
                    mwvNode = children.get("mwv");
                if (mwvNode != null) {
                    pruneChild(mwvNode, "reload");
                }
            }

            // Prune literals
            try {
                Field literalsField = CommandNode.class.getDeclaredField("literals");
                literalsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, ?> literals = (Map<String, ?>) literalsField.get(root);
                literals.keySet().removeIf(name -> !keepNamesSet.contains(name.toLowerCase()));
            } catch (NoSuchFieldException ignored) {
                // Not present in this Brigadier version
            }

        } catch (Exception e) {
            if (!(e instanceof NoSuchFieldException)) {
                System.err.println("[MinewarVelocity] Failed to prune Brigadier tree: " + e.getMessage());
            }
        }
    }

    /** Removes a named child node from a parent CommandNode via reflection. */
    private void pruneChild(CommandNode<?> parent, String childName) {
        try {
            Field childrenField = CommandNode.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> children = (Map<String, ?>) childrenField.get(parent);
            children.remove(childName);

            try {
                Field literalsField = CommandNode.class.getDeclaredField("literals");
                literalsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, ?> literals = (Map<String, ?>) literalsField.get(parent);
                literals.remove(childName);
            } catch (NoSuchFieldException ignored) {
            }
        } catch (Exception e) {
            System.err.println("[MinewarVelocity] Failed to prune child node '" + childName + "': " + e.getMessage());
        }
    }
}
