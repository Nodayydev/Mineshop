package hu.mineshop;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** JDBC storage for the dynamic sales state. */
final class EconomyStorage implements AutoCloseable {
    record SaleRecord(double pressure, long lastSale, long totalSold) {}

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private final String type;
    private final String table;
    private final Connection connection;

    private EconomyStorage(String type, String table, Connection connection) {
        this.type = type;
        this.table = table;
        this.connection = connection;
    }

    static EconomyStorage open(JavaPlugin plugin, FileConfiguration config) throws SQLException, ClassNotFoundException {
        String type = config.getString("storage.type", "sqlite").trim().toLowerCase();
        if (type.equals("yaml")) return null;
        if (!type.equals("sqlite") && !type.equals("mysql")) throw new IllegalArgumentException("storage.type csak sqlite, mysql vagy yaml lehet.");

        String prefix = config.getString("storage.mysql.table-prefix", "mineshop_");
        if (!SAFE_IDENTIFIER.matcher(prefix).matches()) throw new IllegalArgumentException("A MySQL table-prefix csak betűket, számokat és aláhúzást tartalmazhat.");
        String table = prefix + "sales";
        Connection connection;
        if (type.equals("sqlite")) {
            Class.forName("org.sqlite.JDBC");
            String relative = config.getString("storage.sqlite.file", "data/economy.db");
            File database = new File(plugin.getDataFolder(), relative);
            File parent = database.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new SQLException("Nem sikerült létrehozni az adatbázis mappáját.");
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
        } else {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String host = config.getString("storage.mysql.host", "127.0.0.1");
            int port = config.getInt("storage.mysql.port", 3306);
            String database = config.getString("storage.mysql.database", "mineshop");
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + config.getBoolean("storage.mysql.use-ssl", false)
                    + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=5000";
            connection = DriverManager.getConnection(url,
                    config.getString("storage.mysql.username", "root"),
                    config.getString("storage.mysql.password", ""));
        }
        EconomyStorage storage = new EconomyStorage(type, table, connection);
        storage.createTable();
        return storage;
    }

    boolean isSql() { return connection != null; }

    private synchronized void createTable() throws SQLException {
        String sql = type.equals("sqlite")
                ? "CREATE TABLE IF NOT EXISTS " + table + " (material TEXT PRIMARY KEY, pressure REAL NOT NULL, last_sale BIGINT NOT NULL, total_sold BIGINT NOT NULL)"
                : "CREATE TABLE IF NOT EXISTS " + table + " (material VARCHAR(128) PRIMARY KEY, pressure DOUBLE NOT NULL, last_sale BIGINT NOT NULL, total_sold BIGINT NOT NULL)";
        String metaSql = type.equals("sqlite")
                ? "CREATE TABLE IF NOT EXISTS " + table + "_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)"
                : "CREATE TABLE IF NOT EXISTS " + table + "_meta (meta_key VARCHAR(128) PRIMARY KEY, meta_value TEXT NOT NULL)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            statement.executeUpdate(metaSql);
        }
    }

    synchronized long getLastReset() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT meta_value FROM " + table + "_meta WHERE meta_key = ?")) {
            statement.setString(1, "last-reset");
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Long.parseLong(result.getString(1)) : 0L;
            }
        }
    }

    synchronized void initializeLastReset(long timestamp) throws SQLException {
        setMeta("last-reset", Long.toString(timestamp));
    }

    synchronized void resetEconomy(long timestamp) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table + " SET pressure = 0, last_sale = ?")) {
                statement.setLong(1, timestamp);
                statement.executeUpdate();
            }
            upsertMeta("last-reset", Long.toString(timestamp));
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private synchronized void setMeta(String key, String value) throws SQLException { upsertMeta(key, value); }

    private void upsertMeta(String key, String value) throws SQLException {
        String sql = type.equals("sqlite")
                ? "INSERT INTO " + table + "_meta (meta_key, meta_value) VALUES (?, ?) ON CONFLICT(meta_key) DO UPDATE SET meta_value=excluded.meta_value"
                : "INSERT INTO " + table + "_meta (meta_key, meta_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE meta_value=VALUES(meta_value)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    synchronized Map<String, SaleRecord> load() throws SQLException {
        Map<String, SaleRecord> records = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT material, pressure, last_sale, total_sold FROM " + table)) {
            while (result.next()) records.put(result.getString("material"),
                    new SaleRecord(result.getDouble("pressure"), result.getLong("last_sale"), result.getLong("total_sold")));
        }
        return records;
    }

    synchronized void save(Map<String, SaleRecord> records) throws SQLException {
        String sql = type.equals("sqlite")
                ? "INSERT INTO " + table + " (material, pressure, last_sale, total_sold) VALUES (?, ?, ?, ?) ON CONFLICT(material) DO UPDATE SET pressure=excluded.pressure, last_sale=excluded.last_sale, total_sold=excluded.total_sold"
                : "INSERT INTO " + table + " (material, pressure, last_sale, total_sold) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE pressure=VALUES(pressure), last_sale=VALUES(last_sale), total_sold=VALUES(total_sold)";
        boolean oldAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (var entry : records.entrySet()) {
                    statement.setString(1, entry.getKey());
                    statement.setDouble(2, entry.getValue().pressure());
                    statement.setLong(3, entry.getValue().lastSale());
                    statement.setLong(4, entry.getValue().totalSold());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    @Override public synchronized void close() throws SQLException { if (!connection.isClosed()) connection.close(); }
}
