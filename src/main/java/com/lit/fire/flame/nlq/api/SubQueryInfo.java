package com.lit.fire.flame.nlq.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One per-database entry in a federated {@link AskResponse}: the database that was queried, the
 * validated read-only SQL that ran against it, the tables it read, and how many rows it returned.
 * Carries <b>no credentials</b> — only the logical database name.
 */
public final class SubQueryInfo {

    private final String database;
    private final String sql;
    private final List<String> tablesUsed;
    private final int rowCount;
    private final boolean truncated;

    public SubQueryInfo(String database, String sql, List<String> tablesUsed, int rowCount,
                        boolean truncated) {
        this.database = database;
        this.sql = sql;
        this.tablesUsed = (tablesUsed == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tablesUsed));
        this.rowCount = rowCount;
        this.truncated = truncated;
    }

    /** The logical database this query ran against; never {@code null}. */
    public String getDatabase() {
        return database;
    }

    /** The validated, row-capped read-only SQL executed against {@link #getDatabase()}. */
    public String getSql() {
        return sql;
    }

    /** Tables this query read; never {@code null}. */
    public List<String> getTablesUsed() {
        return tablesUsed;
    }

    /** Number of rows this database returned. */
    public int getRowCount() {
        return rowCount;
    }

    /** {@code true} when this database's result filled the row cap. */
    public boolean isTruncated() {
        return truncated;
    }
}
