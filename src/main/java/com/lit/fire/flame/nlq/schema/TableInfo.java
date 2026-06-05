package com.lit.fire.flame.nlq.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable description of a single table or view: its schema, name, columns, and optional
 * primary-key and foreign-key relationships. Built by {@link SchemaIntrospector} from
 * {@code DatabaseMetaData}, after the skip-list has removed any excluded columns.
 *
 * <p>Holds no row data — structure only.
 */
public final class TableInfo {

    /** Owning schema/catalog; may be {@code null} for schemaless dialects such as SQLite. */
    private final String schema;
    private final String name;
    private final List<ColumnInfo> columns;
    /** Primary-key column names in key order; empty when none is declared. */
    private final List<String> primaryKeys;
    private final List<ForeignKeyInfo> foreignKeys;

    public TableInfo(String schema, String name, List<ColumnInfo> columns,
                     List<String> primaryKeys, List<ForeignKeyInfo> foreignKeys) {
        this.schema = schema;
        this.name = name;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.primaryKeys = Collections.unmodifiableList(new ArrayList<>(primaryKeys));
        this.foreignKeys = Collections.unmodifiableList(new ArrayList<>(foreignKeys));
    }

    public String getSchema() {
        return schema;
    }

    public String getName() {
        return name;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public List<String> getPrimaryKeys() {
        return primaryKeys;
    }

    public List<ForeignKeyInfo> getForeignKeys() {
        return foreignKeys;
    }

    /** {@code schema.name} when a schema is present, otherwise the bare table name. */
    public String qualifiedName() {
        return (schema == null || schema.isEmpty()) ? name : schema + "." + name;
    }
}
