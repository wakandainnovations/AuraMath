package com.lit.fire.flame.nlq.sql;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.SkipList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Feature F6 — execute a validated, read-only query against the per-request target connection and
 * return typed results.
 *
 * <p>This service is the only place a generated query actually runs. It assumes nothing about how it
 * was reached: as <b>defense in depth</b> it re-runs the SQL through {@link SqlSafetyGuard} (the same
 * trust boundary F5 applies) and confirms the connection reports {@link Connection#isReadOnly()}
 * before opening a statement. It executes exactly the string the guard returns, so what runs is what
 * was just re-validated — not whatever the caller passed in.
 *
 * <p>The query runs under two hard bounds drawn from {@link AskEngineProperties}: a
 * {@link PreparedStatement#setQueryTimeout(int) query timeout} and a
 * {@link PreparedStatement#setMaxRows(int) row cap}. A fetch-size hint asks the driver to stream
 * rather than buffer the whole result. Result values are mapped to JSON-friendly Java types (see
 * {@link #convert}). Any driver failure is wrapped in a {@link QueryExecutionException} with a
 * sanitized message — never the raw driver text, credentials, or a stack trace.
 */
@Service
public class QueryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutionService.class);

    /** Upper bound on the streaming fetch-size hint; the effective hint is {@code min(maxRows, this)}. */
    private static final int MAX_FETCH_SIZE = 1000;

    private final SqlSafetyGuard safetyGuard;
    private final AskEngineProperties properties;

    public QueryExecutionService(SqlSafetyGuard safetyGuard, AskEngineProperties properties) {
        this.safetyGuard = safetyGuard;
        this.properties = properties;
    }

    /**
     * Execute {@code sql} read-only against {@code connection} and return the typed result set.
     *
     * <p>The caller owns {@code connection} and must close it (it is short-lived and supplied by F1).
     * {@code sql} is expected to have already passed {@link SqlSafetyGuard} (F5); this method
     * re-validates it regardless, using the same {@code skipList} and {@code schema}, and executes
     * the re-validated, row-capped string it returns.
     *
     * @return the typed {@link QueryResult}; {@link QueryResult#isTruncated()} is {@code true} when
     *         the result filled the {@code maxRows} cap
     * @throws QueryExecutionException with a typed {@link QueryExecutionException.Kind} and a sanitized
     *                                 message if re-validation fails, the connection is not read-only,
     *                                 the timeout fires, or the driver raises an error
     */
    public QueryResult execute(Connection connection, String sql, SkipList skipList,
                               DatabaseSchema schema) throws QueryExecutionException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(skipList, "skipList");
        Objects.requireNonNull(schema, "schema");

        // (defense in depth) Re-derive the safe SQL from scratch — the guard, not the caller, decides
        // what is executable. We run exactly what it returns.
        String safeSql;
        try {
            safeSql = safetyGuard.validate(sql, skipList, schema);
        } catch (UnsafeSqlException e) {
            throw new QueryExecutionException(QueryExecutionException.Kind.UNSAFE_SQL,
                    "query failed read-only validation: " + e.getReason(), e);
        }

        // (defense in depth) The connection must be read-only. F1 opens it read-only; we never trust
        // that and never flip it here.
        try {
            if (!connection.isReadOnly()) {
                throw new QueryExecutionException(QueryExecutionException.Kind.NOT_READ_ONLY,
                        "target connection is not read-only; refusing to execute");
            }
        } catch (SQLException e) {
            throw new QueryExecutionException(QueryExecutionException.Kind.NOT_READ_ONLY,
                    "could not confirm the target connection is read-only", e);
        }

        int maxRows = properties.getMaxRows();
        int timeoutSeconds = Math.max(0, properties.getQueryTimeoutSeconds());

        long startNanos = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(safeSql)) {
            statement.setQueryTimeout(timeoutSeconds);
            if (maxRows > 0) {
                statement.setMaxRows(maxRows);
                statement.setFetchSize(Math.min(maxRows, MAX_FETCH_SIZE));
            } else {
                statement.setFetchSize(MAX_FETCH_SIZE);
            }

            try (ResultSet rs = statement.executeQuery()) {
                List<QueryResult.Column> columns = readColumns(rs.getMetaData());
                List<Map<String, Object>> rows = readRows(rs, columns);
                long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
                // setMaxRows caps the driver at maxRows, and F5 injected a matching LIMIT, so filling
                // the cap is our truncation signal: more rows may exist than were returned.
                boolean truncated = maxRows > 0 && rows.size() >= maxRows;
                log.debug("executed read-only query: {} column(s), {} row(s), {} ms, truncated={}",
                        columns.size(), rows.size(), elapsedMillis, truncated);
                return new QueryResult(columns, rows, truncated, elapsedMillis);
            }
        } catch (SQLException e) {
            throw classify(e, timeoutSeconds);
        }
    }

    private static List<QueryResult.Column> readColumns(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<QueryResult.Column> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            // getColumnLabel honours an AS alias; fall back to the name if a driver leaves it blank.
            String label = meta.getColumnLabel(i);
            if (label == null || label.isEmpty()) {
                label = meta.getColumnName(i);
            }
            columns.add(new QueryResult.Column(label, meta.getColumnType(i), meta.getColumnTypeName(i)));
        }
        return columns;
    }

    private static List<Map<String, Object>> readRows(ResultSet rs, List<QueryResult.Column> columns)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        int count = columns.size();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                row.put(columns.get(i).getName(), convert(rs.getObject(i + 1)));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Map a raw JDBC value to a JSON-friendly Java type. Numbers, booleans, and strings pass through;
     * {@link BigDecimal} is preserved (Gson serializes it losslessly as a number); temporal types are
     * rendered as ISO-8601 strings without timezone surprises; binary becomes Base64; and any other
     * driver-specific object (Postgres {@code jsonb}/arrays/UUID, etc.) falls back to {@code toString()}.
     */
    private static Object convert(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().toString();
        }
        if (value instanceof java.sql.Time) {
            return ((java.sql.Time) value).toLocalTime().toString();
        }
        if (value instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) value);
        }
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            try {
                return clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE));
            } finally {
                clob.free();
            }
        }
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            try {
                byte[] bytes = blob.getBytes(1, (int) Math.min(blob.length(), Integer.MAX_VALUE));
                return Base64.getEncoder().encodeToString(bytes);
            } finally {
                blob.free();
            }
        }
        // JSONB (PGobject), SQL arrays, UUID, enums, and any other driver type: a stable string form.
        return value.toString();
    }

    /**
     * Wrap a driver {@link SQLException} in a sanitized {@link QueryExecutionException}. The raw
     * driver message can carry connection details, so it is inspected here only to classify the
     * failure and is never copied into the thrown message; the original is retained as the cause for
     * server-side logging.
     */
    private static QueryExecutionException classify(SQLException e, int timeoutSeconds) {
        if (isTimeout(e)) {
            return new QueryExecutionException(QueryExecutionException.Kind.TIMEOUT,
                    "query exceeded the " + timeoutSeconds + "s timeout and was cancelled", e);
        }
        String state = e.getSQLState();
        String detail = (state == null || state.isEmpty()) ? "" : " [SQLState " + state + "]";
        return new QueryExecutionException(QueryExecutionException.Kind.EXECUTION,
                "query execution failed" + detail, e);
    }

    /** Detect a timeout/cancellation across drivers that do not all raise {@link SQLTimeoutException}. */
    private static boolean isTimeout(SQLException e) {
        if (e instanceof SQLTimeoutException) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("interrupt") || lower.contains("cancel");
    }
}
