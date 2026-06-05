package com.lit.fire.flame.nlq.llm;

import com.google.gson.JsonObject;

/**
 * A provider-neutral completion result from an {@link LlmClient}.
 *
 * <p>{@link #getText()} holds the model's text output (may be empty for a purely structured
 * response). {@link #getStructuredJson()} is non-null only when the request asked for structured
 * output and the model produced a matching JSON object. Token counts are {@code -1} when the
 * provider did not report them.
 */
public class LlmResponse {

    private final String text;
    private final JsonObject structuredJson;
    private final String stopReason;
    private final int inputTokens;
    private final int outputTokens;

    public LlmResponse(String text, JsonObject structuredJson, String stopReason,
                       int inputTokens, int outputTokens) {
        this.text = text;
        this.structuredJson = structuredJson;
        this.stopReason = stopReason;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    /** Concatenated text output; never {@code null} but may be empty. */
    public String getText() {
        return text;
    }

    /** Parsed structured JSON object, or {@code null} for a free-text completion. */
    public JsonObject getStructuredJson() {
        return structuredJson;
    }

    /** Provider stop reason (e.g. {@code end_turn}, {@code max_tokens}, {@code tool_use}), or {@code null}. */
    public String getStopReason() {
        return stopReason;
    }

    /** Input (prompt) tokens billed, or {@code -1} if unknown. */
    public int getInputTokens() {
        return inputTokens;
    }

    /** Output (completion) tokens billed, or {@code -1} if unknown. */
    public int getOutputTokens() {
        return outputTokens;
    }
}
