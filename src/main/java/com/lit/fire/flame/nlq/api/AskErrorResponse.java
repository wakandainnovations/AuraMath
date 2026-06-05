package com.lit.fire.flame.nlq.api;

/**
 * The body returned by {@code POST /api/ask} for a hard failure (a non-2xx that is not a
 * clarification). It carries a single <b>sanitized</b> {@link #getError() error} message — never the
 * target connection's credentials, the raw driver text, the prompt, or a stack trace — so it is safe
 * to surface to a client. The HTTP status conveys the failure category.
 */
public class AskErrorResponse {

    private final String error;

    public AskErrorResponse(String error) {
        this.error = error;
    }

    /** A short, sanitized description of what went wrong. */
    public String getError() {
        return error;
    }
}
