package com.lit.fire.flame.nlq.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a {@link SkipList} to an already-built {@link DatabaseSchema}, producing the same result the
 * {@link SchemaIntrospector} would have produced had that skip-list been in force during introspection:
 * skipped tables and columns are removed, and surviving columns get their {@code masked} flag set.
 *
 * <p>Used on the read path of the schema cache (F13): the cache stores each database's schema filtered
 * by its <em>request-independent</em> base skip-list, and this filter then removes any additional
 * <em>per-request</em> skips before the schema is shown to the model — so a request-skipped object is
 * never visible even when answering from a cache built without that request's skips.
 */
public final class SchemaFilter {

    private SchemaFilter() {
    }

    /**
     * Return a copy of {@code schema} with {@code skipList}'s skipped tables/columns removed and masked
     * columns flagged. If nothing changes, {@code schema} is returned unchanged.
     */
    public static DatabaseSchema apply(DatabaseSchema schema, SkipList skipList) {
        if (schema == null || skipList == null || skipList.isEmpty()) {
            return schema;
        }
        List<TableInfo> kept = new ArrayList<>();
        for (TableInfo table : schema.getTables()) {
            if (skipList.isTableSkipped(table.getSchema(), table.getName())) {
                continue;
            }
            kept.add(filterColumns(table, skipList));
        }
        return new DatabaseSchema(kept, schema.getProductName(), schema.getDialect());
    }

    private static TableInfo filterColumns(TableInfo table, SkipList skipList) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (ColumnInfo column : table.getColumns()) {
            if (skipList.isColumnSkipped(table.getSchema(), table.getName(), column.getName())) {
                continue;
            }
            boolean masked = column.isMasked()
                    || skipList.isColumnMasked(table.getSchema(), table.getName(), column.getName());
            columns.add(new ColumnInfo(column.getName(), column.getSqlType(), column.getTypeName(),
                    column.isNullable(), masked));
        }
        return new TableInfo(table.getSchema(), table.getName(), columns,
                table.getPrimaryKeys(), table.getForeignKeys());
    }
}
