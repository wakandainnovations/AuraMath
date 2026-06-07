package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.connection.ConnectionRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * The request body for {@code POST /api/ask} — one end-to-end Ask: a natural-language question plus
 * the per-request target database to answer it against, and the tables/columns to keep invisible.
 *
 * <p><b>Which databases.</b> The target databases are resolved in precedence order: an explicit
 * {@link #getConnections() connections} list (federated), else a single {@link #getConnection()
 * connection}, else the server-side registry loaded from {@code ~/config.secrets} — all of it, or the
 * {@link #getDatabases() databases} subset. The registry path is credential-free: the request carries
 * only a question. When more than one database is resolved, the engine answers each with its own safe
 * query and collates the results into one answer.
 *
 * <p>The {@link #getSkipTables() skipTables} / {@link #getSkipColumns() skipColumns} here are
 * <b>unioned</b> with any carried on each connection and with the server-side
 * {@code aura.ask.default-skip-tables}; the union is removed from the schema the model sees and
 * re-enforced at validation/execution, so skipped objects can be neither seen nor touched.
 *
 * <p>{@link #getModel() model} optionally overrides the LLM model id for this request;
 * {@link #getMaxRows() maxRows} optionally lowers the row cap (it is clamped to the configured
 * {@code aura.ask.max-rows} ceiling and can never raise it).
 */
public class AskRequest {

    /**
     * A single ad-hoc target database to answer against. Optional. When set (and {@link #connections}
     * is empty) the engine answers against just this database — the original single-database behaviour.
     * Never AuraMath's own datasource.
     */
    private ConnectionRequest connection;

    /**
     * Multiple ad-hoc target databases to answer across, each with its own credentials and optional
     * {@code name}. Optional; when non-empty it takes precedence over {@link #connection} and triggers
     * the federated (cross-database) path. Most callers instead leave both connection fields empty and
     * use the server-side registry via {@link #databases}.
     */
    private List<ConnectionRequest> connections = new ArrayList<>();

    /**
     * Names of server-registered databases (from {@code ~/config.secrets}) to answer against. Used only
     * when no explicit {@link #connection}/{@link #connections} are supplied: an empty list means "all
     * registered databases", a non-empty list selects that subset. This is the credential-free path —
     * the request carries no connection details at all.
     */
    private List<String> databases = new ArrayList<>();

    /** The natural-language question. Required. */
    private String question;

    /** Tables to skip, unioned with the connection's and the server default skip-tables. */
    private List<String> skipTables = new ArrayList<>();

    /** Columns to skip, unioned with the connection's skip-columns. {@code table.column} or {@code schema.table.column}. */
    private List<String> skipColumns = new ArrayList<>();

    /** Optional LLM model id override; {@code null}/blank means the client's default. */
    private String model;

    /** Optional per-request row-cap override; {@code null} uses the configured ceiling. Clamped to it. */
    private Integer maxRows;

    public AskRequest() {
    }

    public ConnectionRequest getConnection() {
        return connection;
    }

    public void setConnection(ConnectionRequest connection) {
        this.connection = connection;
    }

    public List<ConnectionRequest> getConnections() {
        return connections;
    }

    public void setConnections(List<ConnectionRequest> connections) {
        this.connections = (connections == null) ? new ArrayList<>() : connections;
    }

    public List<String> getDatabases() {
        return databases;
    }

    public void setDatabases(List<String> databases) {
        this.databases = (databases == null) ? new ArrayList<>() : databases;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(Integer maxRows) {
        this.maxRows = maxRows;
    }
}
