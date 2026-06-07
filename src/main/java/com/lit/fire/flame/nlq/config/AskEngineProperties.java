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

    /** Audit logging &amp; observability settings (F10). */
    private Audit audit = new Audit();

    /** Hourly schema-cache settings (F13). */
    private SchemaCache schemaCache = new SchemaCache();

    /** Formula-gap logging settings (F14) — captures LLM-computed formulas not yet in the code catalog. */
    private FormulaGap formulaGap = new FormulaGap();

    /** Selected LLM provider behind the {@code LlmClient} interface (e.g. "claude"). */
    private String llmProvider = "claude";

    /**
     * Path to the host-side credential file the {@code DatasourceRegistry} loads at startup. It holds
     * the named target databases the engine may answer against, as {@code ask.db.<name>.*} groups (so
     * credentials never travel in a request or live in the repo). Defaults to {@code config.secrets}
     * in the home directory of the user running AuraMath. A missing file simply means an empty
     * registry — the per-request connection path still works.
     */
    private String secretsPath = System.getProperty("user.home") + "/config.secrets";

    /**
     * Upper bound on how many databases a single Ask may fan out to. Caps both the registry-driven
     * federated path and an explicit multi-connection request, so one question cannot open an
     * unbounded number of target connections.
     */
    private int maxDatabases = 5;

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

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
    }

    public SchemaCache getSchemaCache() {
        return schemaCache;
    }

    public void setSchemaCache(SchemaCache schemaCache) {
        this.schemaCache = schemaCache;
    }

    public FormulaGap getFormulaGap() {
        return formulaGap;
    }

    public void setFormulaGap(FormulaGap formulaGap) {
        this.formulaGap = formulaGap;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getSecretsPath() {
        return secretsPath;
    }

    public void setSecretsPath(String secretsPath) {
        this.secretsPath = secretsPath;
    }

    public int getMaxDatabases() {
        return maxDatabases;
    }

    public void setMaxDatabases(int maxDatabases) {
        this.maxDatabases = maxDatabases;
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

    /**
     * Audit logging &amp; observability configuration (bound from {@code aura.ask.audit}). The Ask
     * engine always writes a structured (JSON) audit line per request via SLF4J; this block controls
     * the <em>optional</em> persistence of the same record to a table in AuraMath's own database.
     *
     * <p>Persistence is <b>off by default</b>. When {@link #isPersist() persist} is {@code true}, each
     * audit record is also written (via AuraMath's own {@code JdbcTemplate}/{@code DataSourceConfig} —
     * never the per-request target connection) to {@link #getTable() table}. The table is <b>not</b>
     * auto-created against arbitrary databases — provide its DDL yourself (see the design doc).
     */
    public static class Audit {

        /** Also persist each audit record to {@link #getTable()} in AuraMath's own database. */
        private boolean persist = false;

        /** Target table for persisted audit records (in AuraMath's own database). */
        private String table = "ask_audit_log";

        public boolean isPersist() {
            return persist;
        }

        public void setPersist(boolean persist) {
            this.persist = persist;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }
    }

    /**
     * Hourly schema-cache configuration (bound from {@code aura.ask.schema-cache}) (F13). When enabled,
     * a scheduled job introspects every registered database and stores its structured schema in a table
     * in AuraMath's own database; the Ask path then answers from the cache instead of introspecting the
     * target live on every request. The table is auto-created (it is AuraMath's own internal table).
     */
    public static class SchemaCache {

        /** Master toggle for the schema cache + its refresh job. */
        private boolean enabled = true;

        /** Table (in AuraMath's own database) holding the cached per-database schemas. Auto-created. */
        private String table = "ask_schema_cache";

        /** How often the refresh job re-introspects every registered database, in milliseconds. */
        private long refreshIntervalMs = 3_600_000L;

        /** When a database is not yet cached, introspect it live for that request rather than failing. */
        private boolean fallbackToLive = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public long getRefreshIntervalMs() {
            return refreshIntervalMs;
        }

        public void setRefreshIntervalMs(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
        }

        public boolean isFallbackToLive() {
            return fallbackToLive;
        }

        public void setFallbackToLive(boolean fallbackToLive) {
            this.fallbackToLive = fallbackToLive;
        }
    }

    /**
     * Formula-gap logging configuration (bound from {@code aura.ask.formula-gap}) (F14). Whenever the
     * mathematician layer has to ask the LLM to compute a formula that is not in the deterministic code
     * catalog, a structured record is logged (always) and — when {@link #isPersist() persist} is true
     * (the default) — written to a table in AuraMath's own database, so those formulas can be promoted
     * into code in a later release. The table is auto-created.
     */
    public static class FormulaGap {

        /** Also persist each formula-gap record to {@link #getTable()} in AuraMath's own database. */
        private boolean persist = true;

        /** Table (in AuraMath's own database) holding logged formula gaps. Auto-created. */
        private String table = "ask_formula_gap";

        public boolean isPersist() {
            return persist;
        }

        public void setPersist(boolean persist) {
            this.persist = persist;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }
    }
}
