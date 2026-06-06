package com.lit.fire.flame.nlq.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One immutable audit record for a single Ask request (F10) — the full, <b>credential-free</b> trace
 * of what happened, assembled by the orchestrator as the pipeline runs and emitted by
 * {@link AskAuditLogger}.
 *
 * <p>It deliberately carries <b>no secrets and no row data</b>: the target database is identified by
 * {@link #getDatabaseHost() host} (and product) only — never its URL credentials, username, or
 * password — and the result is summarized by {@link #getRowCount() rowCount}/{@link #isTruncated()
 * truncated} and the <em>names</em> of the {@link #getTablesUsed() tables used} and the
 * {@link #getSkippedTables() skipped}/{@link #getMaskedColumns() masked} objects, never any cell
 * value (in particular, never a masked column's raw value).
 *
 * <p>The {@link #getRequestId() requestId} is echoed to the client (on the answer and on error
 * responses) so an operator can correlate a log line — or a persisted row — with what the caller saw.
 * Build one with {@link #builder()}; the builder is filled incrementally and tolerates a partial trace
 * (an early failure simply leaves later fields at their defaults).
 */
public final class AskAuditRecord {

    /** The terminal outcome of an Ask. */
    public enum Outcome {
        /** A question was answered. */
        ANSWERED,
        /** The engine returned a clarification instead of an answer. */
        CLARIFICATION,
        /** The request failed; {@link AskAuditRecord#getReason()} carries the sanitized cause. */
        ERROR
    }

    private final String requestId;
    private final Instant timestamp;
    private final Outcome outcome;
    private final String reason;

    private final String databaseProduct;
    private final String databaseHost;

    private final String question;
    private final String generatedSql;
    private final List<String> tablesUsed;
    private final int rowCount;
    private final boolean truncated;

    private final Map<String, Long> timingMillis;
    private final int llmCalls;
    private final int llmInputTokens;
    private final int llmOutputTokens;

    private final List<String> skippedTables;
    private final List<String> skippedColumns;
    private final List<String> maskedColumns;

    private AskAuditRecord(Builder b) {
        this.requestId = b.requestId;
        this.timestamp = b.timestamp;
        this.outcome = b.outcome;
        this.reason = b.reason;
        this.databaseProduct = b.databaseProduct;
        this.databaseHost = b.databaseHost;
        this.question = b.question;
        this.generatedSql = b.generatedSql;
        this.tablesUsed = copy(b.tablesUsed);
        this.rowCount = b.rowCount;
        this.truncated = b.truncated;
        this.timingMillis = (b.timingMillis == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.timingMillis));
        this.llmCalls = b.llmCalls;
        this.llmInputTokens = b.llmInputTokens;
        this.llmOutputTokens = b.llmOutputTokens;
        this.skippedTables = copy(b.skippedTables);
        this.skippedColumns = copy(b.skippedColumns);
        this.maskedColumns = copy(b.maskedColumns);
    }

    private static List<String> copy(List<String> in) {
        return (in == null) ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(in));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Correlation id echoed to the client; never {@code null} for a logged record. */
    public String getRequestId() {
        return requestId;
    }

    /** When the request was received. */
    public Instant getTimestamp() {
        return timestamp;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** A short, sanitized reason — the clarification marker, the failure category, or {@code null}. */
    public String getReason() {
        return reason;
    }

    /** Target database product name (e.g. {@code PostgreSQL}); {@code null} if never reached. */
    public String getDatabaseProduct() {
        return databaseProduct;
    }

    /** Target database host[:port] — never credentials; {@code null} for a host-less URL (e.g. SQLite). */
    public String getDatabaseHost() {
        return databaseHost;
    }

    /** The natural-language question; {@code null} only for a malformed request that carried none. */
    public String getQuestion() {
        return question;
    }

    /** The validated SQL that ran, or {@code null} for a clarification or pre-execution failure. */
    public String getGeneratedSql() {
        return generatedSql;
    }

    /** Names of the tables the query read; never {@code null}. */
    public List<String> getTablesUsed() {
        return tablesUsed;
    }

    /** Rows returned, or {@code -1} when no query ran. */
    public int getRowCount() {
        return rowCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    /** Per-stage wall-clock timings in milliseconds; never {@code null}. */
    public Map<String, Long> getTimingMillis() {
        return timingMillis;
    }

    public int getLlmCalls() {
        return llmCalls;
    }

    public int getLlmInputTokens() {
        return llmInputTokens;
    }

    public int getLlmOutputTokens() {
        return llmOutputTokens;
    }

    /** Names of skip-listed tables in effect for this request; never {@code null}. */
    public List<String> getSkippedTables() {
        return skippedTables;
    }

    /** Names of skip-listed columns in effect for this request; never {@code null}. */
    public List<String> getSkippedColumns() {
        return skippedColumns;
    }

    /** Names of masked columns in effect for this request; never {@code null}. */
    public List<String> getMaskedColumns() {
        return maskedColumns;
    }

    /** Mutable builder filled as the pipeline progresses; see {@link AskAuditRecord}. */
    public static final class Builder {
        private String requestId;
        private Instant timestamp;
        private Outcome outcome = Outcome.ERROR;
        private String reason;
        private String databaseProduct;
        private String databaseHost;
        private String question;
        private String generatedSql;
        private List<String> tablesUsed;
        private int rowCount = -1;
        private boolean truncated;
        private Map<String, Long> timingMillis;
        private int llmCalls;
        private int llmInputTokens;
        private int llmOutputTokens;
        private List<String> skippedTables;
        private List<String> skippedColumns;
        private List<String> maskedColumns;

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder outcome(Outcome outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder databaseProduct(String databaseProduct) {
            this.databaseProduct = databaseProduct;
            return this;
        }

        public Builder databaseHost(String databaseHost) {
            this.databaseHost = databaseHost;
            return this;
        }

        public Builder question(String question) {
            this.question = question;
            return this;
        }

        public Builder generatedSql(String generatedSql) {
            this.generatedSql = generatedSql;
            return this;
        }

        public Builder tablesUsed(List<String> tablesUsed) {
            this.tablesUsed = tablesUsed;
            return this;
        }

        public Builder rowCount(int rowCount) {
            this.rowCount = rowCount;
            return this;
        }

        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public Builder timingMillis(Map<String, Long> timingMillis) {
            this.timingMillis = timingMillis;
            return this;
        }

        public Builder llmUsage(int calls, int inputTokens, int outputTokens) {
            this.llmCalls = calls;
            this.llmInputTokens = inputTokens;
            this.llmOutputTokens = outputTokens;
            return this;
        }

        public Builder skippedTables(List<String> skippedTables) {
            this.skippedTables = skippedTables;
            return this;
        }

        public Builder skippedColumns(List<String> skippedColumns) {
            this.skippedColumns = skippedColumns;
            return this;
        }

        public Builder maskedColumns(List<String> maskedColumns) {
            this.maskedColumns = maskedColumns;
            return this;
        }

        public AskAuditRecord build() {
            return new AskAuditRecord(this);
        }
    }
}
