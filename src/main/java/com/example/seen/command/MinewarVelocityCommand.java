package com.example.seen.command;

import com.example.seen.ConfigManager;
import com.example.seen.DataManager;
import com.example.seen.DiscordManager;
import com.example.seen.MessageUtils;
import com.example.seen.MinewarVelocity;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Handles the {@code /minewarvelocity} command and its subcommands:
 * <ul>
 * <li>{@code reload} — reloads config/messages (perm:
 * {@code minewarvelocity.reload})</li>
 * <li>{@code clear <player>} — wipes all data for a player, resetting their
 * first-join
 * state (perm: {@code minewarvelocity.admin})</li>
 * </ul>
 */
public class MinewarVelocityCommand implements RawCommand {

    private final ConfigManager configManager;
    private final DataManager dataManager;
    private final DiscordManager discordManager;
    private final MinewarVelocity plugin;
    private final ProxyServer server;

    public MinewarVelocityCommand(ConfigManager configManager, DiscordManager discordManager,
            MinewarVelocity plugin) {
        this.configManager = configManager;
        this.discordManager = discordManager;
        this.plugin = plugin;
        this.dataManager = null; // legacy constructor path — clear will be unavailable
        this.server = null;
    }

    /**
     * Extended constructor with access to {@link DataManager} and
     * {@link ProxyServer}
     * for the {@code clear} subcommand. Register via this constructor in the main
     * plugin.
     */
    public MinewarVelocityCommand(ConfigManager configManager, DiscordManager discordManager,
            MinewarVelocity plugin, DataManager dataManager, ProxyServer server) {
        this.configManager = configManager;
        this.discordManager = discordManager;
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.server = server;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");
        Player sourcePlayer = source instanceof Player ? (Player) source : null;

        // ── Base permission ────────────────────────────────────────────────────
        String basePerm = configManager.getConfigString("permissions.minewarvelocity_base", "minewarvelocity.base");
        if (!source.hasPermission(basePerm)) {
            send(source, configManager.getMessage("error.no-permission"));
            return;
        }

        if (args.length == 0) {
            MessageUtils.parseWithPapi(configManager.getMessage("minewarvelocity-header"), sourcePlayer, (UUID) null)
                    .thenAccept(source::sendMessage);
            return;
        }

        switch (args[0].toLowerCase()) {

            // ── /minewarvelocity reload ────────────────────────────────────────
            case "reload" -> {
                String reloadPerm = configManager.getConfigString("permissions.minewarvelocity_reload",
                        "minewarvelocity.reload");
                if (!source.hasPermission(reloadPerm)) {
                    send(source, configManager.getMessage("error.no-permission"));
                    return;
                }
                plugin.reload();

                send(source, configManager.getMessage("reload-success")
                        + " <green>(Full reload completed)</green>");
            }

            // ── /minewarvelocity clear <player> ────────────────────────────────
            case "clear" -> {
                if (!source.hasPermission("minewarvelocity.admin")) {
                    send(source, configManager.getMessage("error.no-permission"));
                    return;
                }
                if (args.length < 2) {
                    send(source, "<red>Usage: /minewarvelocity clear <player>");
                    return;
                }
                if (dataManager == null) {
                    send(source, "<red>DataManager is unavailable. Use the extended constructor.");
                    return;
                }

                String targetName = args[1];
                // Resolve UUID — check in-memory cache then DB
                UUID targetUuid = dataManager.getUUID(targetName);

                if (targetUuid == null) {
                    // Try online player as last resort
                    if (server != null) {
                        targetUuid = server.getPlayer(targetName)
                                .map(Player::getUniqueId)
                                .orElse(null);
                    }
                }

                if (targetUuid == null) {
                    String msg = configManager.getMessage("clear-not-found")
                            .replace("%player%", targetName);
                    send(source, msg);
                    return;
                }

                final UUID finalUuid = targetUuid;
                dataManager.clearPlayer(finalUuid).thenAccept(found -> {
                    String msgKey = found ? "clear-success" : "clear-not-found";
                    String msg = configManager.getMessage(msgKey)
                            .replace("%player%", targetName);
                    send(source, msg);
                });
            }

            // ── Unknown subcommand ────────────────────────────────────────────
            default -> MessageUtils.parseWithPapi(configManager.getMessage("minewarvelocity-header"),
                    sourcePlayer, (UUID) null).thenAccept(source::sendMessage);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String rawArgs = invocation.arguments();
        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return Arrays.stream(new String[] { "reload", "clear" })
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }

        // Tab-complete player names for /minewarvelocity clear <player>
        if (args.length == 2 && args[0].equalsIgnoreCase("clear") && server != null) {
            String prefix = args[1].toLowerCase();
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }

        return Collections.emptyList();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Shorthand: deserialize a MiniMessage string and send it to the source. */
    private void send(CommandSource source, String miniMessage) {
        source.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
    }
}
