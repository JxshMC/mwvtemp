# Minewar Network Plugin Suite AI Instructions

This document serves as the primary source of truth and a permanent context guide for all future AI interactions and development tasks relating to the Minewar Network Plugin Suite.

## Project Overview
**Project Name:** Minewar Network Plugin Suite
**Components:**
- **Minewar-Utils:** Paper plugin for backend server logic.
- **MinewarVelocity:** Velocity plugin for proxy-wide features and integrations.

**Core Architecture:**
The suite operates on a hybrid architecture. Vanish states, player counts, and chat toggles are synchronized between Paper and Velocity via a custom Plugin Messaging Channel (`minewar:vanish` and `minewar:chat`). Velocity handles global display logic (Velocitab), cross-platform integrations (Discord), and network-wide commands.

## Development Environment & Tools
- **Platform:** Velocity Proxy (3.x+), PaperMC (1.20.6+).
- **Build Tool:** Gradle.
- **Language:** Java 17+.
- **Standard:** Use Adventure API (MiniMessage) for all text formatting.

## Strict Coding Standards & Best Practices

### API Usage
- **MiniPlaceholders:** Always use v2.0+ API syntax (Function-based `ctx -> ...` returning `Tag`).
- **Velocitab:** Integrate via `VanishIntegration` and `VelocitabAPI`.
- **JDA:** Assume JDA 5.x.
- **BoostedYAML:** Use for all configuration files (`config.yml`, `messages.yml`). Never use Bukkit's default `FileConfiguration`.

### Defensive Programming
- **External Lookups:** Always implement null/empty checks for Discord Guild IDs, LuckPerms data, and Proxy Server names.
- **Graceful Degradation:** Log warnings when configuration items are missing or invalid, but prevent plugin crashes.
- **Safety:** Use `Optional` for safer object handling in API calls.

### Modularity & Persistence
- **Feature Isolation:** Keep features in dedicated manager classes (e.g., `VanishManager`, `DiscordManager`).
- **No Accidental Deletions:** Never remove existing listeners or core logic (like `vanishedPlayers` Set management) unless explicitly refactoring.
- **Database:** Treat the `database/` folder as persistent storage. Files like `seen.yml`, `link-data.yml`, and `tokens.yml` must NOT be reset or overwritten by default resources.

## Key Features Logic

### Vanish System
- **Core:** Vanish state is initiated on Paper servers.
- **Sync:** Paper sends UUID and status to Velocity via `minewar:vanish`.
- **Velocity State:** `MinewarVelocity` maintains a `Set<UUID> vanishedPlayers`.
- **Visibility:** `MinewarVanishIntegration` controls tablist visibility using the `vanishedPlayers` set and specific permissions (`minewar.vanish.see`, `minewar.vanish.admins.see`).

### Custom Placeholders
- **Provider:** `MinewarExpansion.java` on Velocity (MiniPlaceholders).
- **Tags:** `<minewar_total>` and `<minewar_server_NAME>`.
- **Filtering:** These placeholders MUST exclude vanished players.

### Discord Synchronization
- **Trigger:** `UserDataRecalculateEvent` in LuckPerms triggers role updates.
- **Action:** `DiscordManager` updates roles in the configured Guild based on the mapping in `config.yml`.

## Plugin Messaging Channels
- `minewar:vanish`: Syncs vanish status (UUID, boolean).
- `minewar:chat`: Syncs staff chat toggles and network chat states.

## Future Development Guidance
- **Granular Permissions:** All new commands must have configurable permission nodes in `config.yml`.
- **Message Externalization:** Every user-facing string MUST be in `messages.yml`.
- **Architecture:** Propose major changes before modifying the core sync or data handling logic.
