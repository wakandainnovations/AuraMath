package com.lit.fire.flame.nlq.config;

import com.lit.fire.flame.nlq.audit.LlmUsageRecorder;
import com.lit.fire.flame.nlq.audit.RecordingLlmClient;
import com.lit.fire.flame.nlq.llm.ClaudeLlmClient;
import com.lit.fire.flame.nlq.llm.LlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires up the Ask engine's configuration and provider-selected beans.
 *
 * <p>Enables {@link AskEngineProperties} and, for F3, selects the {@link LlmClient} implementation
 * from {@code aura.ask.llm-provider}. Only {@code claude} is implemented today; any other value
 * fails fast at startup with a clear error. The bean constructs even when no API key is configured —
 * the misconfiguration surfaces on the first call, not at wiring time.
 *
 * <p>For F10, the selected client is wrapped in a {@link RecordingLlmClient} so every completion's
 * token usage is metered into the request-scoped {@link LlmUsageRecorder} for the audit line, without
 * the provider-neutral interface changing for callers.
 */
@Configuration
@EnableConfigurationProperties(AskEngineProperties.class)
public class AskEngineConfiguration {

    /**
     * Build the {@link LlmClient} chosen by {@link AskEngineProperties#getLlmProvider()}, wrapped in a
     * {@link RecordingLlmClient} that meters token usage for the audit line (F10).
     *
     * @throws IllegalStateException if the configured provider is not supported
     */
    @Bean
    public LlmClient llmClient(AskEngineProperties properties, LlmUsageRecorder usageRecorder) {
        String provider = properties.getLlmProvider();
        String normalized = (provider == null) ? "" : provider.trim().toLowerCase();
        if ("claude".equals(normalized)) {
            return new RecordingLlmClient(new ClaudeLlmClient(), usageRecorder);
        }
        throw new IllegalStateException("Unsupported LLM provider '" + provider
                + "' for aura.ask.llm-provider. Supported providers: claude.");
    }
}
