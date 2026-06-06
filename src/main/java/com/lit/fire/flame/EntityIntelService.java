package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Back-end for the entity intelligence report. An entity ({@code
 * managed_entities} row) spans several tracked keywords ({@code
 * entity_keywords}); this service resolves that keyword set, pulls the entity's
 * cross-platform post history, and feeds it through {@link HawkesAuditService}
 * so the report reuses the exact same Hawkes MLE, excitation-spike and burst
 * computation that powers the per-user report — only scoped to an entity's
 * keywords instead of a single author.
 */
@Service
public class EntityIntelService {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HawkesAuditService hawkes;

    /** Resolved entity identity plus its tracked keyword set. */
    public static class EntityProfile {
        public final String       entityId;
        public final String       name;
        public final String       type;
        public final List<String> keywords;

        EntityProfile(String entityId, String name, String type, List<String> keywords) {
            this.entityId = entityId;
            this.name     = name;
            this.type     = type;
            this.keywords = keywords;
        }
    }

    /**
     * Resolves an entity by id, returning its name, type and the distinct
     * keyword set tracked for it, or {@code null} if no such entity exists.
     */
    public EntityProfile lookup(String entityId) {
        String sql =
                "SELECT me.name, me.type, array_agg(DISTINCT ek.keyword) AS keywords " +
                "FROM managed_entities me " +
                "JOIN entity_keywords ek ON ek.entity_id = me.id " +
                "WHERE me.id::text = ? " +
                "GROUP BY me.name, me.type";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, entityId);
        if (rows.isEmpty()) return null;

        Map<String, Object> row = rows.get(0);
        List<String> keywords = sqlArrayToList(row.get("keywords"));
        return new EntityProfile(
                entityId,
                row.get("name") != null ? row.get("name").toString() : entityId,
                row.get("type") != null ? row.get("type").toString() : null,
                keywords);
    }

    /**
     * Computes the aggregate Hawkes audit over every post matching any of the
     * entity's keywords. The returned {@link HawkesAuditService.AuditResult}
     * carries the same per-event excitation/burst data as the user report, with
     * {@code author} set to the entity id.
     */
    public HawkesAuditService.AuditResult computeAudit(EntityProfile entity) {
        return hawkes.computeFromRows(entity.entityId, fetchRows(entity.keywords));
    }

    // -------------------------------------------------------------------------
    // Cross-keyword event fetch — mirrors HawkesAuditService.fetchRows but
    // scopes by the entity's keyword set instead of a single author. Column
    // aliases match exactly so computeFromRows can consume the rows unchanged.
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> fetchRows(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        String in = String.join(", ", Collections.nCopies(keywords.size(), "?"));

        String sql =
            "SELECT id, text AS content, created_at  AS event_time, " +
            "       keyword, sentiment_category, sentiment_score, 'x' AS platform " +
            "  FROM x_posts          WHERE LOWER(keyword) IN (" + in + ") AND sentiment_score BETWEEN 1 AND 100 " +
            "UNION ALL " +
            "SELECT id, text AS content, published_at AS event_time, " +
            "       keyword, sentiment_category, sentiment_score, 'youtube' AS platform " +
            "  FROM youtube_comments WHERE LOWER(keyword) IN (" + in + ") AND sentiment_score BETWEEN 1 AND 100 " +
            "UNION ALL " +
            "SELECT id, text AS content, created_at   AS event_time, " +
            "       keyword, sentiment_category, sentiment_score, 'reddit' AS platform " +
            "  FROM reddit_posts     WHERE LOWER(keyword) IN (" + in + ") AND sentiment_score BETWEEN 1 AND 100 " +
            "UNION ALL " +
            "SELECT id, text AS content, timestamp    AS event_time, " +
            "       keyword, sentiment_category, sentiment_score, 'instagram' AS platform " +
            "  FROM instagram_posts  WHERE LOWER(keyword) IN (" + in + ") AND sentiment_score BETWEEN 1 AND 100 " +
            "ORDER BY event_time ASC";

        return jdbc.queryForList(sql, repeatLowered(keywords, 4));
    }

    @SuppressWarnings("unchecked")
    private static List<String> sqlArrayToList(Object sqlArray) {
        if (sqlArray == null) return List.of();
        try {
            if (sqlArray instanceof java.sql.Array a) {
                Object inner = a.getArray();
                if (inner instanceof Object[] arr) {
                    List<String> list = new ArrayList<>(arr.length);
                    for (Object o : arr) if (o != null) list.add(o.toString());
                    return list;
                }
            }
            if (sqlArray instanceof List<?> l) {
                return (List<String>) l;
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    /** Lower-cases the keyword list and repeats it once per UNION branch. */
    private static Object[] repeatLowered(List<String> keywords, int times) {
        List<Object> args = new ArrayList<>(keywords.size() * times);
        for (int t = 0; t < times; t++)
            for (String k : keywords)
                args.add(k == null ? null : k.toLowerCase());
        return args.toArray();
    }
}
