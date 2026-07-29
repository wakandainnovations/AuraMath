package com.lit.fire.flame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetweetResolver}'s retweet detection and best-effort original-row matching are expressed
 * as Postgres regex/CTE SQL (regexp_match, pg_trgm's similarity()) that a mocked JdbcTemplate can't
 * execute, so — like {@link MarketingAnalyticsIntegrationTest} — this drives the real service
 * against the real local 'aura' DB. Unlike that test, it only ever reads/writes rows it inserts
 * itself (id prefix {@code rrtest-}), plus a full-table shares_count recompute that
 * {@link RetweetResolver#recomputeAndPersist()} is designed to make idempotent, so it's safe to
 * leave enabled rather than requiring a manual run.
 */
@SpringBootTest
public class RetweetResolverTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetweetResolver retweetResolver;

    private static final String ORIG_1 = "rrtest-orig-1";
    private static final String ORIG_2 = "rrtest-orig-2";
    private static final String RT_1 = "rrtest-rt-1";
    private static final String RT_2 = "rrtest-rt-2";
    private static final String RT_3 = "rrtest-rt-3";
    private static final String RT_4 = "rrtest-rt-4";

    // Full text of the original that two retweets (RT_1, RT_2) quote verbatim, so it should be a
    // confident match under both the pg_trgm and exact-prefix strategies regardless of which one
    // this environment's DB supports.
    private static final String ORIG_1_TEXT =
            "Announcing the new community initiative today with full rollout details and timeline for everyone involved nationwide";
    private static final String ORIG_2_TEXT =
            "A completely different unrelated post about something else happening this weekend downtown";
    // Shares no meaningful prefix/substring with either original, so it must NOT match either one
    // even though its retweeted handle (auraresolvertesthero) does have earlier candidates in the table.
    private static final String RT_3_REMAINDER =
            "Something totally unrelated that does not match either original post text at all no overlap here";

    private void deleteTestRows() {
        jdbcTemplate.update("DELETE FROM x_posts WHERE id LIKE 'rrtest-%'");
    }

    @BeforeEach
    public void setUp() {
        deleteTestRows(); // in case a prior run crashed before cleanup

        Timestamp t0 = Timestamp.valueOf("2020-01-01 00:00:00");
        Timestamp t1 = Timestamp.valueOf("2020-01-01 01:00:00");
        Timestamp t2 = Timestamp.valueOf("2020-01-02 00:00:00");
        Timestamp t3 = Timestamp.valueOf("2020-01-02 01:00:00");
        Timestamp t4 = Timestamp.valueOf("2020-01-02 02:00:00");
        Timestamp t5 = Timestamp.valueOf("2020-01-02 03:00:00");

        insertPost(ORIG_1, ORIG_1_TEXT, "AuraResolverTestHero", t0);
        insertPost(ORIG_2, ORIG_2_TEXT, "AuraResolverTestHero", t1);
        // Two retweets of the same original: shares_count on ORIG_1 should end up at 2, not 1.
        insertPost(RT_1, "RT @AuraResolverTestHero: " + ORIG_1_TEXT, "someone_a", t2);
        insertPost(RT_2, "RT @AuraResolverTestHero: " + ORIG_1_TEXT, "someone_b", t3);
        // Same retweeted handle as ORIG_1/ORIG_2 (so candidates exist), but matching text for
        // neither -> must be counted in the aggregate but left unmatched (shares_count untouched).
        insertPost(RT_3, "RT @AuraResolverTestHero: " + RT_3_REMAINDER, "someone_c", t4);
        // Retweeted handle with no corresponding author anywhere in x_posts.
        insertPost(RT_4, "RT @NoSuchAuraResolverHandle: this retweet's original was never scraped", "someone_d", t5);
    }

    @AfterEach
    public void tearDown() {
        deleteTestRows();
    }

    private void insertPost(String id, String text, String author, Timestamp createdAt) {
        jdbcTemplate.update(
                "INSERT INTO x_posts (id, text, author, created_at) VALUES (?, ?, ?, ?)",
                id, text, author, createdAt);
    }

    @Test
    public void recomputeAndPersistProducesBothOutputs() {
        Map<String, Object> summary = retweetResolver.recomputeAndPersist();
        assertTrue(summary.containsKey("originalRowsMatched"), "summary should report originalRowsMatched");

        // Primary output: per-author retweet counts, independent of whether an original exists.
        assertEquals(3, retweetResolver.retweetCountForAuthor("AuraResolverTestHero"),
                "RT_1, RT_2, RT_3 all retweet AuraResolverTestHero");
        assertEquals(1, retweetResolver.retweetCountForAuthor("NoSuchAuraResolverHandle"),
                "RT_4 retweets a handle with no author row in x_posts at all, but still counts");

        // Secondary output: only confident matches increment shares_count.
        assertEquals(2, sharesCountOf(ORIG_1), "two retweets (RT_1, RT_2) confidently match ORIG_1's text");
        assertEquals(0, sharesCountOf(ORIG_2), "no retweet's quoted text matches ORIG_2");
    }

    private int sharesCountOf(String id) {
        Integer count = jdbcTemplate.queryForObject("SELECT shares_count FROM x_posts WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
