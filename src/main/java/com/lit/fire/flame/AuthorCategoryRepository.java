package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists and queries the {@code author_categories} table — the materialised
 * view of marketing-team categorisations (audience class, influence tier,
 * posting style, etc.) keyed by author name.
 */
@Repository
public class AuthorCategoryRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public static final String TABLE     = "author_categories";

    /** Authors below this post count are considered statistically unreliable
     *  for marketing categorisation and are excluded from both writes and reads. */
    public static final int    MIN_POSTS = 5;  // strictly: only total_posts > 5 qualify

    @PostConstruct
    public void init() {
        // PostgreSQL syntax — matches the rest of the project (DataSourceConfig defaults to PG).
        String sql =
            "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
            "  author                  VARCHAR(255) PRIMARY KEY," +
            "  audience_classification VARCHAR(100)," +
            "  influence_tier          VARCHAR(50)," +
            "  posting_style           VARCHAR(50)," +
            "  dominant_tone           VARCHAR(20)," +
            "  primary_platform        VARCHAR(20)," +
            "  branching_ratio         DOUBLE PRECISION," +
            "  total_posts             INTEGER," +
            "  last_categorized_at     TIMESTAMP" +
            ")";
        jdbcTemplate.execute(sql);
    }

    /** @return true if the row was persisted, false if it was skipped due to MIN_POSTS. */
    public boolean upsert(AuthorCategorization c) {
        // Marketing rule: only authors with more than MIN_POSTS total posts are categorised.
        if (c.totalPosts <= MIN_POSTS) return false;

        String sql =
            "INSERT INTO " + TABLE + " " +
            "  (author, audience_classification, influence_tier, posting_style, " +
            "   dominant_tone, primary_platform, branching_ratio, total_posts, last_categorized_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (author) DO UPDATE SET " +
            "  audience_classification = EXCLUDED.audience_classification," +
            "  influence_tier          = EXCLUDED.influence_tier," +
            "  posting_style           = EXCLUDED.posting_style," +
            "  dominant_tone           = EXCLUDED.dominant_tone," +
            "  primary_platform        = EXCLUDED.primary_platform," +
            "  branching_ratio         = EXCLUDED.branching_ratio," +
            "  total_posts             = EXCLUDED.total_posts," +
            "  last_categorized_at     = EXCLUDED.last_categorized_at";

        jdbcTemplate.update(sql,
            c.author,
            c.audienceClassification,
            c.influenceTier,
            c.postingStyle,
            c.dominantTone,
            c.primaryPlatform,
            c.branchingRatio,
            c.totalPosts,
            Timestamp.valueOf(c.lastCategorizedAt != null ? c.lastCategorizedAt : LocalDateTime.now())
        );
        return true;
    }

    /** Removes any rows that no longer meet the MIN_POSTS threshold (e.g. after the rule changed).
     *  @return number of rows deleted. */
    public int purgeBelowThreshold() {
        return jdbcTemplate.update(
            "DELETE FROM " + TABLE + " WHERE total_posts <= ?", MIN_POSTS);
    }

    /**
     * Returns rows matching every non-null filter.  All filters are ANDed; null
     * filters are skipped, so an empty filter map returns every row.
     */
    public List<Map<String, Object>> find(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM " + TABLE + " WHERE total_posts > " + MIN_POSTS);
        List<Object> params = new ArrayList<>();

        addFilter(sql, params, filters, "audience_classification");
        addFilter(sql, params, filters, "influence_tier");
        addFilter(sql, params, filters, "posting_style");
        addFilter(sql, params, filters, "dominant_tone");
        addFilter(sql, params, filters, "primary_platform");

        sql.append(" ORDER BY branching_ratio DESC NULLS LAST, total_posts DESC");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    /** Returns the unique values present in each categorical column — handy for UI dropdowns. */
    public Map<String, List<String>> distinctValues() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String col : new String[]{"audience_classification","influence_tier",
                                       "posting_style","dominant_tone","primary_platform"}) {
            out.put(col, jdbcTemplate.queryForList(
                "SELECT DISTINCT " + col + " FROM " + TABLE +
                " WHERE " + col + " IS NOT NULL AND total_posts > " + MIN_POSTS +
                " ORDER BY " + col, String.class));
        }
        return out;
    }

    private void addFilter(StringBuilder sql, List<Object> params,
                           Map<String, String> filters, String col) {
        String v = filters.get(col);
        if (v != null && !v.isEmpty()) {
            sql.append(" AND ").append(col).append(" = ?");
            params.add(v);
        }
    }
}
