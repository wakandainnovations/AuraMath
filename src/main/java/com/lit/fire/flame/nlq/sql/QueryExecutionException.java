package com.lit.fire.flame.nlq.sql;

/**
 * Thrown by {@link QueryExecutionService} when a validated read-only query cannot be executed —
 * because the connection is not read-only, the SQL fails the defensive re-validation, the query is
 * cut off by the timeout, or the driver raises a {@link java.sql.SQLException}.
 *
 * <p>The {@link #getMessage() message} is deliberately <b>sanitized</b>: it never carries the
 * target connection's credentials, the raw driver exception text, or a stack trace, so it is safe to
 * surface to a client. The originating exception is preserved as the {@link #getCause() cause} for
 * server-side logging only. A typed {@link Kind} lets callers (and tests) react to the precise
 * failure without parsing the message.
 */
public class QueryExecutionException extends Exception {

    /** The category of execution failure. */
    public enum Kind {
        /** The supplied connection did not report {@link java.sql.Connection#isReadOnly()}. */
        NOT_READ_ONLY,
        /** The SQL failed the defensive re-run through {@link SqlSafetyGuard} before execution. */
        UNSAFE_SQL,
        /** The query exceeded the configured {@code queryTimeoutSeconds} and was cancelled. */
        TIMEOUT,
        /** The driver raised a {@link java.sql.SQLException} while executing or reading results. */
        EXECUTION
    }

    private final Kind kind;

    public QueryExecutionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public QueryExecutionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
