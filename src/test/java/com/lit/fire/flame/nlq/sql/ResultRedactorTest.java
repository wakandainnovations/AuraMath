package com.lit.fire.flame.nlq.sql;

import com.lit.fire.flame.nlq.schema.SkipList;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ResultRedactor} (F9) — the output-side backstop that masks masked-column values
 * and drops any skip-listed column that slipped through into the result (e.g. a database-expanded
 * {@code SELECT *}).
 */
class ResultRedactorTest {

    private final ResultRedactor redactor = new ResultRedactor();

    private QueryResult result(List<QueryResult.Column> columns, List<Map<String, Object>> rows) {
        return new QueryResult(columns, rows, false, 1L);
    }

    private QueryResult.Column col(String name) {
        return new QueryResult.Column(name, Types.VARCHAR, "TEXT");
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void maskedColumnValuesAreMaskedAndOtherColumnsPassThrough() {
        SkipList skipList = SkipList.builder()
                .addMaskedColumns(Collections.singletonList("users.email")).build();
        QueryResult original = result(
                Arrays.asList(col("id"), col("email")),
                Arrays.asList(
                        row("id", 1L, "email", "alice@example.com"),
                        row("id", 2L, "email", "bob@example.com")));

        QueryResult redacted = redactor.redact(original, skipList);

        // The email column is retained (so a count is still meaningful) but its values are masked.
        assertEquals(2, redacted.getColumns().size());
        assertEquals(1L, redacted.getRows().get(0).get("id"));
        Object masked = redacted.getRows().get(0).get("email");
        assertFalse("alice@example.com".equals(masked), "raw email must not survive: " + masked);
        assertTrue(masked.toString().startsWith("a"), "partial mask keeps the first char: " + masked);
        // The row count is never changed by redaction.
        assertEquals(2, redacted.getRowCount());
    }

    @Test
    void skipListedColumnThatSlippedThroughIsDropped() {
        // 'ssn' is skip-listed but reached the result anyway (a database-expanded SELECT *).
        SkipList skipList = SkipList.from(null, null, Collections.singletonList("users.ssn"));
        QueryResult original = result(
                Arrays.asList(col("id"), col("ssn"), col("name")),
                Collections.singletonList(row("id", 1L, "ssn", "123-45-6789", "name", "Alice")));

        QueryResult redacted = redactor.redact(original, skipList);

        // The skip-listed column is gone entirely — from both the columns and every row.
        assertEquals(2, redacted.getColumns().size());
        assertTrue(redacted.getColumns().stream().noneMatch(c -> c.getName().equals("ssn")));
        assertFalse(redacted.getRows().get(0).containsKey("ssn"));
        assertEquals("Alice", redacted.getRows().get(0).get("name"));
    }

    @Test
    void resultWithNothingToRedactIsReturnedUnchanged() {
        SkipList skipList = SkipList.builder()
                .addMaskedColumns(Collections.singletonList("users.email")).build();
        QueryResult original = result(
                new ArrayList<>(Collections.singletonList(col("id"))),
                Collections.singletonList(row("id", 1L)));

        QueryResult redacted = redactor.redact(original, skipList);
        assertSame(original, redacted, "no masked/skipped column present → same instance");
    }

    @Test
    void nullMaskedValueStaysNull() {
        assertNull(ResultRedactor.maskValue(null));
        assertEquals("**", ResultRedactor.maskValue("ab"));
        assertEquals("a***e", ResultRedactor.maskValue("apple"));
    }
}
