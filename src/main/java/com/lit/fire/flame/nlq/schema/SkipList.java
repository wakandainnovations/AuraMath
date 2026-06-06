package com.lit.fire.flame.nlq.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The effective sensitive-data policy the Ask engine enforces (F2 + F9). It carries three kinds of
 * rule, all derived once per request and re-checked at every layer:
 *
 * <ul>
 *   <li><b>Skipped tables / columns</b> — invisible objects: removed from the introspected
 *       {@link DatabaseSchema} (so the model never sees them) and re-enforced at SQL
 *       validation/execution and, belt-and-suspenders, dropped from result rows.</li>
 *   <li><b>Masked columns</b> — columns that MAY be aggregated (e.g. {@code count(email)}) but whose
 *       <i>raw</i> values must never be projected or returned. They stay visible in the schema (so the
 *       model can aggregate over them), the guard rejects any raw projection of them, and any value
 *       that still reaches a result is redacted.</li>
 *   <li><b>Auto-skip name patterns</b> — case-insensitive regexes (e.g.
 *       {@code .*(password|secret|ssn|token).*}) matched against bare table and column names; a match
 *       is treated exactly like an explicit skip. They are on by default and configurable.</li>
 * </ul>
 *
 * <p><b>Precedence.</b> Skip beats mask: a column that matches both a skip rule (explicit or an
 * auto-skip pattern) and a mask rule is <i>skipped</i> — removed entirely — never merely masked.
 *
 * <p>The effective list is the <b>union</b> of the per-request entries, the server-side
 * {@code aura.ask.default-skip-*} / {@code masked-columns} entries, and the auto-skip patterns; build
 * it with {@link #builder()}. The legacy {@link #from(Collection, Collection, Collection)} factory
 * (tables/columns only, no masks or patterns) is retained for callers that need just those.
 *
 * <p><b>Matching is case-insensitive and schema-qualified-aware.</b> A table entry may be either a
 * bare name ({@code users}) — matching that table in any schema — or schema-qualified
 * ({@code public.users}) — matching only that schema's table. A column entry may be
 * {@code table.column} or {@code schema.table.column}, with the same semantics.
 */
public final class SkipList {

    private final Set<String> skippedTables;
    private final Set<String> skippedColumns;
    private final Set<String> maskedColumns;

    /** Compiled auto-skip name patterns, matched (full-match, case-insensitive) against bare names. */
    private final List<Pattern> autoSkipPatterns;

    /** Leaf column names of the skipped/masked entries, for fast result-redaction lookups. */
    private final Set<String> skippedColumnLeaves;
    private final Set<String> maskedColumnLeaves;
    /** Bare table names that own at least one masked column ({@code null} = a table-less masked entry). */
    private final Set<String> maskedColumnTableLeaves;
    private final boolean hasTablelessMaskedColumn;

    private SkipList(Set<String> skippedTables, Set<String> skippedColumns, Set<String> maskedColumns,
                    List<Pattern> autoSkipPatterns) {
        this.skippedTables = skippedTables;
        this.skippedColumns = skippedColumns;
        this.maskedColumns = maskedColumns;
        this.autoSkipPatterns = autoSkipPatterns;

        this.skippedColumnLeaves = leavesOf(skippedColumns);
        this.maskedColumnLeaves = leavesOf(maskedColumns);
        Set<String> tableLeaves = new LinkedHashSet<>();
        boolean tableless = false;
        for (String entry : maskedColumns) {
            String tableLeaf = tableLeafOf(entry);
            if (tableLeaf == null) {
                tableless = true;
            } else {
                tableLeaves.add(tableLeaf);
            }
        }
        this.maskedColumnTableLeaves = tableLeaves;
        this.hasTablelessMaskedColumn = tableless;
    }

    /**
     * Build the effective skip-list from the server default tables plus the per-request table and
     * column lists. Any argument may be {@code null}; blank entries are ignored. No masked columns
     * and no auto-skip patterns are configured — use {@link #builder()} for those.
     */
    public static SkipList from(Collection<String> defaultSkipTables,
                                Collection<String> requestSkipTables,
                                Collection<String> requestSkipColumns) {
        return builder()
                .addSkipTables(defaultSkipTables)
                .addSkipTables(requestSkipTables)
                .addSkipColumns(requestSkipColumns)
                .build();
    }

    /** An empty skip-list — nothing is skipped, masked, or auto-skipped. */
    public static SkipList empty() {
        return new SkipList(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(),
                Collections.emptyList());
    }

    /** Start building a full F9 skip-list (skips + masks + auto-skip patterns). */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether the given table is skipped — by an explicit bare-name or {@code schema.table} entry, or
     * by an auto-skip name pattern matching the bare table name (case-insensitively).
     */
    public boolean isTableSkipped(String schema, String table) {
        if (table == null) {
            return false;
        }
        String name = table.toLowerCase(Locale.ROOT);
        if (skippedTables.contains(name)) {
            return true;
        }
        if (schema != null && !schema.isEmpty()
                && skippedTables.contains(schema.toLowerCase(Locale.ROOT) + "." + name)) {
            return true;
        }
        return matchesAutoSkip(name);
    }

    /**
     * Whether the given column is skipped — by an explicit {@code table.column} /
     * {@code schema.table.column} entry, or by an auto-skip name pattern matching the bare column name
     * (case-insensitively).
     */
    public boolean isColumnSkipped(String schema, String table, String column) {
        if (table == null || column == null) {
            return false;
        }
        String t = table.toLowerCase(Locale.ROOT);
        String c = column.toLowerCase(Locale.ROOT);
        if (skippedColumns.contains(t + "." + c)) {
            return true;
        }
        if (schema != null && !schema.isEmpty()
                && skippedColumns.contains(schema.toLowerCase(Locale.ROOT) + "." + t + "." + c)) {
            return true;
        }
        return matchesAutoSkip(c);
    }

    /**
     * Whether the given column is <b>masked</b>: it may be aggregated but its raw values must never be
     * returned. Skip wins — a column that is also skipped (explicitly or by pattern) returns
     * {@code false} here, because it is removed entirely rather than masked.
     */
    public boolean isColumnMasked(String schema, String table, String column) {
        if (table == null || column == null) {
            return false;
        }
        if (isColumnSkipped(schema, table, column)) {
            return false;
        }
        String t = table.toLowerCase(Locale.ROOT);
        String c = column.toLowerCase(Locale.ROOT);
        if (maskedColumns.contains(t + "." + c)) {
            return true;
        }
        return schema != null && !schema.isEmpty()
                && maskedColumns.contains(schema.toLowerCase(Locale.ROOT) + "." + t + "." + c);
    }

    /** Whether any masked column is configured at all. */
    public boolean hasMaskedColumns() {
        return !maskedColumns.isEmpty();
    }

    /**
     * Whether the bare table name owns at least one masked column. A table-less masked entry (bare
     * {@code column}) is treated as belonging to <i>any</i> table, so this returns {@code true} for it.
     * Used by the guard to reject a star projection that could surface a masked value.
     */
    public boolean tableHasMaskedColumn(String tableLeaf) {
        if (hasTablelessMaskedColumn) {
            return true;
        }
        return tableLeaf != null && maskedColumnTableLeaves.contains(tableLeaf.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a bare (leaf) column name matches any masked-column entry. Leaf-only matching — used by
     * the guard's projection check and the result redactor — is intentionally conservative: it can
     * match an identically named column on a non-masked table, the correct fail-closed direction.
     */
    public boolean isMaskedColumnLeaf(String columnLeaf) {
        return columnLeaf != null && maskedColumnLeaves.contains(columnLeaf.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a bare (leaf) column name matches any skipped-column entry (explicit or by auto-skip
     * pattern). Used by the result redactor to drop a skipped column that slipped through (e.g. via a
     * {@code SELECT *} that the database expanded to include it).
     */
    public boolean isSkippedColumnLeaf(String columnLeaf) {
        if (columnLeaf == null) {
            return false;
        }
        String c = columnLeaf.toLowerCase(Locale.ROOT);
        return skippedColumnLeaves.contains(c) || matchesAutoSkip(c);
    }

    public boolean isEmpty() {
        return skippedTables.isEmpty() && skippedColumns.isEmpty() && maskedColumns.isEmpty()
                && autoSkipPatterns.isEmpty();
    }

    /**
     * The effective skipped-table entries, normalized to lower-case (bare {@code table} or
     * {@code schema.table}). Unmodifiable; does not include pattern-driven matches (which are name-based
     * and resolved at lookup time).
     */
    public Set<String> skippedTables() {
        return Collections.unmodifiableSet(skippedTables);
    }

    /**
     * The effective skipped-column entries, normalized to lower-case ({@code table.column} or
     * {@code schema.table.column}). Unmodifiable; primarily for re-enforcement at SQL validation.
     */
    public Set<String> skippedColumns() {
        return Collections.unmodifiableSet(skippedColumns);
    }

    /**
     * The effective masked-column entries, normalized to lower-case ({@code table.column} or
     * {@code schema.table.column}). Unmodifiable.
     */
    public Set<String> maskedColumns() {
        return Collections.unmodifiableSet(maskedColumns);
    }

    private boolean matchesAutoSkip(String bareName) {
        if (bareName == null || autoSkipPatterns.isEmpty()) {
            return false;
        }
        for (Pattern pattern : autoSkipPatterns) {
            if (pattern.matcher(bareName).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> leavesOf(Set<String> entries) {
        Set<String> leaves = new LinkedHashSet<>();
        for (String entry : entries) {
            int dot = entry.lastIndexOf('.');
            String leaf = (dot < 0) ? entry : entry.substring(dot + 1);
            if (!leaf.isEmpty()) {
                leaves.add(leaf);
            }
        }
        return leaves;
    }

    /** The owning-table leaf of a column entry ({@code [schema.]table.column}); {@code null} if none. */
    private static String tableLeafOf(String entry) {
        String[] parts = entry.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : null;
    }

    private static void addNormalized(Set<String> target, Collection<String> source) {
        if (source == null) {
            return;
        }
        for (String entry : source) {
            if (entry == null) {
                continue;
            }
            String normalized = entry.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                target.add(normalized);
            }
        }
    }

    /** Builder for a full F9 skip-list. All {@code add*} methods union their input and ignore blanks. */
    public static final class Builder {
        private final Set<String> skipTables = new LinkedHashSet<>();
        private final Set<String> skipColumns = new LinkedHashSet<>();
        private final Set<String> maskedColumns = new LinkedHashSet<>();
        private final List<Pattern> autoSkipPatterns = new ArrayList<>();

        private Builder() {
        }

        public Builder addSkipTables(Collection<String> tables) {
            addNormalized(skipTables, tables);
            return this;
        }

        public Builder addSkipColumns(Collection<String> columns) {
            addNormalized(skipColumns, columns);
            return this;
        }

        public Builder addMaskedColumns(Collection<String> columns) {
            addNormalized(maskedColumns, columns);
            return this;
        }

        /**
         * Configure the auto-skip name patterns. When {@code enabled} is {@code false} the patterns are
         * dropped entirely (the toggle), so no pattern-driven skipping happens.
         *
         * @throws IllegalArgumentException if a pattern string is not a valid regex
         */
        public Builder autoSkipPatterns(Collection<String> patterns, boolean enabled) {
            if (!enabled || patterns == null) {
                return this;
            }
            for (String raw : patterns) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String pattern = raw.trim();
                try {
                    autoSkipPatterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException(
                            "invalid aura.ask.auto-skip.patterns entry: " + pattern, e);
                }
            }
            return this;
        }

        public SkipList build() {
            return new SkipList(skipTables, skipColumns, maskedColumns, autoSkipPatterns);
        }
    }
}
