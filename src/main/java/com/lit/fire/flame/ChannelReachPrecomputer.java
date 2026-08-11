package com.lit.fire.flame;

import com.lit.fire.flame.models.UniversalPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline producer for the /channel-strategy endpoints (celebrity, party, genre).
 *
 * Two live scans were happening on every request:
 *   - EntityMarketingService.reachForTable(): SELECT SUM(...), COUNT(*) FROM table WHERE
 *     keyword ILIKE ? — a full scan of each platform table per request.
 *   - GenreMarketingAPI.classifyTable(): an UNFILTERED SELECT * FROM table, classifying every
 *     row in Java (GenreClassifier.classifyPost()) to decide genre membership — the heaviest of
 *     the two, since it has no WHERE clause at all and runs on every request to any genre.
 *
 * Neither depends on anything that changes per-request, so both are precomputed here, on a
 * schedule, into two small aggregate tables the endpoints serve with a single indexed lookup:
 *
 *   channel_reach_agg(keyword, platform, reach, post_count)
 *       — one row per (LOWER(keyword), platform). Populated with a single INSERT ... SELECT ...
 *         GROUP BY per platform table — this is pure SQL aggregation with no per-row app logic,
 *         so there is nothing app-side streaming would buy over letting Postgres do it in one
 *         pass. GROUP BY LOWER(keyword) reproduces the old ILIKE-without-wildcards' case-
 *         insensitive equality semantics.
 *   genre_channel_reach_agg(genre, platform, reach, post_count)
 *       — one row per (LOWER(genre), platform). Genre membership isn't a stored column, so this
 *         DOES need to stream every row through GenreClassifier once (cursor-based, matching
 *         AspectDriversPrecomputer's approach), same classification logic as the old
 *         classifyTable()/matchesGenre(), just run once instead of once per request. A post
 *         matching multiple genres contributes to all of them; label weight is not used as a
 *         multiplier, matching the old boolean membership check.
 *
 * On any failure the previously published tables are left untouched.
 */
@Service
public class ChannelReachPrecomputer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ChannelReachPrecomputer.class);

    // Rows per JDBC batch when publishing genre-reach, and server-side cursor fetch size when
    // streaming posts for classification.
    private static final int BATCH_SIZE = 1000;
    private static final int FETCH_SIZE = 500;

    // Application-defined key for pg_advisory_lock so only one instance rebuilds at a time.
    // Arbitrary but stable constant (ASCII "ChannelR"), packed the same way
    // AspectDriversPrecomputer packs "AspectDr" and MarketingEnrichmentScheduler packs "MktEnrch".
    private static final long ADVISORY_LOCK_KEY = 0x4368616E6E656C52L;

    // Packs (genre, platform) into one map key; this control char can't appear in a genre name.
    private static final char KEY_SEP = '\u0001';

    private final DataSource dataSource;      // owns the connection that holds the advisory lock
    private final JdbcTemplate jdbc;          // schema management + publish writes
    private final JdbcTemplate streamingJdbc; // cursor-based reads (FETCH_SIZE) for the genre scan
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final GenreClassifier classifier;

    public ChannelReachPrecomputer(DataSource dataSource,
                                    PlatformTransactionManager txManager,
                                    GenreClassifier classifier) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc.setFetchSize(FETCH_SIZE);
        this.classifier = classifier;
        // Cursor streaming requires autocommit off, so the genre scan runs inside a read-only
        // transaction.
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
    }

    /** Create the tables at startup so the endpoints never hit a missing relation before the first refresh. */
    @Override
    public void afterPropertiesSet() {
        ensureSchema();
    }

    public void ensureSchema() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS channel_reach_agg (" +
                    "keyword TEXT NOT NULL, platform TEXT NOT NULL, " +
                    "reach BIGINT NOT NULL, post_count BIGINT NOT NULL, " +
                    "PRIMARY KEY (keyword, platform))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS genre_channel_reach_agg (" +
                    "genre TEXT NOT NULL, platform TEXT NOT NULL, " +
                    "reach BIGINT NOT NULL, post_count BIGINT NOT NULL, " +
                    "PRIMARY KEY (genre, platform))");
        } catch (Exception e) {
            log.error("Failed to ensure channel-reach schema", e);
        }
    }

    /**
     * Rebuilds both channel-reach tables. Runs on a cron, deliberately offset from every other
     * daily job in this service (AspectDriversPrecomputer/SeedScoreCalibrator fire ~1 minute
     * after startup, AuthorCategoryController ~5 minutes, MarketingEnrichmentScheduler at 03:30
     * UTC) so this job's full, uncapped scan of the 4 platform tables for genre classification —
     * the heaviest job in this set, since unlike AspectDriversPrecomputer it has no
     * MAX_POSTS_PER_PLATFORM-style cap — doesn't stack CPU load on top of the others. Default
     * 04:15 UTC, after MarketingEnrichmentScheduler's job typically finishes.
     *
     * A Postgres session-level advisory lock guards the whole run, so when the service is scaled
     * to multiple replicas only one rebuilds the shared tables.
     */
    @Scheduled(cron = "${channel.reach.cron:0 15 4 * * *}", zone = "${channel.reach.zone:UTC}")
    public void refresh() {
        // The advisory lock is session-scoped, so it must be acquired and released on the same
        // physical connection — hold one open for the duration rather than going through JdbcTemplate.
        try (Connection lockConn = dataSource.getConnection()) {
            if (!tryAdvisoryLock(lockConn)) {
                log.info("Channel-reach precompute skipped: another instance holds the refresh lock");
                return;
            }
            try {
                doRefresh();
            } finally {
                releaseAdvisoryLock(lockConn);
            }
        } catch (Throwable t) {
            log.error("Channel-reach precompute failed", t);
        }
    }

    /**
     * Two independent publish phases under one lock. Each gets its own Throwable boundary
     * (rather than one boundary around both, as AspectDriversPrecomputer uses for its single
     * artifact) so a failure in the heavier, riskier genre scan can't discard an already-
     * successful keyword-reach publish.
     */
    private void doRefresh() {
        long start = System.currentTimeMillis();
        ensureSchema();

        try {
            publishKeywordReach();
        } catch (Throwable t) {
            log.error("channel_reach_agg refresh failed; previous table left in place", t);
        }

        try {
            publishGenreReach();
        } catch (Throwable t) {
            log.error("genre_channel_reach_agg refresh failed; previous table left in place", t);
        }

        log.info("Channel-reach precompute complete in {} ms", System.currentTimeMillis() - start);
    }

    private boolean tryAdvisoryLock(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void releaseAdvisoryLock(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            ps.execute();
        } catch (Exception e) {
            // Non-fatal: closing the connection releases the session lock anyway.
            log.warn("Could not explicitly release channel-reach advisory lock: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // channel_reach_agg — pure SQL aggregation, no app-side row handling.
    // -------------------------------------------------------------------------

    private void publishKeywordReach() {
        writeTx.executeWithoutResult(status -> {
            jdbc.update("TRUNCATE TABLE channel_reach_agg");
            insertKeywordReach("x_posts", "x", "views_count");
            insertKeywordReach("youtube_comments", "youtube", "likes_count");
            insertKeywordReach("reddit_posts", "reddit", "num_comments");
            insertKeywordReach("instagram_posts", "instagram", "like_count");
        });
    }

    private void insertKeywordReach(String table, String platform, String reachColumn) {
        jdbc.update(
                "INSERT INTO channel_reach_agg (keyword, platform, reach, post_count) " +
                "SELECT LOWER(keyword), ?, COALESCE(SUM(" + reachColumn + "), 0)::bigint, COUNT(*)::bigint " +
                "FROM " + table + " WHERE keyword IS NOT NULL GROUP BY LOWER(keyword)",
                platform);
    }

    // -------------------------------------------------------------------------
    // genre_channel_reach_agg — stream every post through GenreClassifier once.
    // -------------------------------------------------------------------------

    private void publishGenreReach() {
        Map<String, long[]> acc = new HashMap<>(); // (genre|platform) -> [reach, post_count]

        readTx.executeWithoutResult(status -> {
            scanForGenreReach(acc, "x_posts", "x", "views_count");
            scanForGenreReach(acc, "youtube_comments", "youtube", "likes_count");
            scanForGenreReach(acc, "reddit_posts", "reddit", "num_comments");
            scanForGenreReach(acc, "instagram_posts", "instagram", "like_count");
        });

        List<Object[]> rows = new ArrayList<>(acc.size());
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            String[] gp = splitKey(e.getKey());
            rows.add(new Object[]{gp[0], gp[1], e.getValue()[0], e.getValue()[1]});
        }

        writeTx.executeWithoutResult(status -> {
            jdbc.update("TRUNCATE TABLE genre_channel_reach_agg");
            for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
                jdbc.batchUpdate(
                        "INSERT INTO genre_channel_reach_agg (genre, platform, reach, post_count) VALUES (?, ?, ?, ?)",
                        rows.subList(i, Math.min(i + BATCH_SIZE, rows.size())));
            }
        });
    }

    /**
     * Streams every row of {@code table} (unfiltered — same universe GenreMarketingAPI's old
     * classifyTable() scanned) through GenreClassifier and folds matches into {@code acc}. A post
     * can match more than one genre; every match contributes, not just the first (mirrors
     * classifyPost() returning a List<GenreLabel>, unlike matchesGenre()'s single-genre check).
     * Reach is credited in full per matched genre, not weighted by label.weight() — the old
     * matchesGenre() was a boolean membership check that ignored weight, so parity requires the
     * same here.
     */
    private void scanForGenreReach(Map<String, long[]> acc, String table, String platform, String metricColumn) {
        boolean hasTitle = table.equals("reddit_posts");
        boolean hasMediaType = table.equals("instagram_posts");
        String sql = "SELECT id, text, keyword" +
                (hasTitle ? ", title" : "") +
                (hasMediaType ? ", media_type" : "") +
                ", COALESCE(" + metricColumn + ", 0) AS metric FROM " + table;

        streamingJdbc.query(sql, rs -> {
            Map<String, Object> meta = new HashMap<>();
            meta.put("keyword", rs.getString("keyword"));
            String content = rs.getString("text");
            if (hasTitle) {
                String title = rs.getString("title") == null ? "" : rs.getString("title");
                String body = content == null ? "" : content;
                meta.put("title", title);
                content = (title + " " + body).trim();
            }
            if (hasMediaType) {
                meta.put("media_type", rs.getString("media_type"));
            }

            UniversalPost post = new UniversalPost(
                    rs.getString("id"), null, content, null, table, meta);
            long metric = rs.getLong("metric");

            for (GenreClassifier.GenreLabel label : classifier.classifyPost(post)) {
                long[] slot = acc.computeIfAbsent(
                        label.genre().toLowerCase() + KEY_SEP + platform, k -> new long[2]);
                slot[0] += metric;
                slot[1] += 1;
            }
        });
    }

    private static String[] splitKey(String key) {
        int sep = key.indexOf(KEY_SEP);
        return new String[]{key.substring(0, sep), key.substring(sep + 1)};
    }
}
