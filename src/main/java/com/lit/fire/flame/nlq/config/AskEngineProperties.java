package com.lit.fire.flame.nlq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
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

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }
}
