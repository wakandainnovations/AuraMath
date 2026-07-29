package com.lit.fire.flame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves X (Twitter) retweets: a post is a retweet when {@code x_posts.text} starts with the
 * client's own {@code "RT @<handle>: <original text>"} prefix (confirmed against live data —
 * 25,005 / 71,123 rows, ~35%, all match {@code ^RT @\w+:} with no stragglers).
 *
 * Two outputs, of different confidence:
 *  - Per-author retweet counts (required): how many {@code RT @handle:} rows reference each
 *    retweeted handle, keyed by the same normalized-author key {@link CrossPlatformIdentityResolver}
 *    uses (lower-cased, non-alphanumerics stripped). This needs nothing but the RT text itself, so
 *    every retweet counts even when its original tweet was never scraped into x_posts. Feature 3's
 *    per-user engagement rating reads this directly via {@link #retweetCountsByNormalizedAuthor()}
 *    rather than re-querying x_posts.
 *  - Per-row shares_count (best-effort): only where a specific original row can be matched with
 *    real confidence — same normalized author, created earlier than the retweet, and the retweet's
 *    quoted-text remainder matches the original's opening ~40 characters (via pg_trgm's
 *    similarity() when the extension is installed, else exact equality of that same 40-character
 *    prefix — chosen over a wildcard LIKE so stray '%'/'_' characters already present in tweet text
 *    can't be misread as pattern metacharacters). Comparing same-length prefixes rather than the
 *    remainder against a candidate's *full* text matters: similarity() penalizes length mismatches,
 *    so a 40-character snippet scored against a much longer full tweet undershoots even for true
 *    matches. Rows with no confident match are left at shares_count = 0 rather than guessed.
 */
@Service
public class RetweetResolver {

    private static final Logger log = LoggerFactory.getLogger(RetweetResolver.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Length of the quoted-text prefix compared between a retweet's remainder and a candidate
    // original. Chosen empirically against live data: comparing same-length ~40-char prefixes via
    // similarity() at a >= 0.6 threshold recovers 1,581 of the 1,614 exact-prefix matches found in
    // the corpus, tolerating minor encoding/emoji differences without opening the door to false
    // positives from unrelated posts by the same author.
    private static final int MATCH_PREFIX_LEN = 40;
    private static final double TRGM_SIMILARITY_THRESHOLD = 0.6;

    private static final String RETWEET_HANDLE_COUNTS_SQL =
            "SELECT normalized_handle, count(*) AS retweet_count FROM (" +
            "  SELECT regexp_replace(lower((regexp_match(text, '^RT @(\\w+):'))[1]), '[^a-zA-Z0-9]', '', 'g') AS normalized_handle " +
            "  FROM x_posts WHERE text ~ '^RT @\\w+:'" +
            ") t WHERE normalized_handle <> '' " +
            "GROUP BY normalized_handle";

    // Shared CTE: every retweet row, its retweeted handle (normalized the same way as x_posts.author
    // elsewhere in the codebase), and the first MATCH_PREFIX_LEN characters of its quoted remainder.
    private static final String RETWEETS_CTE =
            "WITH retweets AS (" +
            "  SELECT id, created_at, " +
            "         regexp_replace(lower((regexp_match(text, '^RT @(\\w+):'))[1]), '[^a-zA-Z0-9]', '', 'g') AS normalized_handle, " +
            "         substring(regexp_replace(text, '^RT @\\w+:\\s*', '') for " + MATCH_PREFIX_LEN + ") AS remainder_prefix " +
            "  FROM x_posts WHERE text ~ '^RT @\\w+:'" +
            "), ";

    // Resets before each recompute so a repeated run is idempotent (a row's shares_count reflects
    // only this run's confident matches, never accumulates across runs).
    private static final String RESET_SHARES_SQL = "UPDATE x_posts SET shares_count = 0 WHERE shares_count <> 0";

    // best_match picks, per retweet, the single most-confident earlier same-author candidate (if
    // any); share_counts sums those per original row so N retweets of the same original set N, not 1.
    // An absolute SET (not shares_count + n) so re-running recomputeAndPersist() is idempotent.
    private static final String SHARE_COUNTS_UPDATE_SUFFIX =
            "share_counts AS (" +
            "  SELECT orig_id, count(*) AS n FROM best_match GROUP BY orig_id" +
            ") " +
            "UPDATE x_posts SET shares_count = share_counts.n " +
            "FROM share_counts WHERE x_posts.id = share_counts.orig_id";

    private static final String TRGM_MATCH_SQL =
            RETWEETS_CTE +
            "best_match AS (" +
            "  SELECT DISTINCT ON (r.id) r.id AS rt_id, o.id AS orig_id " +
            "  FROM retweets r " +
            "  JOIN x_posts o " +
            "    ON regexp_replace(lower(o.author), '[^a-zA-Z0-9]', '', 'g') = r.normalized_handle " +
            "   AND o.created_at < r.created_at " +
            "   AND o.author <> '' " +
            "   AND o.text !~ '^RT @\\w+:' " +
            "   AND btrim(r.remainder_prefix) <> '' " +
            "   AND similarity(left(o.text, " + MATCH_PREFIX_LEN + "), r.remainder_prefix) >= " + TRGM_SIMILARITY_THRESHOLD + " " +
            "  ORDER BY r.id, similarity(left(o.text, " + MATCH_PREFIX_LEN + "), r.remainder_prefix) DESC, o.created_at DESC" +
            "), " +
            SHARE_COUNTS_UPDATE_SUFFIX;

    private static final String PREFIX_MATCH_SQL =
            RETWEETS_CTE +
            "best_match AS (" +
            "  SELECT DISTINCT ON (r.id) r.id AS rt_id, o.id AS orig_id " +
            "  FROM retweets r " +
            "  JOIN x_posts o " +
            "    ON regexp_replace(lower(o.author), '[^a-zA-Z0-9]', '', 'g') = r.normalized_handle " +
            "   AND o.created_at < r.created_at " +
            "   AND o.author <> '' " +
            "   AND o.text !~ '^RT @\\w+:' " +
            "   AND btrim(r.remainder_prefix) <> '' " +
            "   AND left(o.text, " + MATCH_PREFIX_LEN + ") = r.remainder_prefix " +
            "  ORDER BY r.id, o.created_at DESC" +
            "), " +
            SHARE_COUNTS_UPDATE_SUFFIX;

    // Populated by recomputeAndPersist(); read by retweetCountsByNormalizedAuthor()/retweetCountForAuthor().
    private Map<String, Integer> retweetCountsByAuthor = new LinkedHashMap<>();

    private void ensureSchema() {
        jdbcTemplate.execute("ALTER TABLE x_posts ADD COLUMN IF NOT EXISTS shares_count INT DEFAULT 0");
    }

    private boolean pgTrgmAvailable() {
        try {
            Boolean installed = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm')", Boolean.class);
            return Boolean.TRUE.equals(installed);
        } catch (Exception e) {
            log.warn("Could not check pg_trgm availability, falling back to exact-prefix matching: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Recomputes the per-author retweet-count aggregate and the best-effort per-row shares_count.
     * Returns a summary; the aggregate itself is cached and served by
     * {@link #retweetCountsByNormalizedAuthor()} without needing to re-query x_posts.
     */
    public synchronized Map<String, Object> recomputeAndPersist() {
        ensureSchema();

        Map<String, Integer> counts = new LinkedHashMap<>();
        int totalRetweetRows = 0;
        for (Map<String, Object> row : jdbcTemplate.queryForList(RETWEET_HANDLE_COUNTS_SQL)) {
            int c = ((Number) row.get("retweet_count")).intValue();
            counts.put((String) row.get("normalized_handle"), c);
            totalRetweetRows += c;
        }
        this.retweetCountsByAuthor = counts;

        jdbcTemplate.update(RESET_SHARES_SQL);
        boolean trgmAvailable = pgTrgmAvailable();
        int originalRowsMatched = jdbcTemplate.update(trgmAvailable ? TRGM_MATCH_SQL : PREFIX_MATCH_SQL);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("retweetRowsSeen", totalRetweetRows);
        summary.put("distinctRetweetedAuthors", counts.size());
        summary.put("originalRowsMatched", originalRowsMatched);
        summary.put("matchStrategy", trgmAvailable ? "pg_trgm_similarity" : "exact_prefix");
        return summary;
    }

    /** Per-author retweet counts computed by the last {@link #recomputeAndPersist()} run, keyed by normalized author. */
    public synchronized Map<String, Integer> retweetCountsByNormalizedAuthor() {
        return Collections.unmodifiableMap(retweetCountsByAuthor);
    }

    /** Convenience for callers holding a raw (non-normalized) author/handle string. */
    public synchronized int retweetCountForAuthor(String rawAuthor) {
        return retweetCountsByAuthor.getOrDefault(normalize(rawAuthor), 0);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
    }
}
