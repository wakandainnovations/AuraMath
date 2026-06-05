package com.lit.fire.flame.nlq.schema;

import org.springframework.stereotype.Component;

import java.util.StringJoiner;

/**
 * Serializes a {@link DatabaseSchema} into a compact, token-efficient text form for an LLM prompt.
 *
 * <p>The output is a terse CREATE-TABLE-like listing — one table per block — with column types,
 * {@code PK}/{@code NOT NULL} markers, and inline {@code FK -> ref} pointers. It is the exact text
 * later features hand to the model.
 *
 * <p>It is rendered from an already skip-list-filtered schema, so skipped tables and columns are
 * structurally absent and <b>cannot</b> appear here. The renderer emits no row data — structure
 * only.
 */
@Component
public class SchemaRenderer {

    /**
     * Render the schema. Tables already excluded by the skip-list are not present in {@code schema}
     * and therefore never appear in the output.
     */
    public String render(DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- dialect: ").append(schema.getDialect()).append('\n');
        if (schema.getTables().isEmpty()) {
            sb.append("-- (no user tables visible)\n");
            return sb.toString();
        }
        for (TableInfo table : schema.getTables()) {
            renderTable(sb, table);
        }
        return sb.toString();
    }

    private void renderTable(StringBuilder sb, TableInfo table) {
        sb.append("TABLE ").append(table.qualifiedName()).append(" (\n");
        StringJoiner cols = new StringJoiner(",\n");
        for (ColumnInfo column : table.getColumns()) {
            StringBuilder line = new StringBuilder("  ");
            line.append(column.getName()).append(' ').append(column.getTypeName());
            if (table.getPrimaryKeys().contains(column.getName())) {
                line.append(" PK");
            }
            if (!column.isNullable()) {
                line.append(" NOT NULL");
            }
            for (ForeignKeyInfo fk : table.getForeignKeys()) {
                if (fk.getColumn().equals(column.getName())) {
                    line.append(" FK -> ").append(referenced(fk));
                }
            }
            cols.add(line.toString());
        }
        sb.append(cols).append("\n)\n");
    }

    private String referenced(ForeignKeyInfo fk) {
        String table = (fk.getReferencedSchema() == null || fk.getReferencedSchema().isEmpty())
                ? fk.getReferencedTable()
                : fk.getReferencedSchema() + "." + fk.getReferencedTable();
        return table + "." + fk.getReferencedColumn();
    }
}
