package com.lit.fire.flame.nlq.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed result of one read-only query execution (F6).
 *
 * <p>Immutable. The {@link #getColumns() columns} are in select-list order and describe the result
 * shape; the {@link #getRows() rows} carry the data with values already mapped to JSON-friendly Java
 * types (see {@link QueryExecutionService} for the type mapping). Each row is a {@link Map} keyed by
 * column name, in column order. {@link #isTruncated()} is {@code true} when the result was capped at
 * the configured {@code maxRows}, signalling that more rows may exist than were returned.
 *
 * <p>List/map accessors return unmodifiable views and are never {@code null}.
 */
public final class QueryResult {

    /** A single result column: its name and the JDBC {@link java.sql.Types} code it was reported as. */
    public static final class Column {
        private final String name;
        private final int sqlType;
        private final String typeName;

        public Column(String name, int sqlType, String typeName) {
            this.name = name;
            this.sqlType = sqlType;
            this.typeName = typeName;
        }

        /** The column label (alias if one was given, else the column name). */
        public String getName() {
            return name;
        }

        /** The {@link java.sql.Types} integer code for the column. */
        public int getSqlType() {
            return sqlType;
        }

        /** The driver's native type name (e.g. {@code INTEGER}, {@code timestamptz}, {@code jsonb}). */
        public String getTypeName() {
            return typeName;
        }
    }

    private final List<Column> columns;
    private final List<Map<String, Object>> rows;
    private final int rowCount;
    private final boolean truncated;
    private final long executionMillis;

    public QueryResult(List<Column> columns, List<Map<String, Object>> rows, boolean truncated,
                       long executionMillis) {
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        List<Map<String, Object>> copied = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copied.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        this.rows = Collections.unmodifiableList(copied);
        this.rowCount = copied.size();
        this.truncated = truncated;
        this.executionMillis = executionMillis;
    }

    /** Result columns in select-list order; never {@code null}. */
    public List<Column> getColumns() {
        return columns;
    }

    /** Rows as ordered name&rarr;value maps with JSON-friendly values; never {@code null}. */
    public List<Map<String, Object>> getRows() {
        return rows;
    }

    /** Number of rows actually returned (equal to {@code getRows().size()}). */
    public int getRowCount() {
        return rowCount;
    }

    /** {@code true} when the result was capped at {@code maxRows} and more rows may exist. */
    public boolean isTruncated() {
        return truncated;
    }

    /** Wall-clock time spent executing the statement and reading the result set, in milliseconds. */
    public long getExecutionMillis() {
        return executionMillis;
    }
}
