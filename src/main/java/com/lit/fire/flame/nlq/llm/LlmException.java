package com.lit.fire.flame.nlq.llm;

/**
 * Typed failure from an {@link LlmClient} call. The {@link Kind} lets callers branch on the broad
 * failure category without parsing messages, while the message stays deliberately terse and
 * non-leaking — it never contains the API key, the prompt, or schema names.
 */
public class LlmException extends Exception {

    /** Broad category of an LLM call failure, for programmatic handling. */
    public enum Kind {
        /** No API key configured, or the provider is otherwise not usable. */
        CONFIGURATION,
        /** Provider returned 429 (rate limited / quota), after the bounded retry was exhausted. */
        RATE_LIMITED,
        /** The request timed out or the connection failed at the transport layer. */
        TIMEOUT,
        /** Provider returned a non-2xx response other than 429 (e.g. 400, 401, 5xx). */
        HTTP_ERROR,
        /** A 2xx response that could not be parsed into the expected shape. */
        BAD_RESPONSE
    }

    private final Kind kind;

    /** HTTP status code when {@link #kind} is {@link Kind#HTTP_ERROR} or {@link Kind#RATE_LIMITED}; -1 otherwise. */
    private final int statusCode;

    public LlmException(Kind kind, String message) {
        this(kind, -1, message, null);
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        this(kind, -1, message, cause);
    }

    public LlmException(Kind kind, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public Kind getKind() {
        return kind;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
