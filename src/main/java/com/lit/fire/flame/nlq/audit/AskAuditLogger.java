package com.lit.fire.flame.nlq.audit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lit.fire.flame.nlq.config.AskEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records one {@link AskAuditRecord} per Ask request (F10) as a structured (JSON) log line via SLF4J,
 * and — when {@code aura.ask.audit.persist=true} — additionally persists it to a table in AuraMath's
 * <b>own</b> database through the application {@link JdbcTemplate}.
 *
 * <p><b>Redaction is structural.</b> An {@link AskAuditRecord} is built to be credential- and
 * value-free (host only, never the URL/username/password; counts and names, never row cells or a
 * masked column's raw value), so this logger simply serializes the record it is given. It never logs a
 * password, an API key, or any row value.
 *
 * <p><b>Sink.</b> The log line is always written (logger {@code com.lit.fire.flame.nlq.audit.AskAuditLogger},
 * level {@code INFO}). Persistence is <b>off by default</b> and, when on, writes to AuraMath's own
 * datasource via {@code DataSourceConfig}'s {@code JdbcTemplate} — <em>never</em> the per-request
 * target connection. The audit table is not auto-created (the DDL is in the design doc); a persistence
 * failure is swallowed with a warning so auditing never breaks the request it is recording.
 */
@Component
public class AskAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AskAuditLogger.class);

    /** {@code //host[:port]} authority extractor; the orchestrator strips credentials before this. */
    private static final Pattern AUTHORITY = Pattern.compile("//([^/?#]+)");

    private final AskEngineProperties properties;
    private final JdbcTemplate appJdbcTemplate;
    private final Gson gson = new Gson();

    /**
     * @param properties      the Ask engine config (audit persistence flag + table name)
     * @param appJdbcTemplate AuraMath's own {@link JdbcTemplate} (Spring-Boot autoconfigured from
     *                        {@code DataSourceConfig}); may be {@code null} when no datasource is wired
     */
    @Autowired
    public AskAuditLogger(AskEngineProperties properties, @Nullable JdbcTemplate appJdbcTemplate) {
        this.properties = properties;
        this.appJdbcTemplate = appJdbcTemplate;
    }

    /**
     * Emit one audit record: always a structured JSON log line, and — when persistence is enabled —
     * also a row in AuraMath's own audit table. Never throws.
     */
    public void record(AskAuditRecord record) {
        if (record == null) {
            return;
        }
        try {
            log.info("{}", gson.toJson(toJson(record)));
        } catch (RuntimeException e) {
            // Auditing must never break the request it records.
            log.warn("failed to write Ask audit log line for request {}", record.getRequestId());
        }
        if (properties.getAudit().isPersist()) {
            persist(record);
        }
    }

    /**
     * Extract the {@code host[:port]} of a JDBC URL, dropping any {@code user:pass@} userinfo and the
     * path/query entirely. Returns {@code null} for a host-less URL (e.g. {@code jdbc:sqlite:/path}).
     * Credentials carried in the URL are never returned.
     */
    @Nullable
    public static String targetHost(@Nullable String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        Matcher m = AUTHORITY.matcher(jdbcUrl);
        if (!m.find()) {
            return null;
        }
        String authority = m.group(1);
        int at = authority.lastIndexOf('@'); // drop any user:password@ prefix
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        return authority.isEmpty() ? null : authority;
    }

    private JsonObject toJson(AskAuditRecord r) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "ask.request");
        o.addProperty("requestId", r.getRequestId());
        if (r.getTimestamp() != null) {
            o.addProperty("timestamp", r.getTimestamp().toString());
        }
        o.addProperty("outcome", String.valueOf(r.getOutcome()));
        addIfPresent(o, "reason", r.getReason());
        addIfPresent(o, "databaseProduct", r.getDatabaseProduct());
        addIfPresent(o, "databaseHost", r.getDatabaseHost());
        addIfPresent(o, "question", r.getQuestion());
        addIfPresent(o, "generatedSql", r.getGeneratedSql());
        o.add("tablesUsed", toArray(r.getTablesUsed()));
        o.addProperty("rowCount", r.getRowCount());
        o.addProperty("truncated", r.isTruncated());

        JsonObject timings = new JsonObject();
        for (Map.Entry<String, Long> e : r.getTimingMillis().entrySet()) {
            timings.addProperty(e.getKey(), e.getValue());
        }
        o.add("timingMillis", timings);

        JsonObject llm = new JsonObject();
        llm.addProperty("calls", r.getLlmCalls());
        llm.addProperty("inputTokens", r.getLlmInputTokens());
        llm.addProperty("outputTokens", r.getLlmOutputTokens());
        o.add("llm", llm);

        JsonObject policy = new JsonObject();
        policy.add("skippedTables", toArray(r.getSkippedTables()));
        policy.add("skippedColumns", toArray(r.getSkippedColumns()));
        policy.add("maskedColumns", toArray(r.getMaskedColumns()));
        o.add("policy", policy);
        return o;
    }

    private void persist(AskAuditRecord r) {
        if (appJdbcTemplate == null) {
            log.warn("aura.ask.audit.persist=true but no application JdbcTemplate is available; "
                    + "skipping persistence for request {}", r.getRequestId());
            return;
        }
        String table = properties.getAudit().getTable();
        String sql = "INSERT INTO " + table + " ("
                + "request_id, created_at, outcome, reason, db_product, db_host, question, "
                + "generated_sql, tables_used, row_count, truncated, total_millis, llm_calls, "
                + "llm_input_tokens, llm_output_tokens, skipped_tables, skipped_columns, masked_columns"
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try {
            Long totalMillis = r.getTimingMillis().get("totalMillis");
            appJdbcTemplate.update(sql,
                    r.getRequestId(),
                    Timestamp.from(r.getTimestamp() == null ? Instant.now() : r.getTimestamp()),
                    String.valueOf(r.getOutcome()),
                    r.getReason(),
                    r.getDatabaseProduct(),
                    r.getDatabaseHost(),
                    r.getQuestion(),
                    r.getGeneratedSql(),
                    join(r.getTablesUsed()),
                    r.getRowCount(),
                    r.isTruncated(),
                    totalMillis,
                    r.getLlmCalls(),
                    r.getLlmInputTokens(),
                    r.getLlmOutputTokens(),
                    join(r.getSkippedTables()),
                    join(r.getSkippedColumns()),
                    join(r.getMaskedColumns()));
        } catch (RuntimeException e) {
            // Persistence is best-effort: never fail the request because the audit row could not be
            // written. Log only the SQLState-free class name and request id — never the row values.
            log.warn("failed to persist Ask audit row for request {} into table {}: {}",
                    r.getRequestId(), table, e.getClass().getSimpleName());
        }
    }

    private static void addIfPresent(JsonObject o, String key, String value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String v : values) {
                array.add(v);
            }
        }
        return array;
    }

    @Nullable
    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }
}
