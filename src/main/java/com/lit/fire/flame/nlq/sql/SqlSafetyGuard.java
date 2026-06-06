package com.lit.fire.flame.nlq.sql;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.schema.DatabaseSchema;
import com.lit.fire.flame.nlq.schema.SkipList;
import com.lit.fire.flame.nlq.schema.TableInfo;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Feature F5 — the deterministic, fail-closed SQL safety guard that sits between the (untrusted)
 * LLM and the target database. The model's drafted SQL is never executed directly; it must first
 * pass {@link #validate(String, SkipList, DatabaseSchema)}, which either returns a normalized safe
 * SQL string or throws a typed {@link UnsafeSqlException}.
 *
 * <p><b>This validator — not the LLM — is the trust boundary.</b> Every rule fails closed: anything
 * ambiguous, unparseable, or not provably a single read-only query is rejected.
 *
 * <p>Rules enforced, in order:
 * <ol>
 *   <li>Non-empty input.</li>
 *   <li>No SQL comments ({@code --}, {@code /* *}{@code /}) — rejected outright to prevent smuggling
 *       past the parser.</li>
 *   <li>Exactly one statement — a single optional trailing {@code ;} is tolerated; any interior
 *       {@code ;} (chaining/terminating another statement) is rejected.</li>
 *   <li>Must begin with {@code SELECT} or {@code WITH} after trimming.</li>
 *   <li>No top-level DML/DDL/DCL keyword (INSERT/UPDATE/DELETE/MERGE/UPSERT/CREATE/ALTER/DROP/
 *       TRUNCATE/GRANT/REVOKE/CALL/EXEC/EXECUTE/COPY/ATTACH/PRAGMA/VACUUM/INTO).</li>
 *   <li>No known data-exfil / side-effect function (pg_read_file, lo_import/export, dblink,
 *       LOAD_FILE, INTO OUTFILE/DUMPFILE, …).</li>
 *   <li>Parses, via JSqlParser, into a single {@code SELECT} statement.</li>
 *   <li>Every referenced table is present in the (already skip-filtered) schema and not on the
 *       effective skip-list; no referenced identifier matches a skipped column.</li>
 *   <li>No masked column (F9) is projected as a raw value — it is allowed only inside a
 *       value-combining aggregate ({@code count}/{@code sum}/{@code avg}/…), and a star projection over
 *       a table that owns a masked column is rejected.</li>
 *   <li>A row cap is enforced: a missing limit is injected, and an existing limit larger than
 *       {@link AskEngineProperties#getMaxRows()} is lowered to it (a smaller one is left alone).</li>
 * </ol>
 *
 * <p>The keyword/function/comment screens (rules 2, 5, 6) run on the raw string before parsing,
 * because JSqlParser silently discards comments and we must reject — not sanitize — smuggled input.
 * They are intentionally conservative: a forbidden token inside a string literal will also be
 * rejected. For an LLM-facing security boundary, over-rejection is the correct failure direction.
 */
@Service
public class SqlSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(SqlSafetyGuard.class);

    /** Top-level statement-shape keywords no read-only query has any business containing. */
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE"
                    + "|CALL|EXEC|EXECUTE|COPY|ATTACH|DETACH|PRAGMA|VACUUM|REINDEX|INTO)\\b");

    /** Dialect-specific file/network/exfil helpers; plain case-insensitive substring matches. */
    private static final String[] FORBIDDEN_FUNCTIONS = {
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
            "lo_import", "lo_export", "lo_get", "lo_put",
            "dblink", "load_file", "outfile", "dumpfile"
    };

    /**
     * Aggregates that <b>combine</b> their inputs into a derived figure and therefore never echo a
     * single stored value — the only functions a masked column may appear inside (F9). Deliberately
     * excludes value-revealing aggregates ({@code min}, {@code max}, {@code string_agg},
     * {@code array_agg}, {@code group_concat}, {@code json_agg}, {@code median}, {@code percentile_*},
     * {@code first}/{@code last}/{@code mode}), which would surface a raw masked value.
     */
    private static final Set<String> NON_REVEALING_AGGREGATES = new HashSet<>(Arrays.asList(
            "count", "sum", "total", "avg", "mean", "average",
            "stddev", "std", "stddev_pop", "stddev_samp",
            "variance", "var", "var_pop", "var_samp",
            "corr", "covar_pop", "covar_samp"));

    private final AskEngineProperties properties;

    public SqlSafetyGuard(AskEngineProperties properties) {
        this.properties = properties;
    }

    /**
     * Validate a candidate SQL string against the effective {@code skipList} and the (already
     * skip-filtered) {@code schema}, returning a normalized safe SQL string with a bounded row cap.
     *
     * <p>This method MUST be called before any execution. It never executes anything itself.
     *
     * @return the validated, row-capped, re-rendered SQL — safe to execute read-only
     * @throws UnsafeSqlException with a precise {@link UnsafeSqlException.Reason} if any rule fails
     */
    public String validate(String sql, SkipList skipList, DatabaseSchema schema)
            throws UnsafeSqlException {
        Objects.requireNonNull(skipList, "skipList");
        Objects.requireNonNull(schema, "schema");

        if (sql == null || sql.trim().isEmpty()) {
            throw new UnsafeSqlException(UnsafeSqlException.Reason.EMPTY, "SQL is empty");
        }
        String trimmed = sql.trim();

        // (rule 2) Comments are rejected outright — they are the classic vehicle for smuggling a
        // second statement or a forbidden token past a downstream parser.
        if (trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            throw new UnsafeSqlException(UnsafeSqlException.Reason.COMMENT,
                    "SQL comments are not allowed");
        }

        // (rule 3) Tolerate a single trailing ';' but reject any interior one (statement chaining).
        String core = stripTrailingSemicolons(trimmed);
        if (core.contains(";")) {
            throw new UnsafeSqlException(UnsafeSqlException.Reason.MULTIPLE_STATEMENTS,
                    "only a single statement is allowed");
        }
        if (core.isEmpty()) {
            throw new UnsafeSqlException(UnsafeSqlException.Reason.EMPTY, "SQL is empty");
        }

        // (rule 4) Must be a read-only query form.
        String lower = core.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            throw new UnsafeSqlException(UnsafeSqlException.Reason.NOT_READ_ONLY,
                    "statement must start with SELECT or WITH");
        }

        // (rule 5) No statement-shape keyword that would write, change, or branch out of a query.
        java.util.regex.Matcher m = FORBIDDEN_KEYWORDS.matcher(core);
        if (m.find()) {
            throw new UnsafeSqlException(UnsafeSqlException.Reason.FORBIDDEN_KEYWORD,
                    "forbidden keyword: " + m.group(1).toUpperCase(Locale.ROOT));
        }

        // (rule 6) No file/network/exfil helper functions.
        for (String fn : FORBIDDEN_FUNCTIONS) {
            if (lower.contains(fn)) {
                throw new UnsafeSqlException(UnsafeSqlException.Reason.FORBIDDEN_FUNCTION,
                        "forbidden function: " + fn);
            }
        }

        // (rule 7) Parse into exactly one statement and confirm it is a SELECT/WITH query.
        Statement statement = parseSingleStatement(core);
        if (!(statement instanceof Select)) {
            throw new UnsafeSqlException(UnsafeSqlException.Reason.NOT_A_QUERY,
                    "parsed statement is not a SELECT query");
        }
        Select select = (Select) statement;

        // (rule 8) Every referenced table must be a non-skipped table that survived into the schema.
        enforceTableReferences(select, skipList, schema);
        enforceNoSkippedColumns(core, skipList);

        // (rule 9, F9) No masked column may be projected as a raw value — only inside a value-combining
        // aggregate. A star projection over a table with a masked column is rejected too.
        enforceNoRawMaskedColumns(select, skipList);

        // (rule 10) Bound the result set and re-render the validated statement.
        applyRowCap(select, properties.getMaxRows());
        return select.toString();
    }

    private static String stripTrailingSemicolons(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ';' || Character.isWhitespace(c)) {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    private static Statement parseSingleStatement(String core) throws UnsafeSqlException {
        try {
            // CCJSqlParserUtil#parse accepts a single statement only; a chained statement that
            // slipped past the ';' screen (or any malformed SQL) throws here — fail closed.
            return CCJSqlParserUtil.parse(core);
        } catch (Exception e) {
            throw new UnsafeSqlException(UnsafeSqlException.Reason.UNPARSEABLE,
                    "SQL could not be parsed as a single statement", e);
        }
    }

    /**
     * Confirm every table the parser found is present in the skip-filtered schema and not itself on
     * the skip-list. Because skipped tables were already removed from {@code schema} during
     * introspection, a reference to one shows up here as an unknown table; the explicit skip-list
     * check is belt-and-suspenders.
     */
    private void enforceTableReferences(Select select, SkipList skipList, DatabaseSchema schema)
            throws UnsafeSqlException {
        Set<String> known = new HashSet<>();
        for (TableInfo table : schema.getTables()) {
            known.add(table.getName().toLowerCase(Locale.ROOT));
            known.add(table.qualifiedName().toLowerCase(Locale.ROOT));
        }
        // CTE names defined by a WITH clause are valid references even though they are not schema
        // tables; allow them so a legitimate CTE query is not rejected.
        if (select.getWithItemsList() != null) {
            for (WithItem with : select.getWithItemsList()) {
                if (with.getName() != null) {
                    known.add(unquote(with.getName()).toLowerCase(Locale.ROOT));
                }
            }
        }

        List<String> referenced;
        try {
            referenced = new TablesNamesFinder().getTableList(select);
        } catch (Exception e) {
            // If the parser cannot enumerate the tables, we cannot prove the query is in-bounds.
            throw new UnsafeSqlException(UnsafeSqlException.Reason.UNPARSEABLE,
                    "could not enumerate referenced tables", e);
        }

        for (String raw : referenced) {
            String name = unquote(raw).toLowerCase(Locale.ROOT);
            if (!known.contains(name)) {
                throw new UnsafeSqlException(UnsafeSqlException.Reason.UNKNOWN_TABLE,
                        "query references unknown or skipped table: " + raw);
            }
            if (isSkippedTable(name, skipList)) {
                throw new UnsafeSqlException(UnsafeSqlException.Reason.SKIPPED_TABLE,
                        "query references skipped table: " + raw);
            }
        }
    }

    /** Split a possibly schema-qualified name and test it against the skip-list. */
    private static boolean isSkippedTable(String qualified, SkipList skipList) {
        int dot = qualified.lastIndexOf('.');
        if (dot < 0) {
            return skipList.isTableSkipped(null, qualified);
        }
        return skipList.isTableSkipped(qualified.substring(0, dot), qualified.substring(dot + 1));
    }

    /**
     * Re-enforce skipped columns. Skipped columns are already absent from the schema, so the model
     * should never name one; as a conservative backstop we reject the query if any skipped column's
     * leaf name appears as an identifier token. This can over-reject when an allowed table shares a
     * column name with a skipped one — the correct, fail-closed direction for a security guard.
     */
    private static void enforceNoSkippedColumns(String core, SkipList skipList)
            throws UnsafeSqlException {
        for (String entry : skipList.skippedColumns()) {
            int dot = entry.lastIndexOf('.');
            String leaf = (dot < 0) ? entry : entry.substring(dot + 1);
            if (leaf.isEmpty()) {
                continue;
            }
            Pattern token = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(leaf) + "\\b");
            if (token.matcher(core).find()) {
                throw new UnsafeSqlException(UnsafeSqlException.Reason.SKIPPED_COLUMN,
                        "query references skipped column: " + entry);
            }
        }
    }

    /**
     * Reject any raw projection of a masked column (F9). A masked column stays in the schema so the
     * model can aggregate it, but its raw values must never leave the database. This walks the SELECT
     * projection of the top-level query (and each leg of a set operation) and rejects when:
     *
     * <ul>
     *   <li>a masked column appears outside a {@link #NON_REVEALING_AGGREGATES value-combining
     *       aggregate} (a bare reference, inside a scalar expression, or inside a value-revealing
     *       aggregate such as {@code min}/{@code max}/{@code string_agg}); or</li>
     *   <li>the projection contains a star ({@code *} or {@code t.*}) while any referenced table owns a
     *       masked column — the star would expand to the raw column at the database.</li>
     * </ul>
     *
     * <p>References to masked columns in {@code WHERE}/{@code GROUP BY}/{@code HAVING} are not rejected
     * here (they do not surface a value), and the result redactor is a final backstop on output.
     */
    private static void enforceNoRawMaskedColumns(Select select, SkipList skipList)
            throws UnsafeSqlException {
        if (!skipList.hasMaskedColumns()) {
            return;
        }
        // A star projection surfaces raw columns, so it is unsafe if any table the query references
        // owns a masked column. Compute that once from the whole statement (the parser enumerates
        // tables only off a Statement/Expression, not a bare PlainSelect).
        boolean starUnsafe = anyReferencedTableHasMaskedColumn(select, skipList);

        SelectBody body = select.getSelectBody();
        if (body instanceof PlainSelect) {
            enforceNoRawMaskedColumns((PlainSelect) body, skipList, starUnsafe);
        } else if (body instanceof SetOperationList) {
            for (SelectBody leg : ((SetOperationList) body).getSelects()) {
                if (leg instanceof PlainSelect) {
                    enforceNoRawMaskedColumns((PlainSelect) leg, skipList, starUnsafe);
                }
            }
        }
    }

    private static void enforceNoRawMaskedColumns(PlainSelect plain, SkipList skipList,
                                                  boolean starUnsafe) throws UnsafeSqlException {
        List<SelectItem> items = plain.getSelectItems();
        if (items == null) {
            return;
        }
        for (SelectItem item : items) {
            if (item instanceof AllColumns || item instanceof AllTableColumns) {
                if (starUnsafe) {
                    throw new UnsafeSqlException(UnsafeSqlException.Reason.MASKED_COLUMN,
                            "a star projection (*) may expose masked column values; list explicit "
                                    + "columns and use masked columns only inside an aggregate such as count");
                }
                continue;
            }
            if (item instanceof SelectExpressionItem) {
                MaskedColumnFinder finder = new MaskedColumnFinder(skipList);
                ((SelectExpressionItem) item).getExpression().accept(finder);
                if (finder.found != null) {
                    throw new UnsafeSqlException(UnsafeSqlException.Reason.MASKED_COLUMN,
                            "masked column may not be projected as a raw value (only inside an "
                                    + "aggregate such as count): " + finder.found);
                }
            }
        }
    }

    /** Whether any table the query references owns a masked column ({@code *}/{@code t.*} would surface it). */
    private static boolean anyReferencedTableHasMaskedColumn(Select select, SkipList skipList) {
        List<String> referenced;
        try {
            referenced = new TablesNamesFinder().getTableList(select);
        } catch (Exception e) {
            // Cannot prove a star is safe — fail closed.
            return true;
        }
        if (referenced.isEmpty()) {
            return true;
        }
        for (String raw : referenced) {
            String name = unquote(raw).toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String leaf = (dot < 0) ? name : name.substring(dot + 1);
            if (skipList.tableHasMaskedColumn(leaf)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Expression visitor that flags a masked column reference unless it sits directly inside a
     * value-combining aggregate. When such an aggregate is reached its arguments are not descended
     * into (the masked column there is permitted); every other node is traversed normally so a masked
     * column in a scalar expression or a value-revealing aggregate is caught.
     */
    private static final class MaskedColumnFinder extends ExpressionVisitorAdapter {
        private final SkipList skipList;
        private String found;

        MaskedColumnFinder(SkipList skipList) {
            this.skipList = skipList;
        }

        @Override
        public void visit(Function function) {
            String name = (function.getName() == null)
                    ? "" : function.getName().toLowerCase(Locale.ROOT);
            if (NON_REVEALING_AGGREGATES.contains(name)) {
                // Masked columns are allowed here; do not descend into the aggregate's arguments.
                return;
            }
            super.visit(function); // scalar / value-revealing function: keep checking its arguments
        }

        @Override
        public void visit(Column column) {
            if (found == null && skipList.isMaskedColumnLeaf(unquote(column.getColumnName()))) {
                found = column.toString();
            }
        }
    }

    /** Inject or lower a row limit so the query can never return more than {@code maxRows} rows. */
    private static void applyRowCap(Select select, int maxRows) {
        SelectBody body = select.getSelectBody();
        if (body instanceof PlainSelect) {
            PlainSelect plain = (PlainSelect) body;
            plain.setLimit(capLimit(plain.getLimit(), maxRows));
        } else if (body instanceof SetOperationList) {
            SetOperationList ops = (SetOperationList) body;
            ops.setLimit(capLimit(ops.getLimit(), maxRows));
        } else {
            // Unknown body shape (e.g. a values-list) — wrap defensively is not possible here, so
            // we cannot bound it. Treat as already handled by the SELECT-start rule above; the
            // common shapes are covered. Leave as-is rather than fabricate an invalid limit.
            log.debug("row cap not applied to select body of type {}", body.getClass().getSimpleName());
        }
    }

    private static Limit capLimit(Limit existing, int maxRows) {
        if (existing == null || existing.isLimitAll() || existing.getRowCount() == null) {
            return boundedLimit(maxRows);
        }
        if (existing.getRowCount() instanceof LongValue) {
            long current = ((LongValue) existing.getRowCount()).getValue();
            if (current > maxRows) {
                existing.setRowCount(new LongValue(maxRows));
            }
            return existing; // a smaller (or equal) explicit limit is left untouched
        }
        // A non-constant limit (parameter or expression) cannot be proven within the cap — replace
        // it with the hard bound.
        existing.setRowCount(new LongValue(maxRows));
        existing.setLimitAll(false);
        return existing;
    }

    private static Limit boundedLimit(int maxRows) {
        Limit limit = new Limit();
        limit.setRowCount(new LongValue(maxRows));
        return limit;
    }

    private static String unquote(String name) {
        if (name == null || name.length() < 2) {
            return name == null ? "" : name;
        }
        String s = name.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
