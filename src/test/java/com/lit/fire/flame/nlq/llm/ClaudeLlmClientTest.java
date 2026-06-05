package com.lit.fire.flame.nlq.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link ClaudeLlmClient}.
 *
 * <p>Written on JUnit Jupiter (the engine spring-boot-starter-test puts on the JUnit Platform) so it
 * actually executes under {@code mvn test} — the project's older JUnit 4 tests need a vintage engine
 * that is not on the classpath, so they are not discovered.
 *
 * <p>The configuration-error test always runs and needs no network or key. The live round-trips are
 * <b>skipped</b> (via {@link org.junit.jupiter.api.Assumptions}) unless an {@code ANTHROPIC_API_KEY}
 * environment variable is set, so the build stays green and offline by default while still allowing a
 * real end-to-end check on demand:
 *
 * <pre>{@code ANTHROPIC_API_KEY=sk-ant-... mvn test}</pre>
 */
class ClaudeLlmClientTest {

    private static final String PROD_BASE_URL = "https://api.anthropic.com/v1/messages";

    /**
     * Acceptance: with no key configured the client still constructs, and the first call fails with
     * a clear, typed {@link LlmException.Kind#CONFIGURATION} error rather than leaking anything.
     */
    @Test
    void firstCallWithoutKeyThrowsConfigurationError() {
        ClaudeLlmClient client = new ClaudeLlmClient(null, PROD_BASE_URL, HttpClient.newHttpClient());
        LlmRequest request = LlmRequest.builder().userPrompt("ping").maxTokens(16).build();

        LlmException e = assertThrows(LlmException.class, () -> client.complete(request));
        assertEquals(LlmException.Kind.CONFIGURATION, e.getKind());
        // The message must name the secret to set, but never echo a key.
        assertTrue(e.getMessage().contains("anthropic.api.key"));
    }

    /** Acceptance: a real round-trip succeeds when a key is configured. Skipped otherwise. */
    @Test
    void liveRoundTripWhenKeyConfigured() throws LlmException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(),
                "ANTHROPIC_API_KEY not set — skipping live Claude round-trip");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ClaudeLlmClient client = new ClaudeLlmClient(apiKey, PROD_BASE_URL, http);

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("You are a terse assistant. Answer with a single word.")
                .userPrompt("Reply with the single word: pong")
                .maxTokens(32)
                .build();

        LlmResponse response = client.complete(request);
        assertNotNull(response);
        assertNotNull(response.getStopReason(), "expected a stop reason");
        assertTrue(response.getText() != null && !response.getText().isEmpty(),
                "expected non-empty text output");
        assertTrue(response.getInputTokens() > 0, "expected input token usage to be reported");
    }

    /** Acceptance: a structured round-trip returns a JSON object matching the requested schema. Skipped otherwise. */
    @Test
    void liveStructuredRoundTripWhenKeyConfigured() throws LlmException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(),
                "ANTHROPIC_API_KEY not set — skipping live structured round-trip");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ClaudeLlmClient client = new ClaudeLlmClient(apiKey, PROD_BASE_URL, http);

        // { "type": "object", "properties": { "capital": {"type": "string"} }, "required": ["capital"] }
        JsonObject capital = new JsonObject();
        capital.addProperty("type", "string");
        JsonObject properties = new JsonObject();
        properties.add("capital", capital);
        JsonArray required = new JsonArray();
        required.add("capital");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);

        LlmRequest request = LlmRequest.builder()
                .userPrompt("What is the capital of France?")
                .jsonSchema(schema)
                .maxTokens(128)
                .build();

        LlmResponse response = client.complete(request);
        assertNotNull(response.getStructuredJson());
        assertTrue(response.getStructuredJson().has("capital"),
                "structured output should contain the 'capital' field");
    }
}
