package com.lit.fire.flame.nlq.schema;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link SchemaFilter}: applying a per-request skip-list to a cached {@link DatabaseSchema}
 * removes skipped tables/columns and flags masked columns, matching what live introspection would have
 * produced under that policy.
 */
class SchemaFilterTest {

    private DatabaseSchema schema() {
        TableInfo users = new TableInfo(null, "users", List.of(
                new ColumnInfo("id", Types.INTEGER, "INTEGER", false),
                new ColumnInfo("email", Types.VARCHAR, "TEXT", false),
                new ColumnInfo("score", Types.REAL, "REAL", true)), List.of("id"), List.of());
        TableInfo audit = new TableInfo(null, "audit_log", List.of(
                new ColumnInfo("id", Types.INTEGER, "INTEGER", false)), List.of("id"), List.of());
        return new DatabaseSchema(List.of(users, audit), "SQLite", "sqlite");
    }

    @Test
    void removesSkippedTableAndColumn() {
        SkipList skip = SkipList.builder()
                .addSkipTables(List.of("audit_log"))
                .addSkipColumns(List.of("users.email"))
                .build();

        DatabaseSchema filtered = SchemaFilter.apply(schema(), skip);

        assertEquals(1, filtered.getTables().size());
        TableInfo users = filtered.getTables().get(0);
        assertEquals("users", users.getName());
        assertTrue(users.getColumns().stream().noneMatch(c -> c.getName().equals("email")),
                "the skipped column must be gone");
        assertTrue(users.getColumns().stream().anyMatch(c -> c.getName().equals("score")));
    }

    @Test
    void flagsMaskedColumn() {
        SkipList mask = SkipList.builder().addMaskedColumns(List.of("users.email")).build();
        DatabaseSchema filtered = SchemaFilter.apply(schema(), mask);
        ColumnInfo email = filtered.getTables().get(0).getColumns().stream()
                .filter(c -> c.getName().equals("email")).findFirst().orElseThrow();
        assertTrue(email.isMasked());
    }

    @Test
    void emptySkipListReturnsSameInstance() {
        DatabaseSchema original = schema();
        assertSame(original, SchemaFilter.apply(original, SkipList.empty()));
    }
}
