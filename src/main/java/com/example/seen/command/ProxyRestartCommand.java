package com.example.seen.command;

import com.example.seen.config.SmartConfig;
import com.example.seen.ConfigManager;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import com.velocitypowered.api.plugin.PluginContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyRestartCommand implements RawCommand {

    private final ProxyServer server;
    private final SmartConfig smartConfig;
    private final ConfigManager configManager;
    private final Object plugin; // Need plugin instance for scheduler

    public ProxyRestartCommand(ProxyServer server, SmartConfig smartConfig, ConfigManager configManager,
            Object plugin) {
        this.server = server;
        this.smartConfig = smartConfig;
        this.configManager = configManager;
        this.plugin = plugin;
    }

    private static ScheduledTask currentRestartTask = null;

    public static boolean cancelRestart() {
        if (currentRestartTask != null) {
            currentRestartTask.cancel();
            currentRestartTask = null;
            return true;
        }
        return false;
    }

    @Override
    public void execute(Invocation invocation) {
        String rawArgs = invocation.arguments();

        String[] args = rawArgs.trim().isEmpty() ? new String[0] : rawArgs.split("\\s+");

        // Cancel check
        if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) {
            String cancelPerm = smartConfig.getConfig().getString("permissions.proxy_restart_cancel",
                    "minewar.restart.cancel");
            if (!invocation.source().hasPermission(cancelPerm)) {
                invocation.source()
                        .sendMessage(MiniMessage.miniMessage().deserialize(configManager.getMessage("no-permission")));
                return;
            }

            if (currentRestartTask != null) {
                currentRestartTask.cancel();
                currentRestartTask = null;
                server.getAllPlayers().forEach(p -> p.sendMessage(
                        MiniMessage.miniMessage().deserialize(configManager.getMessage("restart-cancelled"))));
            } else {
                invocation.source().sendMessage(
                        MiniMessage.miniMessage().deserialize(configManager.getMessage("restart-not-running")));
            }
            return;
        }

        // Permission check for starting restart
        String perm = smartConfig.getConfig().getString("permissions.proxy_restart", "minewar.restart");
        if (!invocation.source().hasPermission(perm)) {
            invocation.source()
                    .sendMessage(MiniMessage.miniMessage().deserialize(configManager.getMessage("no-permission")));
            return;
        }

        int seconds;
        if (args.length > 0) {
            try {
                seconds = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                invocation.source()
                        .sendMessage(MiniMessage.miniMessage().deserialize(configManager.getMessage("invalid-number")));
                return;
            }
        } else {
            seconds = smartConfig.getConfig().getInt("ProxyRestart.defaultRestartTime", 600);
        }

        startRestartTimer(seconds);
        String scheduledMsg = configManager.getMessage("restart-scheduled").replace("%seconds%",
                String.valueOf(seconds));
        invocation.source().sendMessage(MiniMessage.miniMessage().deserialize(scheduledMsg));
    }

    private void startRestartTimer(int startSeconds) {
        if (currentRestartTask != null) {
            currentRestartTask.cancel();
        }

        List<Integer> intervals = smartConfig.getConfig().getIntList("ProxyRestart.broadcastIntervals");
        AtomicInteger remaining = new AtomicInteger(startSeconds);

        PluginContainer pluginContainer = server.getPluginManager().fromInstance(plugin).orElse(null);
        if (pluginContainer == null)
            return;

        currentRestartTask = server.getScheduler().buildTask(pluginContainer, () -> {
            int time = remaining.getAndDecrement();

            if (time <= 0) {
                server.getAllPlayers().forEach(p -> p
                        .sendMessage(MiniMessage.miniMessage().deserialize(configManager.getMessage("restart-now"))));
                server.shutdown();
                return;
            }

            if (intervals.contains(time)) {
                String msg = configManager.getMessage("restart-broadcast").replace("%time%", formatTime(time));
                server.getAllPlayers().forEach(p -> p.sendMessage(MiniMessage.miniMessage().deserialize(msg)));
            }
        }).repeat(Duration.ofSeconds(1)).schedule();
    }

    private String formatTime(int seconds) {
        if (seconds >= 60) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return seconds + "s";
    }
}
