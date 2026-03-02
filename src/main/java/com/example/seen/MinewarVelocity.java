package com.example.seen;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.example.seen.command.*;
import com.example.seen.listeners.*;
import com.example.seen.database.*;
import org.slf4j.Logger;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import java.util.List;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

@Plugin(id = "minewarvelocity", name = "MinewarVelocity", version = "1.0-SNAPSHOT", authors = {
                "Jxsh" }, dependencies = {
                                @com.velocitypowered.api.plugin.Dependency(id = "velocitab", optional = true) })
public class MinewarVelocity {

        private final ProxyServer server;
        private final Logger logger;
        private final ConfigManager configManager;
        private final DataManager dataManager;
        private final DiscordManager discordManager;
        private final VanishManager vanishManager;
        private final MessageManager messageManager;
        private final DatabaseManager databaseManager;
        private final com.example.seen.config.SmartConfig smartConfig;
        private final FakePlayerRegistry fakePlayerRegistry;
        private CommandFilterListener commandFilterListener;
        private FakePlayerMessageHandler fakePlayerMessageHandler;
        private final UUID botUuid = UUID.nameUUIDFromBytes("Minewar-Sahorah-Bot".getBytes());
        private final HttpClient httpClient = HttpClient.newHttpClient();

        public static final MinecraftChannelIdentifier STAFF_CHAT_CHANNEL = MinecraftChannelIdentifier
                        .from("minewar:staffchat");
        public static final MinecraftChannelIdentifier VANISH_CHANNEL = MinecraftChannelIdentifier
                        .from("minewar:vanish");
        public static final MinecraftChannelIdentifier GLOBAL_CHAT_CHANNEL = MinecraftChannelIdentifier
                        .from("minewar:globalchat");
        public static final MinecraftChannelIdentifier SYNC_CHANNEL = MinecraftChannelIdentifier
                        .from("minewar:sync");
        public static final MinecraftChannelIdentifier FAKE_PLAYER_CHANNEL = MinecraftChannelIdentifier
                        .from("minewar:fakeplayer");

        @Inject
        public MinewarVelocity(ProxyServer server, Logger logger, ConfigManager configManager,
                        DataManager dataManager, DiscordManager discordManager, VanishManager vanishManager,
                        MessageManager messageManager, DatabaseManager databaseManager,
                        com.example.seen.config.SmartConfig smartConfig,
                        FakePlayerRegistry fakePlayerRegistry) {
                this.server = server;
                this.logger = logger;
                this.configManager = configManager;
                this.dataManager = dataManager;
                this.discordManager = discordManager;
                this.vanishManager = vanishManager;
                this.messageManager = messageManager;
                this.databaseManager = databaseManager;
                this.smartConfig = smartConfig;
                this.fakePlayerRegistry = fakePlayerRegistry;
        }

        @Subscribe
        public void onProxyInitialization(ProxyInitializeEvent event) {
                // 1. Config First
                smartConfig.load();
                configManager.load();

                // 2. Database Initialization
                databaseManager.init();

                // 3. Data Third (now uses DB)
                dataManager.load();

                // 3. Integrations Third (uses config)
                discordManager.load();

                // 4. Register Channels
                server.getChannelRegistrar().register(STAFF_CHAT_CHANNEL, VANISH_CHANNEL, GLOBAL_CHAT_CHANNEL,
                                SYNC_CHANNEL, FAKE_PLAYER_CHANNEL);

                // Initialize MessageUtils
                MessageUtils.initPapi();
                MessageUtils.setDiscordManager(discordManager);
                MessageUtils.setConfigManager(configManager);

                // Register Commands with New Alias System
                com.velocitypowered.api.command.CommandManager commandManager = server.getCommandManager();

                // Helper to get aliases
                java.util.function.Function<String, String[]> getAliases = (cmdName) -> {
                        Section commandsSection = smartConfig.getConfig().getSection("Commands");
                        if (commandsSection == null)
                                return new String[0];
                        Section cmdSection = commandsSection.getSection(cmdName);
                        if (cmdSection == null || !cmdSection.getBoolean("enabled", true))
                                return new String[0];
                        List<String> aliasList = cmdSection.getStringList("aliases");
                        return aliasList != null ? aliasList.toArray(new String[0]) : new String[0];
                };

                // Helper to check disabled
                java.util.function.Predicate<String> isEnabled = (cmdName) -> {
                        Section commandsSection = smartConfig.getConfig().getSection("Commands");
                        if (commandsSection == null)
                                return true; // Default enabled
                        Section cmdSection = commandsSection.getSection(cmdName);
                        return cmdSection == null || cmdSection.getBoolean("enabled", true);
                };

                if (isEnabled.test("alert")) {
                        commandManager.register(
                                        commandManager.metaBuilder("alert").aliases(getAliases.apply("alert")).build(),
                                        new AlertCommand(server, smartConfig, discordManager));
                }

                if (isEnabled.test("discord")) {
                        commandManager.register(
                                        commandManager.metaBuilder("discord").aliases(getAliases.apply("discord"))
                                                        .build(),
                                        new DiscordCommand(discordManager, configManager));
                }

                if (isEnabled.test("balert")) {
                        commandManager.register(
                                        commandManager.metaBuilder("balert").aliases(getAliases.apply("balert"))
                                                        .build(),
                                        new com.example.seen.command.BALertCommand(discordManager, configManager));
                }

                if (isEnabled.test("find")) {
                        commandManager.register(
                                        commandManager.metaBuilder("find").aliases(getAliases.apply("find")).build(),
                                        new FindCommand(server, configManager, dataManager));
                }

                if (isEnabled.test("firstjoin")) {
                        commandManager.register(
                                        commandManager.metaBuilder("firstjoin").aliases(getAliases.apply("firstjoin"))
                                                        .build(),
                                        new FirstJoinCommand(server, dataManager, configManager));
                }

                if (isEnabled.test("ignore")) {
                        commandManager.register(
                                        commandManager.metaBuilder("ignore").aliases(getAliases.apply("ignore"))
                                                        .build(),
                                        new IgnoreCommand(server, dataManager, configManager));
                }

                if (isEnabled.test("minewarvelocity")) {
                        commandManager.register(
                                        commandManager.metaBuilder("minewarvelocity")
                                                        .aliases(getAliases.apply("minewarvelocity")).build(),
                                        new MinewarVelocityCommand(configManager, discordManager, this,
                                                        dataManager, server));
                }

                // Msg (RawCommand for signature bypass)
                if (isEnabled.test("msg")) {
                        MsgCommand msgCmd = new MsgCommand(server, messageManager, configManager,
                                        vanishManager, fakePlayerRegistry);

                        String[] configAliases = getAliases.apply("msg");
                        List<String> combinedMsg = new ArrayList<>(Arrays.asList(configAliases));
                        // Force standard aliases
                        for (String s : new String[] { "tell", "w", "message" }) {
                                if (!combinedMsg.contains(s))
                                        combinedMsg.add(s);
                        }

                        commandManager.register(
                                        commandManager.metaBuilder("msg")
                                                        .aliases(combinedMsg.toArray(new String[0]))
                                                        .build(),
                                        msgCmd);
                }

                if (isEnabled.test("adminmessage")) {
                        commandManager.register(
                                        commandManager.metaBuilder("adminmessage")
                                                        .aliases(getAliases.apply("adminmessage")).build(),
                                        new AdminMessageCommand(server, configManager, messageManager, vanishManager));
                }

                if (isEnabled.test("msgtoggle")) {
                        commandManager.register(
                                        commandManager.metaBuilder("msgtoggle").aliases(getAliases.apply("msgtoggle"))
                                                        .build(),
                                        new MsgToggleCommand(dataManager, configManager));
                }

                // Reply (RawCommand for signature bypass)
                if (isEnabled.test("reply")) {
                        ReplyCommand replyCmd = new ReplyCommand(server, messageManager,
                                        configManager);

                        String[] replyAliases = getAliases.apply("reply");
                        List<String> combinedReply = new ArrayList<>(Arrays.asList(replyAliases));
                        if (!combinedReply.contains("r"))
                                combinedReply.add("r");

                        commandManager.register(
                                        commandManager.metaBuilder("reply")
                                                        .aliases(combinedReply.toArray(new String[0]))
                                                        .build(),
                                        replyCmd);
                }

                // AdminReply (bypasses msg_enabled and ignore list)
                if (isEnabled.test("adminreply")) {
                        AdminReplyCommand adminReplyCmd = new AdminReplyCommand(server, messageManager,
                                        configManager, dataManager);
                        commandManager.register(
                                        commandManager.metaBuilder("adminreply").aliases(getAliases.apply("adminreply"))
                                                        .build(),
                                        adminReplyCmd);
                }

                if (isEnabled.test("seen")) {
                        commandManager.register(
                                        commandManager.metaBuilder("seen").aliases(getAliases.apply("seen")).build(),
                                        new SeenCommand(server, dataManager, configManager));
                }

                if (isEnabled.test("socialspy")) {
                        commandManager.register(
                                        commandManager.metaBuilder("socialspy").aliases(getAliases.apply("socialspy"))
                                                        .build(),
                                        new SocialSpyCommand(dataManager, configManager));
                }

                // StaffChat
                if (isEnabled.test("staffchat")) {
                        StaffChatCommand scCmd = new StaffChatCommand(this, server, dataManager, configManager);
                        commandManager.register(commandManager.metaBuilder("staffchat")
                                        .aliases(getAliases.apply("staffchat")).build(), scCmd);
                }

                if (isEnabled.test("unignore")) {
                        commandManager.register(
                                        commandManager.metaBuilder("unignore").aliases(getAliases.apply("unignore"))
                                                        .build(),
                                        new UnignoreCommand(server, dataManager, configManager));
                }

                // Vanish
                if (isEnabled.test("vanish")) {
                        VanishCommand vanishCmd = new VanishCommand(server, configManager, dataManager, vanishManager);
                        commandManager.register(commandManager.metaBuilder("vanish").aliases(getAliases.apply("vanish"))
                                        .build(), vanishCmd);
                }

                if (isEnabled.test("whois")) {
                        commandManager.register(
                                        commandManager.metaBuilder("whois").aliases(getAliases.apply("whois")).build(),
                                        new WhoisCommand(discordManager, configManager, dataManager, server));
                }

                if (isEnabled.test("list")) {
                        commandManager.register(
                                        commandManager.metaBuilder("list").aliases(getAliases.apply("list")).build(),
                                        new ListCommand(server, smartConfig, dataManager, vanishManager));
                }

                if (isEnabled.test("proxy_restart")) {
                        commandManager.register(
                                        commandManager.metaBuilder("proxyrestart")
                                                        .aliases(getAliases.apply("proxy_restart")).build(),
                                        new ProxyRestartCommand(server, smartConfig, configManager, this));
                        commandManager.register(
                                        commandManager.metaBuilder("proxyrestartcancel").build(),
                                        new ProxyRestartCancelCommand(server, smartConfig, configManager));
                }

                if (isEnabled.test("server_command")) {
                        commandManager.register(
                                        commandManager.metaBuilder("server").aliases(getAliases.apply("server_command"))
                                                        .build(),
                                        new ServerCommand(server, smartConfig));
                }

                // Register SlashServer commands
                Section SLASHSERVER_SECTION = smartConfig.getConfig().getSection("slashserver");
                if (SLASHSERVER_SECTION != null) {
                        for (Object keyObj : SLASHSERVER_SECTION.getKeys()) {
                                String alias = keyObj.toString();
                                String targetServer = SLASHSERVER_SECTION.getString(alias);
                                commandManager.register(commandManager.metaBuilder(alias).build(),
                                                new SlashServerCommand(server, targetServer, smartConfig));
                        }
                }

                /*
                 * // Register PAPI Placeholder %minewar_total% via PAPIProxyBridge if available
                 * if (server.getPluginManager().isLoaded("papiproxybridge")) {
                 * try {
                 * // API Method mismatch for registration on this version.
                 * // Needs manual verification of PAPIProxyBridge API.
                 * // net.william278.papiproxybridge.api.PlaceholderAPI.getInstance().register(
                 * // "minewar_total",
                 * // (uuid, args) ->
                 * String.valueOf(vanishManager.getVisibleCount(java.util.Optional.empty()))
                 * // );
                 * } catch (Throwable t) {
                 * logger.warn("Failed to register PAPIProxyBridge placeholder: " +
                 * t.getMessage());
                 * }
                 * }
                 */

                // Register Listeners
                com.velocitypowered.api.event.EventManager eventManager = server.getEventManager();
                eventManager.register(this, new JoinListener(server, dataManager, configManager, vanishManager));
                eventManager.register(this, new SahorahResponseListener(messageManager, server));
                eventManager.register(this, new ChatListener(server, dataManager, discordManager));
                eventManager.register(this, new GlobalChatListener(server, dataManager, configManager, discordManager));
                eventManager.register(this, new ServerListPingListener(vanishManager, smartConfig));

                this.commandFilterListener = new CommandFilterListener(
                                smartConfig.getCommandWhitelist(),
                                smartConfig.getWhitelistBypassPermission(),
                                smartConfig.isCommandWhitelistEnabled(),
                                server,
                                dataManager,
                                smartConfig,
                                fakePlayerRegistry);
                eventManager.register(this, commandFilterListener);
                eventManager.register(this, new PluginMessageBridge(vanishManager, commandFilterListener));
                eventManager.register(this, new VanishMessageListener(dataManager));
                this.fakePlayerMessageHandler = new FakePlayerMessageHandler(fakePlayerRegistry, server, configManager,
                                logger);
                eventManager.register(this, fakePlayerMessageHandler);

                // LuckPerms Listener (Self-registering)
                new LuckPermsListener(discordManager, server, configManager).register();

                // Register Velocitab vanish integration if Velocitab is present
                if (server.getPluginManager().getPlugin("velocitab").isPresent()) {
                        try {
                                net.william278.velocitab.api.VelocitabAPI velocitabAPI = net.william278.velocitab.api.VelocitabAPI
                                                .getInstance();
                                MinewarVanishIntegration vanishIntegration = new MinewarVanishIntegration(
                                                server, dataManager, vanishManager);
                                velocitabAPI.setVanishIntegration(vanishIntegration);
                                logger.info("Velocitab integration registered successfully!");
                        } catch (Exception e) {
                                logger.warn("Failed to register Velocitab integration: " + e.getMessage());
                        }
                } else {
                        logger.info("Velocitab not found, vanish tab integration disabled.");
                }

                // Start Player Count Broadcast Task (Every 10s)
                server.getScheduler().buildTask(this, () -> {
                        broadcastCounts();
                }).repeat(java.time.Duration.ofSeconds(10)).schedule();

                // Initial whitelist broadcast to all connected backends
                broadcastWhitelist();

                // Register MiniPlaceholders Expansion
                new com.example.seen.placeholders.MinewarExpansion(vanishManager, configManager, dataManager)
                                .register();

                logger.info("Loaded plugin minewarvelocity 1.0-SNAPSHOT by Jxsh");
                logger.info("MinewarVelocity loaded successfully!");
        }

        private void broadcastCounts() {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("BulkCount");

                // Global Total
                int globalTotal = vanishManager.getVisibleCount(java.util.Optional.empty());
                out.writeInt(globalTotal);

                // Server Counts
                java.util.List<com.velocitypowered.api.proxy.server.RegisteredServer> servers = new java.util.ArrayList<>(
                                server.getAllServers());
                out.writeInt(servers.size());

                for (com.velocitypowered.api.proxy.server.RegisteredServer srv : servers) {
                        out.writeUTF(srv.getServerInfo().getName());
                        out.writeInt(vanishManager
                                        .getVisibleCount(java.util.Optional.of(srv.getServerInfo().getName())));
                }

                byte[] data = out.toByteArray();
                for (com.velocitypowered.api.proxy.server.RegisteredServer srv : server.getAllServers()) {
                        if (!srv.getPlayersConnected().isEmpty()) {
                                srv.sendPluginMessage(SYNC_CHANNEL, data);
                        }
                }
        }

        public void syncStaffChat(Player player, boolean toggled) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Toggle");
                out.writeUTF(player.getUniqueId().toString());
                out.writeBoolean(toggled);

                player.getCurrentServer().ifPresent(server -> {
                        server.sendPluginMessage(STAFF_CHAT_CHANNEL, out.toByteArray());
                });
        }

        @Subscribe
        public void onServerPostConnect(ServerPostConnectEvent event) {
                Player player = event.getPlayer();
                if (dataManager.isVanished(player.getUniqueId())) {
                        if (server.getPluginManager().getPlugin("velocitab").isPresent()) {
                                try {
                                        net.william278.velocitab.api.VelocitabAPI.getInstance().vanishPlayer(player);
                                } catch (Exception e) {
                                        logger.warn("Failed to vanish player in Velocitab: " + e.getMessage());
                                }
                        }
                }

                // Send the master whitelist to the backend server the player just connected to
                player.getCurrentServer().ifPresent(serverConn -> {
                        byte[] data = buildWhitelistPacket();
                        if (data != null) {
                                serverConn.sendPluginMessage(SYNC_CHANNEL, data);
                        }
                });

        }

        @Subscribe
        public void onProxyShutdown(ProxyShutdownEvent event) {
                dataManager.saveAll();

                discordManager.save();
                discordManager.shutdown();
        }

        // ── Whitelist Sync ──

        /**
         * Builds the serialized WhitelistSync plugin message packet.
         */
        private byte[] buildWhitelistPacket() {
                java.util.List<String> whitelist = smartConfig.getCommandWhitelist();
                boolean enabled = smartConfig.isCommandWhitelistEnabled();
                boolean perPermission = smartConfig.isPerPermissionEnabled();
                String botUsername = smartConfig.getConfig().getString("bot.username", "Sahorah");
                String bypassPerm = smartConfig.getWhitelistBypassPermission();

                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("WhitelistSync");
                out.writeBoolean(enabled);
                out.writeBoolean(perPermission);
                out.writeUTF(botUsername);
                out.writeInt(whitelist.size());
                for (String cmd : whitelist) {
                        out.writeUTF(cmd);
                }
                out.writeUTF(bypassPerm);
                return out.toByteArray();
        }

        /**
         * Broadcasts the master whitelist to ALL connected backend servers.
         * Called on init, after config reload, and on ServerPostConnect.
         */
        public void broadcastWhitelist() {
                byte[] data = buildWhitelistPacket();
                if (data == null)
                        return;

                for (com.velocitypowered.api.proxy.server.RegisteredServer srv : server.getAllServers()) {
                        if (!srv.getPlayersConnected().isEmpty()) {
                                srv.sendPluginMessage(SYNC_CHANNEL, data);
                        }
                }
                logger.info("Broadcast WhitelistSync to all backends (" + smartConfig.getCommandWhitelist().size()
                                + " commands)");
        }

        /**
         * Called after a config reload to update the in-memory filter and broadcast.
         */
        public void reloadWhitelist() {
                if (commandFilterListener != null) {
                        commandFilterListener.updateWhitelist(
                                        smartConfig.getCommandWhitelist(),
                                        smartConfig.isCommandWhitelistEnabled());
                }
                broadcastWhitelist();
        }

        public void reload() {
                // Reload config + messages synchronously (merges new JAR keys, keeps user
                // values)
                smartConfig.reload();
                // Reconnect DB with the freshly-loaded config
                databaseManager.reconnect();
                // Discord reload
                discordManager.reload();
                // Sync whitelist to backends
                reloadWhitelist();
                if (fakePlayerMessageHandler != null) {
                        fakePlayerMessageHandler.refreshAll();
                }
                logger.info("MinewarVelocity reloaded successfully.");
        }

        public com.example.seen.config.SmartConfig getSmartConfig() {
                return smartConfig;
        }

        public FakePlayerMessageHandler getFakePlayerMessageHandler() {
                return fakePlayerMessageHandler;
        }
}