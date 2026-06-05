package com.lit.fire.flame.nlq.connection;

import java.util.Locale;

/**
 * The three JDBC drivers the Ask engine is allowed to target. Each entry knows its JDBC URL scheme
 * prefix and the driver class that must be explicitly loaded before {@code DriverManager} can serve
 * a connection for it. Anything not in this enum is rejected fail-closed.
 */
public enum SupportedDriver {

    POSTGRESQL("postgresql", "jdbc:postgresql:", "org.postgresql.Driver"),
    SQLITE("sqlite", "jdbc:sqlite:", "org.sqlite.JDBC"),
    MYSQL("mysql", "jdbc:mysql:", "com.mysql.cj.jdbc.Driver");

    private final String alias;
    private final String urlPrefix;
    private final String driverClass;

    SupportedDriver(String alias, String urlPrefix, String driverClass) {
        this.alias = alias;
        this.urlPrefix = urlPrefix;
        this.driverClass = driverClass;
    }

    /** Short name used in {@link ConnectionRequest#getDriver()} (e.g. {@code postgresql}). */
    public String getAlias() {
        return alias;
    }

    /** Required JDBC URL scheme prefix for this driver (e.g. {@code jdbc:postgresql:}). */
    public String getUrlPrefix() {
        return urlPrefix;
    }

    /** Fully-qualified driver class loaded via {@code Class.forName} before connecting. */
    public String getDriverClass() {
        return driverClass;
    }

    /** Resolve by the caller-supplied {@code driver} alias, case-insensitively. */
    public static SupportedDriver fromAlias(String alias) {
        if (alias == null) {
            return null;
        }
        String needle = alias.trim().toLowerCase(Locale.ROOT);
        for (SupportedDriver d : values()) {
            if (d.alias.equals(needle)) {
                return d;
            }
        }
        return null;
    }

    /** Resolve by matching the JDBC URL scheme prefix, case-insensitively. */
    public static SupportedDriver fromUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String lower = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        for (SupportedDriver d : values()) {
            if (lower.startsWith(d.urlPrefix)) {
                return d;
            }
        }
        return null;
    }
}
