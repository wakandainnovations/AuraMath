package com.lit.fire.flame.nlq.schema;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The effective set of tables and columns the Ask engine must treat as invisible: they are removed
 * from the introspected {@link DatabaseSchema} (so the model never sees them) and are re-enforced
 * later at SQL validation/execution.
 *
 * <p>The effective list is the <b>union</b> of the server-side
 * {@code AskEngineProperties.defaultSkipTables} and the per-request {@code skipTables}/{@code
 * skipColumns}; build it with {@link #from(Collection, Collection, Collection)}.
 *
 * <p><b>Matching is case-insensitive and schema-qualified-aware.</b> A table entry may be either a
 * bare name ({@code users}) — matching that table in any schema — or schema-qualified
 * ({@code public.users}) — matching only that schema's table. A column entry may be
 * {@code table.column} or {@code schema.table.column}, with the same semantics.
 */
public final class SkipList {

    private final Set<String> skippedTables;
    private final Set<String> skippedColumns;

    private SkipList(Set<String> skippedTables, Set<String> skippedColumns) {
        this.skippedTables = skippedTables;
        this.skippedColumns = skippedColumns;
    }

    /**
     * Build the effective skip-list from the server default tables plus the per-request table and
     * column lists. Any argument may be {@code null}; blank entries are ignored.
     */
    public static SkipList from(Collection<String> defaultSkipTables,
                                Collection<String> requestSkipTables,
                                Collection<String> requestSkipColumns) {
        Set<String> tables = new LinkedHashSet<>();
        addNormalized(tables, defaultSkipTables);
        addNormalized(tables, requestSkipTables);
        Set<String> columns = new LinkedHashSet<>();
        addNormalized(columns, requestSkipColumns);
        return new SkipList(tables, columns);
    }

    /** An empty skip-list — nothing is skipped. */
    public static SkipList empty() {
        return new SkipList(new LinkedHashSet<>(), new LinkedHashSet<>());
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

    /**
     * Whether the given table is skipped, matching either a bare-name entry or a
     * {@code schema.table} entry (case-insensitively).
     */
    public boolean isTableSkipped(String schema, String table) {
        if (table == null) {
            return false;
        }
        String name = table.toLowerCase(Locale.ROOT);
        if (skippedTables.contains(name)) {
            return true;
        }
        if (schema != null && !schema.isEmpty()) {
            return skippedTables.contains(schema.toLowerCase(Locale.ROOT) + "." + name);
        }
        return false;
    }

    /**
     * Whether the given column is skipped, matching either a {@code table.column} entry or a
     * {@code schema.table.column} entry (case-insensitively).
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
        if (schema != null && !schema.isEmpty()) {
            return skippedColumns.contains(schema.toLowerCase(Locale.ROOT) + "." + t + "." + c);
        }
        return false;
    }

    public boolean isEmpty() {
        return skippedTables.isEmpty() && skippedColumns.isEmpty();
    }

    /**
     * The effective skipped-table entries, normalized to lower-case (bare {@code table} or
     * {@code schema.table}). Unmodifiable; primarily for re-enforcement at SQL validation.
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
}
