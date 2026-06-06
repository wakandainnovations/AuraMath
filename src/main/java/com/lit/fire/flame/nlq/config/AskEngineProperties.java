package com.lit.fire.flame.nlq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bound configuration for the Ask engine, prefix {@code aura.ask}.
 *
 * <p>Defaults are chosen so the engine is safe and dormant out of the box: enabled but with a
 * bounded row cap and conservative timeouts, no tables skipped, and the Claude LLM provider.
 */
@ConfigurationProperties(prefix = "aura.ask")
public class AskEngineProperties {

    /** Master switch for the Ask engine. */
    private boolean enabled = true;

    /** Maximum number of rows any generated query may return. */
    private int maxRows = 1000;

    /** Per-query execution timeout, in seconds. */
    private int queryTimeoutSeconds = 30;

    /** Timeout for establishing a target database connection, in seconds. */
    private int connectionTimeoutSeconds = 10;

    /** Tables always excluded from the schema and re-enforced at validation/execution. */
    private List<String> defaultSkipTables = new ArrayList<>();

    /**
     * Columns always excluded from the schema and re-enforced at validation/execution, on top of any
     * per-request {@code skipColumns}. Each entry is {@code table.column} or
     * {@code schema.table.column} (case-insensitive). (F9)
     */
    private List<String> defaultSkipColumns = new ArrayList<>();

    /**
     * Columns that MAY be aggregated (e.g. {@code count(email)}) but whose raw values must never be
     * projected or returned. They stay visible in the schema, the guard rejects any raw projection of
     * them, and any value that still reaches a result is redacted. Each entry is {@code table.column}
     * or {@code schema.table.column} (case-insensitive). (F9)
     */
    private List<String> maskedColumns = new ArrayList<>();

    /** Auto-skip-by-name-pattern settings (F9). */
    private AutoSkip autoSkip = new AutoSkip();

    /** Selected LLM provider behind the {@code LlmClient} interface (e.g. "claude"). */
    private String llmProvider = "claude";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }

    public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) {
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }

    public List<String> getDefaultSkipTables() {
        return defaultSkipTables;
    }

    public void setDefaultSkipTables(List<String> defaultSkipTables) {
        this.defaultSkipTables = defaultSkipTables;
    }

    public List<String> getDefaultSkipColumns() {
        return defaultSkipColumns;
    }

    public void setDefaultSkipColumns(List<String> defaultSkipColumns) {
        this.defaultSkipColumns = defaultSkipColumns;
    }

    public List<String> getMaskedColumns() {
        return maskedColumns;
    }

    public void setMaskedColumns(List<String> maskedColumns) {
        this.maskedColumns = maskedColumns;
    }

    public AutoSkip getAutoSkip() {
        return autoSkip;
    }

    public void setAutoSkip(AutoSkip autoSkip) {
        this.autoSkip = autoSkip;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    /**
     * Auto-skip-by-name-pattern configuration (bound from {@code aura.ask.auto-skip}). When
     * {@link #isEnabled() enabled} (the default), any table or column whose bare name fully matches one
     * of the case-insensitive {@link #getPatterns() patterns} is treated exactly like an explicitly
     * skipped object — invisible to the model and rejected at validation. Set {@code enabled=false} to
     * turn the feature off; override {@code patterns} to change the rules.
     */
    public static class AutoSkip {

        /** Master toggle for pattern-based auto-skipping. */
        private boolean enabled = true;

        /**
         * Case-insensitive, full-match regexes applied to bare table and column names. The defaults
         * cover the common secret-bearing name fragments; replace the list to customise.
         */
        private List<String> patterns = new ArrayList<>(Arrays.asList(
                ".*(password|passwd|pwd).*",
                ".*secret.*",
                ".*(^|_)ssn(_|$).*",
                ".*token.*",
                ".*api[_-]?key.*",
                ".*private[_-]?key.*",
                ".*(credit[_-]?card|card[_-]?number).*"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getPatterns() {
            return patterns;
        }

        public void setPatterns(List<String> patterns) {
            this.patterns = patterns;
        }
    }
}
