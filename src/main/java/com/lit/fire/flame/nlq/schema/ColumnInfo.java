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

    public ColumnInfo(String name, int sqlType, String typeName, boolean nullable) {
        this.name = name;
        this.sqlType = sqlType;
        this.typeName = typeName;
        this.nullable = nullable;
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
}
