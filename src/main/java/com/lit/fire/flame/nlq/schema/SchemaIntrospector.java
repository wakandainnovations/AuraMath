package com.lit.fire.flame.nlq.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Walks {@link DatabaseMetaData} for a per-request target {@link Connection} to build a
 * {@link DatabaseSchema}, restricted to user tables/views and filtered through a {@link SkipList}.
 *
 * <p><b>Never reads row data.</b> Only the metadata calls {@code getTables}, {@code getColumns},
 * {@code getPrimaryKeys}, and {@code getImportedKeys} are used; no {@code SELECT} is issued.
 *
 * <p>System schemas are excluded so the model only ever sees application data: {@code pg_catalog}
 * and any {@code pg_*} schema and {@code information_schema} (Postgres); {@code mysql},
 * {@code sys}, {@code performance_schema}, {@code information_schema} (MySQL); and SQLite's internal
 * {@code sqlite_*} tables.
 */
@Component
public class SchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(SchemaIntrospector.class);

    /** Schema names that are always system/internal and never surfaced to the model. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "pg_catalog", "information_schema", "mysql", "sys", "performance_schema");

    /**
     * Introspect the connected database into a skip-list-filtered {@link DatabaseSchema}.
     *
     * @param connection a live target connection (read-only); the caller owns and closes it
     * @param skipList   the effective skip-list; skipped tables are omitted entirely and skipped
     *                   columns are removed from their table
     * @throws SQLException if a metadata call fails
     */
    public DatabaseSchema introspect(Connection connection, SkipList skipList) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String productName = meta.getDatabaseProductName();
        String dialect = dialectOf(productName);

        List<TableInfo> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String table = rs.getString("TABLE_NAME");
                if (isSystemObject(schema, table)) {
                    continue;
                }
                if (skipList.isTableSkipped(schema, table)) {
                    // Log the object NAME only (never any row value) so operators can verify the policy.
                    log.debug("Ask skip: table '{}' excluded from schema", qualify(schema, table));
                    continue;
                }
                List<ColumnInfo> columns = readColumns(meta, schema, table, skipList);
                if (columns.isEmpty()) {
                    // Every column was skipped (or the object exposes none) — nothing to show.
                    continue;
                }
                List<String> primaryKeys = readPrimaryKeys(meta, schema, table, skipList);
                List<ForeignKeyInfo> foreignKeys = readForeignKeys(meta, schema, table, skipList);
                tables.add(new TableInfo(schema, table, columns, primaryKeys, foreignKeys));
            }
        }
        return new DatabaseSchema(tables, productName, dialect);
    }

    private List<ColumnInfo> readColumns(DatabaseMetaData meta, String schema, String table,
                                         SkipList skipList) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (skipList.isColumnSkipped(schema, table, column)) {
                    log.debug("Ask skip: column '{}.{}' excluded from schema",
                            qualify(schema, table), column);
                    continue;
                }
                int sqlType = rs.getInt("DATA_TYPE");
                String typeName = rs.getString("TYPE_NAME");
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                // Masked columns stay visible (so they can be aggregated) but are flagged so the
                // renderer marks them and the guard rejects raw projection of them.
                boolean masked = skipList.isColumnMasked(schema, table, column);
                if (masked) {
                    log.debug("Ask mask: column '{}.{}' kept for aggregation, raw values redacted",
                            qualify(schema, table), column);
                }
                columns.add(new ColumnInfo(column, sqlType, typeName, nullable, masked));
            }
        }
        return columns;
    }

    private List<String> readPrimaryKeys(DatabaseMetaData meta, String schema, String table,
                                         SkipList skipList) throws SQLException {
        // Order by KEY_SEQ so composite keys keep their declared column order.
        Map<Short, String> byKeySeq = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, schema, table)) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (skipList.isColumnSkipped(schema, table, column)) {
                    continue;
                }
                byKeySeq.put(rs.getShort("KEY_SEQ"), column);
            }
        }
        return new ArrayList<>(byKeySeq.values());
    }

    private List<ForeignKeyInfo> readForeignKeys(DatabaseMetaData meta, String schema, String table,
                                                 SkipList skipList) throws SQLException {
        // Keyed by FK name + key-seq to keep multi-column keys in order without duplication.
        Map<String, ForeignKeyInfo> ordered = new LinkedHashMap<>();
        try (ResultSet rs = meta.getImportedKeys(null, schema, table)) {
            while (rs.next()) {
                String fkColumn = rs.getString("FKCOLUMN_NAME");
                String pkSchema = rs.getString("PKTABLE_SCHEM");
                String pkTable = rs.getString("PKTABLE_NAME");
                String pkColumn = rs.getString("PKCOLUMN_NAME");
                // Drop the reference if either side is invisible to the model.
                if (skipList.isColumnSkipped(schema, table, fkColumn)
                        || skipList.isTableSkipped(pkSchema, pkTable)
                        || skipList.isColumnSkipped(pkSchema, pkTable, pkColumn)) {
                    continue;
                }
                String key = rs.getString("FK_NAME") + "#" + rs.getShort("KEY_SEQ") + "#" + fkColumn;
                ordered.put(key, new ForeignKeyInfo(fkColumn, pkSchema, pkTable, pkColumn));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    /** {@code schema.table} for logging when a schema is present, else the bare table name. */
    private static String qualify(String schema, String table) {
        return (schema == null || schema.isEmpty()) ? table : schema + "." + table;
    }

    /** Exclude system schemas and SQLite's internal {@code sqlite_*} tables. */
    private boolean isSystemObject(String schema, String table) {
        if (schema != null) {
            String s = schema.toLowerCase(Locale.ROOT);
            if (SYSTEM_SCHEMAS.contains(s) || s.startsWith("pg_")) {
                return true;
            }
        }
        return table != null && table.toLowerCase(Locale.ROOT).startsWith("sqlite_");
    }

    /** Coarse dialect hint from the product name; defaults to a lowercased product name. */
    private String dialectOf(String productName) {
        if (productName == null) {
            return "unknown";
        }
        String p = productName.toLowerCase(Locale.ROOT);
        if (p.contains("postgre")) {
            return "postgresql";
        }
        if (p.contains("sqlite")) {
            return "sqlite";
        }
        if (p.contains("mysql") || p.contains("mariadb")) {
            return "mysql";
        }
        return p.trim();
    }
}
