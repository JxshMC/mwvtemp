# Minewar Network Plugin Suite

Enhance your Minecraft network experience with a powerful, integrated plugin suite designed for Velocity and Paper.

## Overview
The **Minewar Network Plugin Suite** (comprising **MinewarVelocity** and **Minewar-Utils**) provides a seamless bridge between your proxy and backend servers. It offers an advanced vanish system, network-wide chat synchronization, dynamic placeholders, and robust Discord integration—all centered around a performance-first design.

## Key Features

### Advanced Vanish System
- **Tiered Permissions:** Support for Standard and Admin vanish levels.
- **Seamless Tablist Integration:** Fully integrated with Velocitab to hide vanished players from the tablist based on viewer permissions.
- **Smart Placeholders:** Network and server player counts automatically exclude vanished players.

### Dynamic Placeholders (Native MiniPlaceholders)
- `<minewar_total>`: Total non-vanished players online across the entire network.
- `<minewar_server_NAME>`: Non-vanished player count for a specific server (e.g., `<minewar_server_lobby>`).
- Native integration with Velocitab for headers and footers.

### Discord Synchronization
- **Account Linking:** Link Minecraft accounts to Discord using a simple code system.
- **Role Sync:** Automatically synchronize LuckPerms roles to Discord roles.
- **Log Alerts:** Send administrative alerts directly to Discord channels.

### Performance & Security
- **Asynchronous Operations:** Configuration reloads and message parsing are handled asynchronously to prevent proxy lag.
- **Database Persistence:** Critical data (last seen, ignore lists, link data) is stored securely in a dedicated database folder.

## Installation

### Requirements
- **Velocity Proxy** (3.3+)
- **PaperMC** (1.20.6+)
- **Velocitab** (latest)
- **MiniPlaceholders** (latest)
- **LuckPerms** (synced database recommended)

### Setup Steps
1. Place `MinewarVelocity.jar` in your Velocity `/plugins` folder.
2. Place `Minewar-Utils.jar` in each Paper server's `/plugins` folder.
3. Restart the servers to generate configuration files.
4. Configure your Discord Bot Token and Guild ID in `config.yml`.
5. Set `formatter_type: MINIMESSAGE` and `enable_miniplaceholders_hook: true` in your Velocitab `config.yml`.

## Commands & Permissions

### Core Commands
- `/minewarvelocity reload`: Reloads proxy configurations. (`minewarvelocity.reload`)
- `/vanish`: Toggles your vanish state. (`minewar.vanish`)
- `/alert <message>`: Broadcase a network-wide alert. (`minewar.alert`)
- `/server <name>`: Switch to a whitelisted server. (`minewar.server`)
- `/discord <link|unlink|users>`: Manage your Discord account link.

### Essential Permissions
- `minewar.vanish.see`: See standard vanished players.
- `minewar.vanish.admins.see`: See admin vanished players.
- `minewar.staffchat.see`: Access the network staff chat.
- `minewar.socialspy`: View private messages between other players.

## Support & Contributing
For bug reports or feature requests, please use the issue tracker. Contributions are welcome—please submit a pull request with a clear description of your changes.
# mwvtemp
