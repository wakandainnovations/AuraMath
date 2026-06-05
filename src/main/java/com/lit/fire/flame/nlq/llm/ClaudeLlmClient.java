package com.lit.fire.flame.nlq.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;

/**
 * {@link LlmClient} backed by the Anthropic Claude Messages API, called directly over
 * {@link java.net.http.HttpClient} with Gson (de)serialization — no Anthropic SDK dependency.
 *
 * <p><b>Credentials.</b> The API key is read once at construction from {@code secrets.txt} on the
 * classpath (key {@code anthropic.api.key}), the same loading pattern AuraMath's {@code
 * DataSourceConfig} uses. A missing key does <em>not</em> fail construction — the bean still wires
 * up — but the first {@link #complete} call then fails with a clear
 * {@link LlmException.Kind#CONFIGURATION} error. The key is never logged.
 *
 * <p><b>Structured output.</b> When the request carries a JSON Schema, the schema is advertised as a
 * single tool's {@code input_schema} and {@code tool_choice} forces that tool, so the model must
 * return a matching JSON object. That object is surfaced on {@link LlmResponse#getStructuredJson()}.
 *
 * <p><b>Resilience.</b> Non-2xx responses, timeouts, and rate limits (429) raise a typed
 * {@link LlmException}. Retryable failures (429, 5xx, timeouts) are retried <em>once</em> with a
 * bounded backoff that honours a {@code Retry-After} header when present.
 *
 * <p><b>Logging.</b> Prompt and schema contents are never logged at {@code INFO}; only coarse,
 * non-sensitive call metadata (model id, retry notices) is emitted, and never the API key.
 */
public class ClaudeLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLlmClient.class);

    /** Default reasoning-heavy model; callers may override per request via {@link LlmRequest#getModelId()}. */
    public static final String DEFAULT_MODEL = "claude-opus-4-8";

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String SECRETS_RESOURCE = "secrets.txt";
    private static final String API_KEY_PROPERTY = "anthropic.api.key";

    /** One bounded retry: two attempts total. */
    private static final int MAX_ATTEMPTS = 2;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final long DEFAULT_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 10_000L;

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /** Production constructor: loads the API key from {@code secrets.txt} and builds an HttpClient. */
    public ClaudeLlmClient() {
        this(loadApiKey(), DEFAULT_BASE_URL,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Visible-for-testing constructor allowing an explicit key, endpoint, and client. */
    ClaudeLlmClient(String apiKey, String baseUrl, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new LlmException(LlmException.Kind.CONFIGURATION,
                    "Anthropic API key is not configured. Set '" + API_KEY_PROPERTY
                            + "' in secrets.txt.");
        }

        String model = (request.getModelId() == null || request.getModelId().isEmpty())
                ? DEFAULT_MODEL : request.getModelId();
        byte[] body = gson.toJson(buildRequestBody(request, model)).getBytes(StandardCharsets.UTF_8);

        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = send(body);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return parseResponse(response.body(), request);
                }
                // Non-2xx: classify, retry once if retryable (429 or 5xx — never other 4xx).
                LlmException ex = httpError(status, response.body());
                if (isRetryable(status) && attempt < MAX_ATTEMPTS) {
                    backoff(retryAfterMillis(response).orElse(DEFAULT_BACKOFF_MS), attempt, status);
                    last = ex;
                    continue;
                }
                throw ex;
            } catch (HttpTimeoutException e) {
                // Covers HttpConnectTimeoutException (a subclass) as well.
                last = new LlmException(LlmException.Kind.TIMEOUT, "Claude request timed out", e);
                if (attempt < MAX_ATTEMPTS) {
                    backoff(DEFAULT_BACKOFF_MS, attempt, -1);
                    continue;
                }
                throw last;
            } catch (java.io.IOException e) {
                last = new LlmException(LlmException.Kind.TIMEOUT,
                        "Claude request failed at the transport layer", e);
                if (attempt < MAX_ATTEMPTS) {
                    backoff(DEFAULT_BACKOFF_MS, attempt, -1);
                    continue;
                }
                throw last;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(LlmException.Kind.TIMEOUT, "Claude request was interrupted", e);
            }
        }
        // Unreachable in practice — the loop either returns or throws — but keep the compiler happy.
        throw (last != null) ? last
                : new LlmException(LlmException.Kind.HTTP_ERROR, "Claude request failed");
    }

    private HttpResponse<String> send(byte[] body) throws java.io.IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Build the Anthropic Messages request body. Structured output is expressed as a forced tool call. */
    private JsonObject buildRequestBody(LlmRequest request, String model) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("max_tokens", request.getMaxTokens());
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            root.addProperty("system", request.getSystemPrompt());
        }
        // Temperature is only sent when explicitly set — the default model rejects sampling params.
        if (request.getTemperature() != null) {
            root.addProperty("temperature", request.getTemperature());
        }

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", request.getUserPrompt());
        messages.add(userMessage);
        root.add("messages", messages);

        if (request.isStructured()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", request.getStructuredToolName());
            tool.addProperty("description",
                    "Return the answer as a JSON object matching the provided schema.");
            tool.add("input_schema", request.getJsonSchema());
            JsonArray tools = new JsonArray();
            tools.add(tool);
            root.add("tools", tools);

            JsonObject toolChoice = new JsonObject();
            toolChoice.addProperty("type", "tool");
            toolChoice.addProperty("name", request.getStructuredToolName());
            root.add("tool_choice", toolChoice);
        }
        return root;
    }

    /** Parse a 2xx Messages response into an {@link LlmResponse}. */
    private LlmResponse parseResponse(String responseBody, LlmRequest request) throws LlmException {
        JsonObject root;
        try {
            root = gson.fromJson(responseBody, JsonObject.class);
        } catch (JsonParseException e) {
            throw new LlmException(LlmException.Kind.BAD_RESPONSE,
                    "Claude returned a response that was not valid JSON", e);
        }
        if (root == null || !root.has("content") || !root.get("content").isJsonArray()) {
            throw new LlmException(LlmException.Kind.BAD_RESPONSE,
                    "Claude response did not contain a content array");
        }

        StringBuilder text = new StringBuilder();
        JsonObject structured = null;
        for (JsonElement element : root.getAsJsonArray("content")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String type = block.has("type") ? block.get("type").getAsString() : "";
            if ("text".equals(type) && block.has("text")) {
                text.append(block.get("text").getAsString());
            } else if ("tool_use".equals(type)
                    && request.isStructured()
                    && block.has("name")
                    && request.getStructuredToolName().equals(block.get("name").getAsString())
                    && block.has("input") && block.get("input").isJsonObject()) {
                structured = block.getAsJsonObject("input");
            }
        }

        if (request.isStructured() && structured == null) {
            throw new LlmException(LlmException.Kind.BAD_RESPONSE,
                    "Claude did not return the requested structured output");
        }

        String stopReason = root.has("stop_reason") && !root.get("stop_reason").isJsonNull()
                ? root.get("stop_reason").getAsString() : null;
        int inputTokens = -1;
        int outputTokens = -1;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : -1;
            outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : -1;
        }
        return new LlmResponse(text.toString(), structured, stopReason, inputTokens, outputTokens);
    }

    /** Map a non-2xx status to a typed exception, including a terse provider message when parseable. */
    private LlmException httpError(int status, String responseBody) {
        String providerMessage = extractErrorMessage(responseBody);
        String suffix = (providerMessage != null) ? ": " + providerMessage : "";
        LlmException.Kind kind = (status == 429)
                ? LlmException.Kind.RATE_LIMITED : LlmException.Kind.HTTP_ERROR;
        return new LlmException(kind, status, "Claude API returned HTTP " + status + suffix, null);
    }

    /** Pull {@code error.message} from an Anthropic error body, if present. Never returns the raw body verbatim. */
    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        try {
            JsonObject root = gson.fromJson(responseBody, JsonObject.class);
            if (root != null && root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message")) {
                    return error.get("message").getAsString();
                }
            }
        } catch (JsonParseException ignored) {
            // Non-JSON error body — omit it rather than risk leaking opaque content.
        }
        return null;
    }

    /** Retry on 429 and 5xx; never on other 4xx (400/401/403 are not transient). */
    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    private Optional<Long> retryAfterMillis(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after").map(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return Math.min(MAX_BACKOFF_MS, Math.max(0L, seconds * 1000L));
            } catch (NumberFormatException e) {
                return DEFAULT_BACKOFF_MS;
            }
        });
    }

    private void backoff(long requestedMs, int attempt, int status) throws LlmException {
        long sleepMs = Math.min(MAX_BACKOFF_MS, Math.max(0L, requestedMs));
        if (log.isDebugEnabled()) {
            log.debug("Retrying Claude request (attempt {} of {}, status {}) after {} ms",
                    attempt, MAX_ATTEMPTS, status, sleepMs);
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.TIMEOUT,
                    "Interrupted while backing off before a Claude retry", e);
        }
    }

    /** Load the Anthropic API key from {@code secrets.txt} on the classpath; returns {@code null} if absent. */
    private static String loadApiKey() {
        Properties properties = new Properties();
        try (InputStream in = ClaudeLlmClient.class.getClassLoader()
                .getResourceAsStream(SECRETS_RESOURCE)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (Exception e) {
            // Treat an unreadable secrets file as "no key": construction still succeeds, the first
            // call surfaces a clear configuration error. Do not log the cause at INFO.
            log.debug("Could not read {} while loading the Anthropic API key", SECRETS_RESOURCE, e);
        }
        String key = properties.getProperty(API_KEY_PROPERTY);
        return (key == null) ? null : key.trim();
    }
}
