package com.lit.fire.flame.nlq.connection;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens a brand-new, fully isolated {@link Connection} to a per-request target database for the Ask
 * engine.
 *
 * <p><b>Isolation.</b> This factory never reads, references, or falls back to AuraMath's own
 * {@code DataSourceConfig} datasource. Every connection is built from scratch via
 * {@link DriverManager} using only the caller-supplied {@link ConnectionRequest}.
 *
 * <p><b>Read-only.</b> Connections are opened with {@code setReadOnly(true)} as defense in depth;
 * autocommit is left at the driver default. The SQL validator remains the primary read-only guard.
 *
 * <p><b>Lifecycle contract.</b> {@link #open(ConnectionRequest)} returns a live {@link Connection}
 * that the <em>caller owns and must close</em>, ideally with try-with-resources. The factory holds
 * no pool and keeps no reference; connections are short-lived.
 */
@Component
public class DynamicConnectionFactory {

    private final AskEngineProperties properties;

    public DynamicConnectionFactory(AskEngineProperties properties) {
        this.properties = properties;
    }

    /**
     * Validate the request, load the appropriate driver, and open a read-only connection.
     *
     * @return a live, read-only {@link Connection}; the caller must close it
     * @throws IllegalArgumentException if the URL/driver fails {@link JdbcUrlValidator}
     * @throws SQLException             if the driver class is missing or the connection cannot be
     *                                  established (including login timeout)
     */
    public Connection open(ConnectionRequest request) throws SQLException {
        if (request == null) {
            throw new IllegalArgumentException("connection request is required");
        }
        SupportedDriver driver = JdbcUrlValidator.validateAndResolve(request.getJdbcUrl(), request.getDriver());

        // Explicitly load the supported driver. Never auto-discover or share AuraMath's datasource.
        try {
            Class.forName(driver.getDriverClass());
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not available on the classpath: " + driver.getDriverClass(), e);
        }

        // Login/connection timeout. DriverManager's setting is the portable mechanism honoured by
        // all three supported drivers.
        DriverManager.setLoginTimeout(Math.max(0, properties.getConnectionTimeoutSeconds()));

        Properties props = new Properties();
        if (request.getUsername() != null) {
            props.setProperty("user", request.getUsername());
        }
        if (request.getPassword() != null) {
            props.setProperty("password", request.getPassword());
        }
        // SQLite (Xerial) only honours read-only at open time — it rejects setReadOnly() afterwards —
        // so the read-only open mode is baked into the connection properties here. Postgres and MySQL
        // accept setReadOnly(true) on the live connection below.
        if (driver == SupportedDriver.SQLITE) {
            SQLiteConfig sqliteConfig = new SQLiteConfig();
            sqliteConfig.setReadOnly(true);
            props.putAll(sqliteConfig.toProperties());
        }

        Connection connection = DriverManager.getConnection(request.getJdbcUrl().trim(), props);
        if (driver != SupportedDriver.SQLITE) {
            try {
                connection.setReadOnly(true);
            } catch (SQLException e) {
                // Fail closed: if read-only cannot be guaranteed, do not hand back the connection.
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // best-effort cleanup
                }
                throw e;
            }
        }
        return connection;
    }
}
