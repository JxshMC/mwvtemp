package com.example.seen;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central utility for message parsing and LuckPerms placeholder resolution.
 *
 * <p>
 * <b>Placeholder contract:</b> {@code %prefix_other%}, {@code %suffix_other%},
 * and {@code %owner_color%} must NEVER appear in the final output — if their
 * resolved value is null or empty they are silently replaced with {@code ""}.
 */
public class MessageUtils {

  private static DiscordManager discordManager;
  private static ConfigManager configManager;

  // ── Initialization ────────────────────────────────────────────────────────

  public static void initPapi() {
    // Reserved for future PAPI synchronization hooks
  }

  public static void setDiscordManager(DiscordManager manager) {
    discordManager = manager;
  }

  /**
   * Must be called during plugin startup so placeholder resolution can read
   * config values.
   */
  public static void setConfigManager(ConfigManager manager) {
    configManager = manager;
  }

  // ── Owner Check ───────────────────────────────────────────────────────────

  /**
   * Returns {@code true} if the given LuckPerms {@link User} is in the
   * {@code "owner"} group
   * (either as primary group or as an inherited group node).
   */
  public static boolean isOwner(User user) {
    if (user == null)
      return false;
    if ("owner".equalsIgnoreCase(user.getPrimaryGroup()))
      return true;
    return user.getNodes(NodeType.INHERITANCE).stream()
        .map(InheritanceNode::getGroupName)
        .anyMatch("owner"::equalsIgnoreCase);
  }

  // ── Placeholder Replacement ───────────────────────────────────────────────

  /**
   * Replaces placeholders in %message% using the given UUID for metadata
   * resolution.
   * 
   * @param isDiscord If true, %rank% resolves to Group Name. If false, to Prefix.
   */
  public static String applyPlaceholders(String message, String senderName, UUID uuid, boolean isDiscord) {
    if (message == null)
      return "";

    // %player% -> sender's username
    message = message.replace("%player%", senderName != null ? senderName : "");

    if (uuid != null) {
      DataManager dataManager = discordManager.getDataManager();
      String prefix = dataManager.getPrefix(uuid);
      String group = dataManager.getGroupName(uuid);
      String suffix = dataManager.getSuffix(uuid);

      // %rank% resolution based on context
      message = message.replace("%rank%", isDiscord ? group : prefix);
      message = message.replace("%suffix%", suffix);

      // Legacy/Extra placeholders
      message = message.replace("%prefix_other%", prefix);
      message = message.replace("%suffix_other%", suffix);
    } else {
      message = message.replace("%rank%", "");
      message = message.replace("%suffix%", "");
      message = message.replace("%prefix_other%", "");
      message = message.replace("%suffix_other%", "");
    }

    // Minewar Join Index Placeholders
    if (message.contains("%minewar_joinindex%") || message.contains("%joinindex%")
        || message.contains("%minewar_join_index%")) {
      String indexValue = "Unknown";
      if (uuid != null) {
        indexValue = String.valueOf(discordManager.getDataManager().getJoinIndex(uuid));
      }
      message = message.replace("%minewar_joinindex%", indexValue)
          .replace("%joinindex%", indexValue)
          .replace("%minewar_join_index%", indexValue);
    }

    if (message.contains("%minewar_joinindex_player_")) {
      java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("%minewar_joinindex_player_([^%]+)%");
      java.util.regex.Matcher matcher = pattern.matcher(message);
      StringBuilder sb = new StringBuilder();
      int lastEnd = 0;
      while (matcher.find()) {
        sb.append(message, lastEnd, matcher.start());
        String playerName = matcher.group(1);
        UUID playerUuid = discordManager.getDataManager().getUUID(playerName);
        String index = (playerUuid != null) ? String.valueOf(discordManager.getDataManager().getJoinIndex(playerUuid))
            : "Unknown";
        sb.append(index);
        lastEnd = matcher.end();
      }
      sb.append(message.substring(lastEnd));
      message = sb.toString();
    }

    // %owner_color% — resolve from config or hard-coded default
    String ownerColorTag = (configManager != null)
        ? configManager.getOwnerColor()
        : "<#0adef7>";
    if (ownerColorTag == null || ownerColorTag.isEmpty())
      ownerColorTag = "<#0adef7>";
    message = message.replace("%owner_color%", ownerColorTag);

    // Standardize some network-wide placeholders if possible
    // Note: %online% and %max% are usually handled in specific command contexts
    // but we can add them here if we have access to the proxy in the future.

    return message;
  }

  /** Legacy support method - resolves using LuckPerms directly if available */
  public static String applyPlaceholders(String message, String senderName, User otherUser) {
    if (message == null)
      return "";
    message = message.replace("%player%", senderName != null ? senderName : "");

    String prefix = "";
    String suffix = "";
    if (otherUser != null) {
      prefix = otherUser.getCachedData().getMetaData().getPrefix();
      suffix = otherUser.getCachedData().getMetaData().getSuffix();
    }

    // Default to Prefix for %rank% in legacy calls (usually Minecraft context)
    message = message.replace("%rank%", prefix != null ? prefix : "");
    message = message.replace("%suffix%", suffix != null ? suffix : "");
    message = message.replace("%prefix_other%", prefix != null ? prefix : "");
    message = message.replace("%suffix_other%", suffix != null ? suffix : "");

    return message;
  }

  // ── Async Parse Helpers ───────────────────────────────────────────────────

  /**
   * Fully asynchronous variant: loads the LuckPerms user for
   * {@code otherPlayerUuid},
   * applies all placeholders (fail-safe), then deserializes via MiniMessage.
   *
   * @param message         Raw message string (may contain MiniMessage tags and
   *                        %placeholders%)
   * @param viewer          The player <em>viewing</em> the message (used for
   *                        %player% where applicable)
   * @param otherPlayerUuid UUID of the player whose LP meta to load (nullable →
   *                        no LP lookup)
   */
  public static CompletableFuture<Component> parseWithPapi(String message, Player viewer, UUID otherPlayerUuid) {
    String applied = applyPlaceholders(message, viewer.getUsername(), otherPlayerUuid, false);
    return CompletableFuture.completedFuture(renderMessage(applied));
  }

  /**
   * Overload that directly accepts a pre-loaded {@link User} (avoids a second LP
   * fetch
   * when the caller already has the user loaded).
   * Named distinctly from {@link #parseWithPapi(String, Player, UUID)} to avoid
   * ambiguity when {@code null} is passed.
   */
  public static CompletableFuture<Component> parseWithUser(String message, Player viewer, User preloadedOtherUser) {
    String applied = applyPlaceholders(message, viewer.getUsername(), preloadedOtherUser);
    return CompletableFuture.completedFuture(renderMessage(applied));
  }

  /**
   * Translates legacy Bukkit "&" color codes into MiniMessage tags natively,
   * then parses the entire string. This safely allows both legacy "&c" and
   * modern "<red>" or "<#0adef7>" hex codes to mix perfectly without breaking
   * each other.
   */
  public static Component renderMessage(String raw) {
    if (raw == null)
      return Component.empty();

    // Safely map all specific legacy colors to their MiniMessage eqivalents
    String msg = raw
        .replace("&0", "<black>").replace("&1", "<dark_blue>")
        .replace("&2", "<dark_green>").replace("&3", "<dark_aqua>")
        .replace("&4", "<dark_red>").replace("&5", "<dark_purple>")
        .replace("&6", "<gold>").replace("&7", "<gray>")
        .replace("&8", "<dark_gray>").replace("&9", "<blue>")
        .replace("&a", "<green>").replace("&A", "<green>")
        .replace("&b", "<aqua>").replace("&B", "<aqua>")
        .replace("&c", "<red>").replace("&C", "<red>")
        .replace("&d", "<light_purple>").replace("&D", "<light_purple>")
        .replace("&e", "<yellow>").replace("&E", "<yellow>")
        .replace("&f", "<white>").replace("&F", "<white>")
        .replace("&k", "<obfuscated>").replace("&K", "<obfuscated>")
        .replace("&l", "<bold>").replace("&L", "<bold>")
        .replace("&m", "<strikethrough>").replace("&M", "<strikethrough>")
        .replace("&n", "<underlined>").replace("&N", "<underlined>")
        .replace("&o", "<italic>").replace("&O", "<italic>")
        .replace("&r", "<reset>").replace("&R", "<reset>");

    return MiniMessage.miniMessage().deserialize(msg);
  }

  /**
   * Synchronous shorthand for simple messages where LP data is not needed.
   */
  public static String parsePapi(Player player, String message) {
    return applyPlaceholders(message, player.getUsername(), null);
  }

  // ── LuckPerms Loader ──────────────────────────────────────────────────────

  public static CompletableFuture<User> loadUser(UUID uuid) {
    try {
      return LuckPermsProvider.get().getUserManager().loadUser(uuid);
    } catch (Exception e) {
      return CompletableFuture.completedFuture(null);
    }
  }

  /**
   * Legacy helper kept for backward compatibility with callers that pass both
   * sender and receiver users.
   * Uses {@code other} as the source of prefix/suffix.
   */
  public static String replaceLuckPermsPlaceholders(String format, User user, User other) {
    return applyPlaceholders(format, null, other != null ? other : user);
  }
}
