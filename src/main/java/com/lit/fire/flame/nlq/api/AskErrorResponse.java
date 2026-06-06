package com.lit.fire.flame.nlq.api;

/**
 * The body returned by {@code POST /api/ask} for a hard failure (a non-2xx that is not a
 * clarification). It carries a single <b>sanitized</b> {@link #getError() error} message — never the
 * target connection's credentials, the raw driver text, the prompt, or a stack trace — so it is safe
 * to surface to a client. The HTTP status conveys the failure category.
 *
 * <p>It also carries the {@link #getRequestId() requestId} (F10) that the audit log recorded for this
 * request, so an operator can correlate the client-visible failure with the server-side audit line.
 */
public class AskErrorResponse {

    private final String error;
    private final String requestId;

    public AskErrorResponse(String error, String requestId) {
        this.error = error;
        this.requestId = requestId;
    }

    /** A short, sanitized description of what went wrong. */
    public String getError() {
        return error;
    }

    /** The correlation id shared with the audit log; may be {@code null} for a pre-pipeline rejection. */
    public String getRequestId() {
        return requestId;
    }
}
