package com.lit.fire.flame.nlq.schema;

/**
 * An immutable description of a single imported foreign-key reference: one column in the owning
 * table pointing at one column of a referenced table. Built from
 * {@code DatabaseMetaData.getImportedKeys}.
 */
public final class ForeignKeyInfo {

    /** Column in the owning table that holds the reference. */
    private final String column;
    /** Schema of the referenced table; may be {@code null} for schemaless dialects (SQLite). */
    private final String referencedSchema;
    private final String referencedTable;
    private final String referencedColumn;

    public ForeignKeyInfo(String column, String referencedSchema, String referencedTable,
                          String referencedColumn) {
        this.column = column;
        this.referencedSchema = referencedSchema;
        this.referencedTable = referencedTable;
        this.referencedColumn = referencedColumn;
    }

    public String getColumn() {
        return column;
    }

    public String getReferencedSchema() {
        return referencedSchema;
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public String getReferencedColumn() {
        return referencedColumn;
    }
}
