package com.lit.fire.flame.nlq.sql;

/**
 * Thrown by {@link SqlSafetyGuard} when a candidate SQL string fails any read-only / skip-list /
 * single-statement guard rule. Carries a typed {@link Reason} so callers (and tests) can react to
 * the precise rule that fired, not just a free-text message.
 *
 * <p>The guard is <b>fail-closed</b>: anything ambiguous, unparseable, or not provably a single
 * read-only query raises this exception rather than being passed through.
 */
public class UnsafeSqlException extends Exception {

    /** The specific guard rule that rejected the SQL. */
    public enum Reason {
        /** Null, blank, or whitespace-only input. */
        EMPTY,
        /** Contains a SQL comment ({@code --} or {@code /* *}{@code /}) — rejected to stop smuggling. */
        COMMENT,
        /** More than one statement (an interior {@code ;} chaining/terminating statements). */
        MULTIPLE_STATEMENTS,
        /** Does not begin with {@code SELECT} or {@code WITH} after trimming. */
        NOT_READ_ONLY,
        /** Contains a top-level DML/DDL/DCL keyword (e.g. {@code INSERT}, {@code DROP}, {@code INTO}). */
        FORBIDDEN_KEYWORD,
        /** Contains a known data-exfil / side-effect function (e.g. {@code pg_read_file}, {@code LOAD_FILE}). */
        FORBIDDEN_FUNCTION,
        /** The SQL could not be parsed into a single statement by the SQL parser. */
        UNPARSEABLE,
        /** Parsed, but the statement is not a {@code SELECT}/{@code WITH} query. */
        NOT_A_QUERY,
        /** References a table that is not present in the (already skip-filtered) schema. */
        UNKNOWN_TABLE,
        /** References a table that is on the effective skip-list. */
        SKIPPED_TABLE,
        /** References a column that is on the effective skip-list. */
        SKIPPED_COLUMN,
        /** Projects the raw value of a masked column (allowed only inside a value-combining aggregate). */
        MASKED_COLUMN
    }

    private final Reason reason;

    public UnsafeSqlException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public UnsafeSqlException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
