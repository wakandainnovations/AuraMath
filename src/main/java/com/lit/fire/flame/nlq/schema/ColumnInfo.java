package com.lit.fire.flame.nlq.schema;

/**
 * An immutable description of a single column, built from {@code DatabaseMetaData.getColumns}.
 *
 * <p>Carries only structural metadata — no row data is ever read or stored here.
 */
public final class ColumnInfo {

    private final String name;
    /** JDBC type code from {@link java.sql.Types} (e.g. {@code Types.VARCHAR}). */
    private final int sqlType;
    /** Database-native type name reported by the driver (e.g. {@code varchar}, {@code int4}). */
    private final String typeName;
    private final boolean nullable;
    /**
     * Whether this column is <b>masked</b> (F9): it stays visible to the model so it can be aggregated,
     * but its raw values must never be projected or returned. Distinct from a <i>skipped</i> column,
     * which is removed from the schema entirely and so never produces a {@code ColumnInfo}.
     */
    private final boolean masked;

    public ColumnInfo(String name, int sqlType, String typeName, boolean nullable) {
        this(name, sqlType, typeName, nullable, false);
    }

    public ColumnInfo(String name, int sqlType, String typeName, boolean nullable, boolean masked) {
        this.name = name;
        this.sqlType = sqlType;
        this.typeName = typeName;
        this.nullable = nullable;
        this.masked = masked;
    }

    public String getName() {
        return name;
    }

    public int getSqlType() {
        return sqlType;
    }

    public String getTypeName() {
        return typeName;
    }

    public boolean isNullable() {
        return nullable;
    }

    /** Whether this column's raw values must be redacted from output (F9 masked column). */
    public boolean isMasked() {
        return masked;
    }
}
