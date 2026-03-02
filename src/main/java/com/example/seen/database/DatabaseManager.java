package com.example.seen.database;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages database connections using HikariCP.
 * Supports both MariaDB and H2 with self-healing schema creation.
 *
 * <p>
 * Nuclear Reset (Migration v3): On startup, if config key
 * {@code database.confirm-nuclear-reset} is {@code true} AND the metadata flag
 * {@code migration_v3_complete} does NOT yet exist in {@code plugin_metadata},
 * the plugin will TRUNCATE {@code seen_data} and then set the flag. Once the
 * flag is written, the reset will never run again even if the config key
 * remains {@code true}.
 */
@Singleton
public class DatabaseManager {

    private final Logger logger;
    private final Path dataDirectory;
    private final com.example.seen.config.SmartConfig smartConfig;
    private HikariDataSource dataSource;

    private String storageType;

    @Inject
    public DatabaseManager(@DataDirectory Path dataDirectory, Logger logger,
            com.example.seen.config.SmartConfig smartConfig) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.smartConfig = smartConfig;
    }

    /**
     * Initialize database connection after config has been loaded.
     * Must be called after SmartConfig.load().
     */
    public void init() {
        Section storageSection = smartConfig.getConfig().getSection("storage");

        if (storageSection == null) {
            logger.warn("Storage section missing from config.yml - using H2 defaults");
            this.storageType = "H2";
            setupH2(dataDirectory);
            createTables();
            return;
        }

        this.storageType = storageSection.getString("storage-type", "H2");

        if ("MARIADB".equalsIgnoreCase(storageType)) {
            setupMariaDB(storageSection);
        } else {
            setupH2(dataDirectory);
        }

        createTables();

        if (this.dataSource == null || this.dataSource.isClosed()) {
            throw new RuntimeException("HikariCP failed to initialize DataSource!");
        }

        // Run nuclear reset AFTER tables are confirmed to exist
        runNuclearResetIfRequired();

        logger.info("DatabaseManager initialization complete - DataSource ready");
    }

    // ── Nuclear Reset ─────────────────────────────────────────────────────────

    /**
     * Checks whether a one-time nuclear reset of {@code seen_data} should be
     * performed.
     *
     * <p>
     * Rules:
     * <ol>
     * <li>Config key {@code database.confirm-nuclear-reset} must be exactly
     * {@code true}.</li>
     * <li>The metadata flag {@code migration_v3_complete} must be ABSENT from
     * {@code plugin_metadata}.</li>
     * </ol>
     * Once the reset runs, {@code migration_v3_complete = true} is inserted into
     * {@code plugin_metadata} and subsequent calls are always no-ops.
     */
    private void runNuclearResetIfRequired() {
        // Read the config flag.
        // The key lives under the 'storage' section in config.yml.
        // Also accept the legacy 'database.confirm-nuclear-reset' path for safety.
        boolean confirmed = smartConfig.getConfig().getBoolean("storage.confirm-nuclear-reset", false)
                || smartConfig.getConfig().getBoolean("database.confirm-nuclear-reset", false);
        if (!confirmed) {
            return; // Config gate is off — never run
        }

        // Check metadata flag — if already set, skip forever
        if (isMetaFlagSet("migration_v3_complete")) {
            return;
        }

        // Both conditions met — execute reset
        logger.warn("NUCLEAR RESET triggered: truncating seen_data (migration_v3)...");
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE seen_data");
            logger.warn("seen_data has been truncated.");

            // Immediately lock — will never run again
            setMetaFlag(conn, "migration_v3_complete", "true");
            logger.info("migration_v3_complete flag set. Reset is now permanently locked.");
        } catch (SQLException e) {
            logger.error("NUCLEAR RESET FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean isMetaFlagSet(String key) {
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT meta_value FROM plugin_metadata WHERE meta_key = ?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            return rs.next(); // Flag exists = locked
        } catch (SQLException e) {
            logger.warn("Could not check metadata flag '" + key + "': " + e.getMessage());
            return false; // Assume not set — safe default is to check again
        }
    }

    private void setMetaFlag(Connection conn, String key, String value) throws SQLException {
        if (isMariaDB()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO plugin_metadata (meta_key, meta_value) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        } else {
            // H2: check-then-insert
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT meta_key FROM plugin_metadata WHERE meta_key = ?")) {
                check.setString(1, key);
                if (!check.executeQuery().next()) {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO plugin_metadata (meta_key, meta_value) VALUES (?, ?)")) {
                        ins.setString(1, key);
                        ins.setString(2, value);
                        ins.executeUpdate();
                    }
                } else {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE plugin_metadata SET meta_value = ? WHERE meta_key = ?")) {
                        upd.setString(1, value);
                        upd.setString(2, key);
                        upd.executeUpdate();
                    }
                }
            }
        }
    }

    // ── Connection setup ──────────────────────────────────────────────────────

    private void setupMariaDB(Section storageSection) {
        Section mariadbSection = storageSection.getSection("mariadb");

        if (mariadbSection == null) {
            logger.error("MariaDB selected but 'mariadb' section is missing from config.yml!");
            logger.warn("Falling back to H2 database");
            this.storageType = "H2";
            setupH2(dataDirectory);
            return;
        }

        String host = mariadbSection.getString("host", "localhost");
        int port = mariadbSection.getInt("port", 3306);
        String database = mariadbSection.getString("database", "minewar");
        String username = mariadbSection.getString("username", "root");
        String password = mariadbSection.getString("password", "");

        logger.info("Connecting to MariaDB: " + host + ":" + port + "/" + database);

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(30000);

        dataSource = new HikariDataSource(config);
        logger.info("MariaDB DataSource initialized successfully");
    }

    private void setupH2(Path dataDirectory) {
        try {
            Class.forName("org.h2.Driver");
            logger.info("H2 Driver loaded successfully");
        } catch (ClassNotFoundException e) {
            logger.error("H2 Driver not found in shaded JAR!");
            e.printStackTrace();
            return;
        }

        Path dbPath = dataDirectory.resolve("database").resolve("storage");
        String absolutePath = dbPath.toAbsolutePath().toString();

        logger.info("Using H2 database at: " + absolutePath);

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:" + absolutePath);
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(30000);

        dataSource = new HikariDataSource(config);
        logger.info("H2 DataSource initialized successfully");
    }

    // ── Schema creation ───────────────────────────────────────────────────────

    /**
     * Self-healing table creation.
     */
    private void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            boolean isMaria = isMariaDB();

            // 1. plugin_metadata
            stmt.execute("CREATE TABLE IF NOT EXISTS plugin_metadata (" +
                    "meta_key VARCHAR(64) PRIMARY KEY, " +
                    "meta_value VARCHAR(255)" +
                    ")");

            // 2. seen_data — AUTO_INCREMENT join_index is the join counter
            if (isMaria) {
                stmt.execute("CREATE TABLE IF NOT EXISTS seen_data (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "join_index INT UNIQUE AUTO_INCREMENT, " +
                        "first_join BIGINT, " +
                        "last_seen BIGINT, " +
                        "msg_enabled BOOLEAN DEFAULT TRUE, " +
                        "social_spy BOOLEAN DEFAULT FALSE, " +
                        "exact_name VARCHAR(16), " +
                        "vanish_status BOOLEAN DEFAULT FALSE, " +
                        "prefix VARCHAR(255), " +
                        "group_name VARCHAR(64), " +
                        "suffix VARCHAR(255))");
            } else {
                stmt.execute("CREATE TABLE IF NOT EXISTS seen_data (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "join_index INT AUTO_INCREMENT UNIQUE, " +
                        "first_join BIGINT, " +
                        "last_seen BIGINT, " +
                        "msg_enabled BOOLEAN DEFAULT TRUE, " +
                        "social_spy BOOLEAN DEFAULT FALSE, " +
                        "exact_name VARCHAR(16), " +
                        "vanish_status BOOLEAN DEFAULT FALSE, " +
                        "prefix VARCHAR(255), " +
                        "group_name VARCHAR(64), " +
                        "suffix VARCHAR(255))");
            }

            // 3. Other tables
            stmt.execute("CREATE TABLE IF NOT EXISTS ignore_list (" +
                    "player_uuid VARCHAR(36), " +
                    "ignored_uuid VARCHAR(36), " +
                    "PRIMARY KEY (player_uuid, ignored_uuid)" +
                    ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS discord_links (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "discord_id VARCHAR(20)" +
                    ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS discord_tokens (" +
                    "token VARCHAR(6) PRIMARY KEY, " +
                    "uuid VARCHAR(36), " +
                    "expires_at BIGINT" +
                    ")");

            // 4. Self-healing column additions for existing installations
            addColumnIfNotExists(conn, "seen_data", "last_seen", "BIGINT");
            addColumnIfNotExists(conn, "seen_data", "first_join", "BIGINT");
            addColumnIfNotExists(conn, "seen_data", "msg_enabled", "BOOLEAN DEFAULT TRUE");
            addColumnIfNotExists(conn, "seen_data", "social_spy", "BOOLEAN DEFAULT FALSE");
            addColumnIfNotExists(conn, "seen_data", "exact_name", "VARCHAR(16)");
            addColumnIfNotExists(conn, "seen_data", "vanish_status", "BOOLEAN DEFAULT FALSE");
            addColumnIfNotExists(conn, "seen_data", "prefix", "VARCHAR(255)");
            addColumnIfNotExists(conn, "seen_data", "group_name", "VARCHAR(64)");
            addColumnIfNotExists(conn, "seen_data", "suffix", "VARCHAR(255)");
            addColumnIfNotExists(conn, "seen_data", "join_index",
                    isMaria ? "INT UNIQUE AUTO_INCREMENT" : "INT AUTO_INCREMENT UNIQUE");

            logger.info("Database tables verified and updated");

        } catch (SQLException e) {
            logger.error("Failed to create/update tables: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Add a column to a table if it does not already exist.
     *
     * <p>
     * MariaDB stores table names in lowercase in INFORMATION_SCHEMA;
     * H2 stores them in UPPERCASE. Both are handled here.
     */
    private void addColumnIfNotExists(Connection conn, String table, String column, String definition) {
        try {
            // MariaDB: lowercase; H2: uppercase
            String lookupTable = isMariaDB() ? table.toLowerCase() : table.toUpperCase();
            ResultSet rs = conn.getMetaData().getColumns(null, null, lookupTable, column.toUpperCase());
            if (!rs.next()) {
                // Also try the other case for safety
                rs.close();
                rs = conn.getMetaData().getColumns(null, null, table, column);
                if (!rs.next()) {
                    rs.close();
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                        logger.info("Added column '" + column + "' to table '" + table + "'");
                    }
                    return;
                }
            }
            rs.close();
        } catch (SQLException e) {
            if (!e.getMessage().contains("Duplicate column")) {
                logger.warn("Could not add column '" + column + "' to '" + table + "': " + e.getMessage());
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource is not initialized!");
        }
        return dataSource.getConnection();
    }

    public boolean isMariaDB() {
        return "MARIADB".equalsIgnoreCase(getStorageType());
    }

    public String getStorageType() {
        return storageType != null ? storageType : "H2";
    }

    public void reconnect() {
        close();
        init();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
