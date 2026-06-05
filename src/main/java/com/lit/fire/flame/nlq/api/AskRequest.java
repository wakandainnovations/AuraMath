package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.connection.ConnectionRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * The request body for {@code POST /api/ask} — one end-to-end Ask: a natural-language question plus
 * the per-request target database to answer it against, and the tables/columns to keep invisible.
 *
 * <p>The {@link #getConnection() connection} carries the (always read-only, fully isolated) target
 * database details (F1). The {@link #getSkipTables() skipTables} / {@link #getSkipColumns()
 * skipColumns} here are <b>unioned</b> with any carried on the connection and with the server-side
 * {@code aura.ask.default-skip-tables}; the union is removed from the schema the model sees and
 * re-enforced at validation/execution, so skipped objects can be neither seen nor touched.
 *
 * <p>{@link #getModel() model} optionally overrides the LLM model id for this request;
 * {@link #getMaxRows() maxRows} optionally lowers the row cap (it is clamped to the configured
 * {@code aura.ask.max-rows} ceiling and can never raise it).
 */
public class AskRequest {

    /** The target database to answer against. Required. Never AuraMath's own datasource. */
    private ConnectionRequest connection;

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
