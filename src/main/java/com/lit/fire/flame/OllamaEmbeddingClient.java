package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sentence-embedding client for a local Ollama instance, called directly over
 * {@link java.net.http.HttpClient} with Gson — mirrors {@code ClaudeLlmClient}'s conventions.
 * No API key: Ollama runs on localhost and nothing leaves the machine.
 */
@Component
public class OllamaEmbeddingClient {

    // nomic-embed-text is instruction-tuned: prefixing the task keeps corpus and query embeddings
    // in the same representation space. "clustering" is the correct task for nearest-neighbor
    // grouping (as opposed to "search_query"/"search_document", which are for asymmetric retrieval).
    private static final String TASK_PREFIX = "clustering: ";
    private static final int BATCH_SIZE = 32;

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public OllamaEmbeddingClient(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.embedding-model:nomic-embed-text}") String model) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> results = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> chunk = texts.subList(start, Math.min(start + BATCH_SIZE, texts.size()));
            results.addAll(embedChunk(chunk));
        }
        return results;
    }

    private List<float[]> embedChunk(List<String> chunk) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray input = new JsonArray();
        for (String t : chunk) {
            input.add(TASK_PREFIX + (t == null ? "" : t));
        }
        body.add("input", input);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embed"))
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Ollama embeddings call failed: HTTP " + response.statusCode() + " " + response.body());
            }
            JsonObject root = gson.fromJson(response.body(), JsonObject.class);
            JsonArray embeddings = root.getAsJsonArray("embeddings");
            List<float[]> out = new ArrayList<>(embeddings.size());
            for (JsonElement el : embeddings) {
                JsonArray vec = el.getAsJsonArray();
                float[] arr = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    arr[i] = vec.get(i).getAsFloat();
                }
                out.add(arr);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Ollama embeddings call failed (transport error) — is Ollama running at " + baseUrl + "?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama embeddings call interrupted", e);
        }
    }
}
