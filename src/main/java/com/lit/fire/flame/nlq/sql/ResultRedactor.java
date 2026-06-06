package com.lit.fire.flame.nlq.sql;

import com.lit.fire.flame.nlq.schema.SkipList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Feature F9 — the output-side sensitive-data backstop. After a validated query has executed (F6) and
 * before the rows are handed to the mathematician (F7) and the response, every row passes through this
 * redactor, which applies the masked/skip policy one more time at the value level:
 *
 * <ul>
 *   <li><b>Masked columns</b> — a result column whose name matches a masked-column entry has its raw
 *       values {@link #maskValue partially masked}, so a masked value can never be returned verbatim
 *       even if it somehow reached the result set.</li>
 *   <li><b>Skipped columns (belt-and-suspenders)</b> — a result column whose name matches a skipped
 *       column is dropped entirely. This matters because a {@code SELECT *} is expanded <i>by the
 *       database</i> to its real columns, so a skipped column can re-appear in the result even though
 *       it was absent from the schema the model saw and from the SQL text the guard screened.</li>
 * </ul>
 *
 * <p>Matching is by the result column's leaf name (alias-aware label), consistent with the guard's
 * leaf-name screens; it is intentionally conservative (it can mask/drop an identically named column on
 * a non-masked table) — the correct fail-closed direction. Row <i>count</i> is never changed; only
 * column values and the column list are. The redactor never logs any row value.
 */
@Service
public class ResultRedactor {

    private static final Logger log = LoggerFactory.getLogger(ResultRedactor.class);

    /**
     * Return a copy of {@code result} with skipped columns dropped and masked column values redacted.
     * When the policy carries no masks and the result has no skip-listed column, the original is
     * returned unchanged.
     */
    public QueryResult redact(QueryResult result, SkipList skipList) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(skipList, "skipList");

        // Decide, per column, whether to drop (skipped) or mask (masked); keep the rest as-is.
        List<QueryResult.Column> keptColumns = new ArrayList<>(result.getColumns().size());
        List<String> droppedNames = new ArrayList<>();
        List<String> maskedNames = new ArrayList<>();
        for (QueryResult.Column column : result.getColumns()) {
            String leaf = leafOf(column.getName());
            if (skipList.isSkippedColumnLeaf(leaf)) {
                droppedNames.add(column.getName());
                continue;
            }
            if (skipList.isMaskedColumnLeaf(leaf)) {
                maskedNames.add(column.getName());
            }
            keptColumns.add(column);
        }

        if (droppedNames.isEmpty() && maskedNames.isEmpty()) {
            return result;
        }

        List<Map<String, Object>> redactedRows = new ArrayList<>(result.getRows().size());
        for (Map<String, Object> row : result.getRows()) {
            Map<String, Object> out = new LinkedHashMap<>(row.size() * 2);
            for (Map.Entry<String, Object> cell : row.entrySet()) {
                String name = cell.getKey();
                if (droppedNames.contains(name)) {
                    continue;
                }
                if (maskedNames.contains(name)) {
                    out.put(name, maskValue(cell.getValue()));
                } else {
                    out.put(name, cell.getValue());
                }
            }
            redactedRows.add(out);
        }

        if (!droppedNames.isEmpty()) {
            log.debug("Ask redact: dropped {} skip-listed result column(s) {}",
                    droppedNames.size(), droppedNames);
        }
        if (!maskedNames.isEmpty()) {
            log.debug("Ask redact: masked values of {} result column(s) {}",
                    maskedNames.size(), maskedNames);
        }
        return new QueryResult(keptColumns, redactedRows, result.isTruncated(),
                result.getExecutionMillis());
    }

    /**
     * Partially mask a single value: {@code null} stays {@code null}; otherwise the string form keeps
     * its first and last character and replaces the middle with {@code ***} (a very short value is
     * fully starred). Enough to confirm a value is present and not break joins on shape, without
     * revealing the content.
     */
    static Object maskValue(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        int n = s.length();
        if (n == 0) {
            return "";
        }
        if (n <= 2) {
            return "**";
        }
        if (n <= 4) {
            return s.charAt(0) + "***";
        }
        return s.charAt(0) + "***" + s.charAt(n - 1);
    }

    /** The leaf of a possibly-qualified column label (e.g. {@code u.email} → {@code email}). */
    private static String leafOf(String columnName) {
        if (columnName == null) {
            return null;
        }
        int dot = columnName.lastIndexOf('.');
        return (dot < 0) ? columnName : columnName.substring(dot + 1);
    }
}
