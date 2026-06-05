package com.lit.fire.flame.nlq.llm;

/**
 * Pluggable, provider-neutral interface to a large-language-model completion endpoint.
 *
 * <p>The Ask engine talks to models only through this interface so the underlying provider (Claude
 * first; an OpenAI or local implementation could follow) can be swapped without touching callers.
 * The active implementation is chosen in {@code nlq.config} from {@code aura.ask.llm-provider}.
 *
 * <p>Implementations must keep the contract provider-neutral: no Claude/OpenAI-specific types appear
 * on {@link LlmRequest} or {@link LlmResponse}, and structured output is requested with a generic
 * JSON Schema rather than any provider's tool format.
 */
public interface LlmClient {

    /**
     * Run a single-turn completion.
     *
     * @param request the prompt and generation parameters; must not be {@code null}
     * @return the model's text and/or structured output plus token usage
     * @throws LlmException if the provider is misconfigured, the call fails or times out, or the
     *                      response cannot be interpreted. The {@link LlmException.Kind} distinguishes
     *                      the cases; the message never contains the API key or prompt contents.
     */
    LlmResponse complete(LlmRequest request) throws LlmException;
}
