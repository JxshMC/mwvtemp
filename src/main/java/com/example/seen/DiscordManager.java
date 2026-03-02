package com.example.seen;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.List;
import java.util.UUID;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.HashMap;
import java.awt.Color;
import javax.annotation.Nonnull;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.Message;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.EmbedBuilder;

@Singleton
public class DiscordManager extends ListenerAdapter {
    private final ConfigManager configManager;
    private final ProxyServer server;
    private JDA jda;
    private final DataManager dataManager;
    private final Map<String, String> groupRoleMap = new HashMap<>(); // LuckPerms Group -> Discord Role ID

    @Inject
    public DiscordManager(ConfigManager configManager,
            ProxyServer server,
            DataManager dataManager) {
        this.configManager = configManager;
        this.server = server;
        this.dataManager = dataManager;
    }

    public synchronized void load() {
        if (jda != null) {
            shutdown();
        }

        String token = configManager.getConfigString("discord.token");
        if (token == null || token.equals("YOUR_DISCORD_BOT_TOKEN_HERE") || token.isEmpty()) {
            return;
        }

        try {
            String botStatus = configManager.getConfigString("discord.BOT_STATUS", "Watching Minewar");
            String visibility = configManager.getConfigString("discord.Visibility", "ONLINE").toUpperCase();

            net.dv8tion.jda.api.OnlineStatus onlineStatus;
            try {
                onlineStatus = net.dv8tion.jda.api.OnlineStatus.valueOf(visibility);
            } catch (IllegalArgumentException e) {
                onlineStatus = net.dv8tion.jda.api.OnlineStatus.ONLINE;
            }

            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .setStatus(onlineStatus)
                    .setActivity(
                            net.dv8tion.jda.api.entities.Activity.watching(botStatus != null ? botStatus : "Minewar"))
                    .addEventListeners(this)
                    .build();

            String guildId = configManager.getConfigString("discord.guild_id");
            if (guildId != null && !guildId.isEmpty()) {
                jda.awaitReady(); // Ensure JDA is ready to update commands
                Guild guild = jda.getGuildById(guildId);
                if (guild != null) {
                    guild.updateCommands().addCommands(
                            Commands.slash("link", "Link your Minecraft account using the code from in-game")
                                    .addOption(OptionType.STRING, "code", "The 6-character code from /discord link",
                                            true),
                            Commands.slash("userinfo", "Get Minecraft info for a Discord user")
                                    .addOption(OptionType.USER, "user", "The Discord user to lookup", true),
                            Commands.slash("alert", "Post an alert embed to a specific channel")
                                    .addOption(OptionType.STRING, "channel_id", "The Channel ID to post to", true)
                                    .addOption(OptionType.STRING, "message", "The message to post", true))
                            .queue();
                }
            }

            // Load Role Mapping
            loadRoleMappings();

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void save() {
        // SQL updates happen in setters. No bulk save needed for DB.
    }

    private void loadRoleMappings() {
        groupRoleMap.clear();
        Section section = configManager.getConfig().getSection("discord.role-sync.mappings");
        if (section != null) {
            for (Object key : section.getKeys()) {
                String group = key.toString();
                String roleId = section.getString(group);
                if (roleId != null) {
                    groupRoleMap.put(group, roleId);
                }
            }
        }
    }

    public void updateUserRoles(String discordId, String primaryGroup) {
        if (jda == null)
            return;

        String guildId = configManager.getConfigString("discord.guild_id");
        if (guildId == null || guildId.isEmpty())
            return;

        Guild guild = jda.getGuildById(guildId);
        if (guild == null)
            return;

        String roleId = groupRoleMap.get(primaryGroup);
        if (roleId == null || roleId.isEmpty())
            return;

        guild.retrieveMemberById(discordId).queue(member -> {
            Role targetRole = guild.getRoleById(roleId);
            if (targetRole == null)
                return;

            // Only add if they don't have it
            if (!member.getRoles().contains(targetRole)) {
                guild.addRoleToMember(member, targetRole).queue();
            }
        }, throwable -> {
            // Member not found or error
        });
    }

    public boolean isLinked(UUID uuid) {
        return dataManager.getDiscordId(uuid) != null;
    }

    public String getDiscordId(UUID uuid) {
        return dataManager.getDiscordId(uuid);
    }

    public synchronized void shutdown() {
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
    }

    public void reload() {
        shutdown();
        load();
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public CompletableFuture<Map<UUID, String>> getLinkedUsers() {
        return dataManager.getLinkedUsers();
    }

    public boolean sendAlertToChannel(String senderName, String message, String channelId) {
        if (jda == null || channelId == null || jda.getStatus() != JDA.Status.CONNECTED)
            return false;

        net.dv8tion.jda.api.entities.channel.concrete.TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null)
            return false;

        String colorHex = configManager.getMessage("discord-embed.color");
        if (colorHex == null || colorHex.isEmpty())
            colorHex = "#0adef7";
        String author = configManager.getMessage("discord-embed.author");
        String footer = configManager.getMessage("discord-embed.footer");
        if (footer == null)
            footer = "Minewar Alert • %time%";

        // Simple time/date placeholders
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String date = java.time.LocalDate.now().toString();

        footer = footer.replace("%time%", time).replace("%date%", date).replace("%sender%", senderName);
        message = message.replace("%time%", time).replace("%date%", date);

        EmbedBuilder eb = new EmbedBuilder();
        eb.setDescription(message);
        eb.setColor(Color.decode(colorHex.startsWith("#") ? colorHex : "#" + colorHex));
        if (author != null && !author.isEmpty())
            eb.setAuthor(author);
        eb.setFooter(footer);

        channel.sendMessageEmbeds(eb.build()).queue();
        return true;
    }

    public void sendToDiscord(Player player, String message) {
        if (jda == null || !configManager.isModuleEnabled("discord-sync"))
            return;

        if (dataManager.isVanished(player.getUniqueId()))
            return;

        UUID uuid = player.getUniqueId();
        // Use DataManager directly (has cached LuckPerms data)
        String prefix = dataManager.getPrefix(uuid);

        // Resolve Color from Suffix (MiniMessage or Legacy)
        Color embedColor = Color.decode("#0adef7"); // Default

        String plainPrefix = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .stripTags(prefix != null ? prefix : "");
        String authorName = "[" + plainPrefix + "] " + player.getUsername();
        String avatarUrl = "https://mc-heads.net/avatar/" + uuid;

        EmbedBuilder eb = new EmbedBuilder();
        eb.setAuthor(authorName, null, avatarUrl);
        eb.setThumbnail(avatarUrl);
        eb.setDescription(message);
        eb.setColor(embedColor);

        String syncChannelId = configManager.getConfigString("discord.sync-channel-id");
        if (syncChannelId != null) {
            net.dv8tion.jda.api.entities.channel.concrete.TextChannel channel = jda.getTextChannelById(syncChannelId);
            if (channel != null) {
                channel.sendMessageEmbeds(eb.build()).queue();
            }
        }
    }

    public String getDiscordTag(String discordId) {
        if (jda == null)
            return null;
        try {
            net.dv8tion.jda.api.entities.User user = jda.getUserById(discordId != null ? discordId : "");
            if (user != null)
                return user.getName();

            String guildId = configManager.getConfigString("discord.guild_id");
            if (guildId != null && !guildId.isEmpty()) {
                Guild guild = jda.getGuildById(guildId);
                if (guild != null && discordId != null) {
                    Member member = guild.getMemberById(discordId);
                    if (member != null)
                        return member.getUser().getName();
                }
            }
        } catch (Exception ignored) {
        }
        return discordId;
    }

    public void unlink(UUID uuid) {
        dataManager.unlinkAccount(uuid);
    }

    public String generateLinkCode(UUID playerUuid) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        String code = sb.toString();
        dataManager.createLinkToken(playerUuid, code);
        return code;
    }

    @Override
    public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
        if (event.getAuthor().isBot())
            return;
        if (!event.isFromGuild())
            return;

        String syncChannelId = configManager.getConfigString("discord.sync-channel-id");
        if (syncChannelId == null || !event.getChannel().getId().equals(syncChannelId))
            return;

        String discordId = event.getAuthor().getId();
        UUID uuid = dataManager.getUUIDByDiscordId(discordId);

        if (uuid == null) {
            String warning = configManager.getMessage("discord-unlinked-warning");
            if (warning == null || warning.isEmpty()) {
                warning = "Your Discord account is not linked. Use /discord link in-game.";
            }
            event.getMessage().reply(warning.replaceAll("<[^>]*>", "")).queue();
            return;
        }

        // Use DataManager (has cached LuckPerms data)
        String prefix = dataManager.getPrefix(uuid);
        String name = dataManager.getName(uuid);
        if (name == null)
            name = event.getAuthor().getName();

        // Format from config
        String formatCode = configManager.getMessage("discord-to-mc-format");
        if (formatCode == null)
            formatCode = "<blue>[Discord]</blue> %suffix%%player%: %message%";

        String format = formatCode.replace("%suffix%", prefix != null ? prefix : "")
                .replace("%player%", name)
                .replace("%message%", event.getMessage().getContentDisplay());

        net.kyori.adventure.text.Component component = MessageUtils.renderMessage(format);
        server.getAllPlayers().forEach(p -> p.sendMessage(component));
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        String cmd = event.getName();

        if (cmd.equals("link")) {
            event.deferReply(true).queue();
            OptionMapping option = event.getOption("code");
            if (option == null) {
                event.getHook().sendMessage(configManager.getMessage("discord.link-missing-code")).queue();
                return;
            }
            String code = option.getAsString().toUpperCase();
            UUID mcUuid = dataManager.validateLinkToken(code);

            if (mcUuid == null) {
                event.getHook().sendMessage(configManager.getMessage("discord.link-invalid-code")).queue();
                return;
            }

            dataManager.linkAccount(mcUuid, event.getUser().getId());
            event.getHook().sendMessage(configManager.getMessage("discord.link-success")).queue();
        } else if (cmd.equals("alert")) {
            event.deferReply(true).queue();

            // Check Permissions
            Member member = event.getMember();
            if (member == null) {
                event.getHook().sendMessage(configManager.getMessage("discord.member-not-found")).queue();
                return;
            }

            List<String> allowedRoles = configManager.getConfig().getStringList("discord.permissions.alert-roles");

            boolean hasPermission = false;
            if (allowedRoles != null) {
                for (String roleId : allowedRoles) {
                    if (member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId))) {
                        hasPermission = true;
                        break;
                    }
                }
            }

            if (!hasPermission) {
                event.getHook().sendMessage(configManager.getMessage("discord.no-permission-discord")).queue();
                return;
            }

            OptionMapping msgOpt = event.getOption("message");
            OptionMapping chanOpt = event.getOption("channel_id");

            if (msgOpt == null || chanOpt == null) {
                event.getHook().sendMessage(configManager.getMessage("discord.alert-missing-fields")).queue();
                return;
            }

            String message = msgOpt.getAsString();
            String channelId = chanOpt.getAsString();

            // Send to Discord Channel ONLY
            boolean sent = sendAlertToChannel(member.getUser().getName(), message, channelId);

            if (sent) {
                event.getHook()
                        .sendMessage(configManager.getMessage("discord.alert-success").replace("%channel%", channelId))
                        .queue();
            } else {
                event.getHook().sendMessage(configManager.getMessage("discord.alert-fail")).queue();
            }

        } else if (cmd.equals("userinfo")) {
            event.deferReply(true).queue();
            OptionMapping userOpt = event.getOption("user");
            if (userOpt == null) {
                event.getHook().sendMessage(configManager.getMessage("discord.user-not-found")).queue();
                return;
            }
            net.dv8tion.jda.api.entities.User discordUser = userOpt.getAsUser();
            UUID mcUuid = dataManager.getUUIDByDiscordId(discordUser.getId());

            if (mcUuid == null) {
                event.getHook().sendMessage(configManager.getMessage("discord.not-linked")).queue();
                return;
            }

            String mcName = dataManager.getName(mcUuid);
            if (mcName == null)
                mcName = "Unknown";

            String joinIndex = dataManager.getJoinIndexFormatted(mcUuid);
            String prefix = dataManager.getPrefix(mcUuid);

            EmbedBuilder eb = new EmbedBuilder();
            eb.setTitle("Linked Account Info");
            eb.addField("Discord", discordUser.getName(), true);
            eb.addField("Minecraft Name", mcName, true);
            eb.addField("Join Index", joinIndex, true);
            eb.addField("Rank (Prefix)",
                    prefix != null && !prefix.isEmpty() ? prefix.replaceAll("<[^>]*>", "") : "None", true);
            eb.addField("UUID", mcUuid.toString(), false);
            eb.setThumbnail("https://visage.surgeplay.com/head/64/" + mcUuid);
            eb.setColor(java.awt.Color.CYAN);

            event.getHook().sendMessageEmbeds(eb.build()).queue();
        }
    }
}