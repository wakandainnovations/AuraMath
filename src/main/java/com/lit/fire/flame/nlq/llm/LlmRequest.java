package com.lit.fire.flame.nlq.llm;

import com.google.gson.JsonObject;

/**
 * A provider-neutral single-turn completion request for an {@link LlmClient}.
 *
 * <p>Carries a system prompt, the user prompt, optional structured-output schema, and the usual
 * generation knobs. Instances are immutable; build one with {@link #builder()}.
 *
 * <p><b>Structured output.</b> When {@link #getJsonSchema()} is non-null the client asks the model
 * to return a JSON object matching that schema (a <a href="https://json-schema.org">JSON Schema</a>
 * object describing an {@code object}); the parsed result is surfaced on
 * {@link LlmResponse#getStructuredJson()}. How a provider enforces this is an implementation detail
 * (the Claude client uses tool-use / {@code tool_choice}) and never leaks to the caller.
 *
 * <p><b>Temperature.</b> Optional and {@code null} by default. It is only sent to the provider when
 * set, because the default reasoning model ({@code claude-opus-4-8}) rejects sampling parameters —
 * leave it unset unless you target a model that accepts it.
 */
public class LlmRequest {

    private final String systemPrompt;
    private final String userPrompt;
    private final JsonObject jsonSchema;
    private final String structuredToolName;
    private final int maxTokens;
    private final Double temperature;
    private final String modelId;

    private LlmRequest(Builder b) {
        this.systemPrompt = b.systemPrompt;
        this.userPrompt = b.userPrompt;
        this.jsonSchema = b.jsonSchema;
        this.structuredToolName = b.structuredToolName;
        this.maxTokens = b.maxTokens;
        this.temperature = b.temperature;
        this.modelId = b.modelId;
    }

    /** System prompt / instructions, or {@code null} for none. */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** The user turn. Required. */
    public String getUserPrompt() {
        return userPrompt;
    }

    /** JSON Schema for structured output, or {@code null} for a free-text completion. */
    public JsonObject getJsonSchema() {
        return jsonSchema;
    }

    /**
     * Name advertised for the structured-output tool when {@link #getJsonSchema()} is set. Has no
     * effect for free-text completions. Defaults to {@code structured_output}.
     */
    public String getStructuredToolName() {
        return structuredToolName;
    }

    /** Upper bound on output tokens. */
    public int getMaxTokens() {
        return maxTokens;
    }

    /** Sampling temperature, or {@code null} to omit it (the default — required for Opus 4.x). */
    public Double getTemperature() {
        return temperature;
    }

    /** Model id, or {@code null} to let the client apply its default. */
    public String getModelId() {
        return modelId;
    }

    /** Whether this request asks for a structured (schema-constrained) response. */
    public boolean isStructured() {
        return jsonSchema != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link LlmRequest}. {@code userPrompt} is required. */
    public static final class Builder {
        private String systemPrompt;
        private String userPrompt;
        private JsonObject jsonSchema;
        private String structuredToolName = "structured_output";
        private int maxTokens = 1024;
        private Double temperature;
        private String modelId;

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }

        /** Request structured output matching this JSON Schema object. */
        public Builder jsonSchema(JsonObject jsonSchema) {
            this.jsonSchema = jsonSchema;
            return this;
        }

        public Builder structuredToolName(String structuredToolName) {
            this.structuredToolName = structuredToolName;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public LlmRequest build() {
            if (userPrompt == null || userPrompt.isEmpty()) {
                throw new IllegalArgumentException("userPrompt is required");
            }
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive");
            }
            if (structuredToolName == null || structuredToolName.isEmpty()) {
                throw new IllegalArgumentException("structuredToolName must not be blank");
            }
            return new LlmRequest(this);
        }
    }
}
