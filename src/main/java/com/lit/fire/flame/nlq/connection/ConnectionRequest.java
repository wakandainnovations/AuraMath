package com.lit.fire.flame.nlq.connection;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-request details for opening a connection to the target database the Ask engine should answer
 * against. Supplied in the request body and deserialized by the REST layer.
 *
 * <p>This is never AuraMath's own application database: the {@link DynamicConnectionFactory} always
 * builds a brand-new, isolated {@link java.sql.Connection} from these fields and never touches
 * {@code DataSourceConfig}.
 *
 * <p>The connection is always opened with read-only intent — {@link #isReadOnly()} is fixed to
 * {@code true} and cannot be overridden by the caller, since the engine only ever issues
 * {@code SELECT}/{@code WITH} queries.
 */
public class ConnectionRequest {

    /**
     * Optional logical label for this database (e.g. {@code orders}, {@code billing}). Used to tag the
     * database in a federated (multi-database) Ask so the model and the response can refer to each
     * source by name. The {@code DatasourceRegistry} sets it from the {@code ask.db.<name>} section; for
     * an ad-hoc per-request connection it may be {@code null}, in which case a default is assigned.
     */
    private String name;

    /** JDBC URL of the target database. Required. Must pass {@link JdbcUrlValidator}. */
    private String jdbcUrl;

    /** Username for the target database. May be {@code null} (e.g. file-based SQLite). */
    private String username;

    /** Password for the target database. May be {@code null}. Never echoed back or logged. */
    private String password;

    /**
     * JDBC driver hint: one of {@code postgresql}, {@code sqlite}, {@code mysql}. Optional —
     * when blank it is auto-detected from the {@link #jdbcUrl} scheme.
     */
    private String driver;

    /**
     * Tables to skip for this request, unioned with {@code aura.ask.default-skip-tables}. Each entry
     * is a bare name ({@code users}) or schema-qualified ({@code public.users}); matching is
     * case-insensitive. Skipped tables are invisible to the model and re-enforced at validation.
     */
    private List<String> skipTables = new ArrayList<>();

    /**
     * Columns to skip for this request. Each entry is {@code table.column} or
     * {@code schema.table.column} (case-insensitive). Skipped columns are removed from their
     * table before the schema is shown to the model.
     */
    private List<String> skipColumns = new ArrayList<>();

    public ConnectionRequest() {
    }

    public ConnectionRequest(String jdbcUrl, String username, String password, String driver) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.driver = driver;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public List<String> getSkipTables() {
        return skipTables;
    }

    public void setSkipTables(List<String> skipTables) {
        this.skipTables = skipTables;
    }

    public List<String> getSkipColumns() {
        return skipColumns;
    }

    public void setSkipColumns(List<String> skipColumns) {
        this.skipColumns = skipColumns;
    }

    /** Read-only intent is always {@code true} for the Ask engine and cannot be overridden. */
    public boolean isReadOnly() {
        return true;
    }
}
