package com.lit.fire.flame;

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
import java.util.stream.Collectors;

/**
 * Offline producer for the /aspect-drivers/{keyword} endpoint.
 *
 * Running Stanford CoreNLP over every matching post on each request made that endpoint do up
 * to 4,000 NLP pipelines on the request thread — tens of seconds of work that timed upstream
 * callers out. NLP output for a stored post never changes, so we extract aspects once here, on
 * a 24-hour schedule, and persist pre-summed aggregates the endpoint can serve with two cheap
 * GROUP BY queries.
 *
 * Two tables are rebuilt each run:
 *   aspect_drivers_agg(keyword, platform, aspect, sentiment_sum, mention_count)
 *       — one row per (source keyword, platform, aspect noun). sentiment_sum/mention_count are
 *         kept (rather than a precomputed average) so the endpoint can re-aggregate across every
 *         keyword its ILIKE substring matches, exactly reproducing the old per-request grouping.
 *   aspect_drivers_post_counts(keyword, platform, post_count)
 *       — posts considered per (keyword, platform); backs the response's totalPostsAnalyzed.
 *
 * Each post is annotated exactly once per run (capped to the MAX_POSTS_PER_PLATFORM most recent
 * posts per keyword per platform, matching the endpoint's historical limit). On any failure the
 * previously published tables are left untouched.
 */
@Service
public class AspectDriversPrecomputer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AspectDriversPrecomputer.class);

    // Most-recent posts considered per keyword per platform. Mirrors the cap the endpoint
    // historically applied per request (ViralSeedController.MAX_POSTS_PER_PLATFORM).
    private static final int MAX_POSTS_PER_PLATFORM = 1000;

    // Rows per JDBC batch when publishing, and server-side cursor fetch size when reading.
    private static final int BATCH_SIZE = 1000;
    private static final int FETCH_SIZE = 500;

    // Posts buffered before each parallel NLP flush. Bounds memory while giving the parallel
    // stream enough work per flush to keep every core busy.
    private static final int NLP_BATCH = 2000;

    // Application-defined key for pg_advisory_lock so only one instance rebuilds at a time.
    // Arbitrary constant (ASCII "AspectDr"); just needs to be stable and unique to this job.
    private static final long ADVISORY_LOCK_KEY = 0x4173706563744472L;

    // Packs (keyword, platform) into one map key; this control char can't appear in a keyword.
    private static final char KEY_SEP = '\u0001';

    private final DataSource dataSource;      // owns the connection that holds the advisory lock
    private final JdbcTemplate jdbc;          // schema management + publish writes
    private final JdbcTemplate streamingJdbc; // cursor-based reads (FETCH_SIZE) for the source scans
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final AspectSentimentAnalyzer analyzer;

    public AspectDriversPrecomputer(DataSource dataSource,
                                    PlatformTransactionManager txManager,
                                    AspectSentimentAnalyzer analyzer) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc.setFetchSize(FETCH_SIZE);
        this.analyzer = analyzer;
        // Cursor streaming requires autocommit off, so reads run inside a read-only transaction.
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
    }

    /** Create the tables at startup so the endpoint never hits a missing relation before the first refresh. */
    @Override
    public void afterPropertiesSet() {
        ensureSchema();
    }

    public void ensureSchema() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS aspect_drivers_agg (" +
                    "keyword TEXT NOT NULL, platform TEXT NOT NULL, aspect TEXT NOT NULL, " +
                    "sentiment_sum DOUBLE PRECISION NOT NULL, mention_count INTEGER NOT NULL, " +
                    "PRIMARY KEY (keyword, platform, aspect))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS aspect_drivers_post_counts (" +
                    "keyword TEXT NOT NULL, platform TEXT NOT NULL, post_count INTEGER NOT NULL, " +
                    "PRIMARY KEY (keyword, platform))");
        } catch (Exception e) {
            log.error("Failed to ensure aspect-drivers schema", e);
            return;
        }
        // Trigram indexes let the endpoint's `keyword ILIKE '%...%'` use an index. Best-effort:
        // creating the extension may require privileges the app role lacks, which is non-fatal —
        // the precomputed tables are small enough to scan.
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ada_keyword_trgm " +
                    "ON aspect_drivers_agg USING gin (keyword gin_trgm_ops)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_adpc_keyword_trgm " +
                    "ON aspect_drivers_post_counts USING gin (keyword gin_trgm_ops)");
        } catch (Exception e) {
            log.warn("Could not create pg_trgm index on aspect-drivers tables (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Rebuilds the aspect-drivers tables. Runs 60s after startup (DB warm-up) then every 24 hours.
     *
     * A Postgres session-level advisory lock guards the whole run, so when the service is scaled
     * to multiple replicas only one rebuilds the shared tables — the rest skip rather than doing
     * the same multi-minute NLP scan and contending on the publish TRUNCATE.
     *
     * The heavy NLP scan runs outside any write transaction; only the final truncate-and-insert
     * publish step is transactional, so a failure mid-scan leaves the live tables intact.
     */
    @Scheduled(initialDelay = 60_000L, fixedRate = 24L * 60 * 60 * 1000)
    public void refresh() {
        // The advisory lock is session-scoped, so it must be acquired and released on the same
        // physical connection — hold one open for the duration rather than going through JdbcTemplate.
        try (Connection lockConn = dataSource.getConnection()) {
            if (!tryAdvisoryLock(lockConn)) {
                log.info("Aspect-drivers precompute skipped: another instance holds the refresh lock");
                return;
            }
            try {
                doRefresh();
            } finally {
                releaseAdvisoryLock(lockConn);
            }
        } catch (Exception e) {
            log.error("Aspect-drivers precompute failed; previously published tables left in place", e);
        }
    }

    private void doRefresh() {
        long start = System.currentTimeMillis();
        ensureSchema();

        Accumulator acc = new Accumulator();
        readTx.executeWithoutResult(status -> {
            scanX(acc);
            scanCategoryPlatform(acc, "youtube",   "youtube_comments", "text", null,    "published_at");
            scanCategoryPlatform(acc, "reddit",    "reddit_posts",     "text", "title", "created_at");
            scanCategoryPlatform(acc, "instagram", "instagram_posts",  "text", null,    "timestamp");
            acc.flush(); // annotate the final partial batch before the cursor closes
        });

        publish(acc);

        int rows = acc.agg.values().stream().mapToInt(Map::size).sum();
        log.info("Aspect-drivers precompute complete: {} (keyword,platform) groups, {} aspect rows, in {} ms",
                acc.postCounts.size(), rows, System.currentTimeMillis() - start);
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
            log.warn("Could not explicitly release aspect-drivers advisory lock: {}", e.getMessage());
        }
    }

    /** x_posts carries a continuous numeric sentiment_score. */
    private void scanX(Accumulator acc) {
        String sql =
                "SELECT keyword, text, sentiment_score FROM (" +
                "  SELECT keyword, text, sentiment_score, " +
                "         ROW_NUMBER() OVER (PARTITION BY keyword ORDER BY created_at DESC) AS rn " +
                "  FROM x_posts WHERE keyword IS NOT NULL AND text IS NOT NULL" +
                ") t WHERE rn <= ?";
        streamingJdbc.query(sql, rs -> {
            String keyword = rs.getString("keyword");
            String text = rs.getString("text");
            double score = rs.getDouble("sentiment_score");
            if (rs.wasNull()) score = 0.0;
            acc.add(keyword, "x", text, score);
        }, MAX_POSTS_PER_PLATFORM);
    }

    /**
     * youtube / reddit / instagram store a sentiment_category string instead of a numeric score.
     * {@code titleCol} is non-null only for reddit, whose title is concatenated with the body.
     */
    private void scanCategoryPlatform(Accumulator acc, String platform, String table,
                                      String textCol, String titleCol, String orderCol) {
        String selectCols = (titleCol != null ? titleCol + ", " : "") + textCol + ", sentiment_category, keyword";
        String sql =
                "SELECT keyword, " + (titleCol != null ? titleCol + ", " : "") + textCol + ", sentiment_category FROM (" +
                "  SELECT " + selectCols + ", " +
                "         ROW_NUMBER() OVER (PARTITION BY keyword ORDER BY " + orderCol + " DESC) AS rn " +
                "  FROM " + table + " WHERE keyword IS NOT NULL" +
                ") t WHERE rn <= ?";
        streamingJdbc.query(sql, rs -> {
            String keyword = rs.getString("keyword");
            String text = titleCol != null
                    ? combine(rs.getString(titleCol), rs.getString(textCol))
                    : rs.getString(textCol);
            double score = categoryToScore(rs.getString("sentiment_category"));
            acc.add(keyword, platform, text, score);
        }, MAX_POSTS_PER_PLATFORM);
    }

    /**
     * Accumulates the scan results. Cheap bookkeeping (post counts, buffering) happens inline on
     * the cursor thread; the expensive CoreNLP annotation is deferred and run NLP_BATCH posts at a
     * time across all cores. Aspect merging is done single-threaded after each parallel flush, so
     * the shared maps never need synchronization.
     */
    private final class Accumulator {
        // (keyword|platform) -> aspect -> [sentiment_sum, mention_count]
        final Map<String, Map<String, double[]>> agg = new HashMap<>();
        // (keyword|platform) -> posts considered
        final Map<String, Integer> postCounts = new HashMap<>();
        // Posts awaiting annotation in the current batch.
        private final List<Post> buffer = new ArrayList<>(NLP_BATCH);

        /** Counts every row the capped query returned (matching the old rows.size()); blanks add no aspects. */
        void add(String keyword, String platform, String text, double score) {
            if (keyword == null) return;
            String key = keyword + KEY_SEP + platform;
            postCounts.merge(key, 1, Integer::sum);
            if (text == null || text.isBlank()) return;
            buffer.add(new Post(key, text, score));
            if (buffer.size() >= NLP_BATCH) flush();
        }

        /** Annotates the buffered posts in parallel, then folds the nouns into the running sums. */
        void flush() {
            if (buffer.isEmpty()) return;
            List<Map.Entry<String, Map<String, Double>>> annotated = buffer.parallelStream()
                    .map(p -> Map.entry(p.key(), analyzer.analyze(p.text(), p.score())))
                    .collect(Collectors.toList());
            for (Map.Entry<String, Map<String, Double>> e : annotated) {
                Map<String, double[]> target = agg.computeIfAbsent(e.getKey(), k -> new HashMap<>());
                for (Map.Entry<String, Double> a : e.getValue().entrySet()) {
                    double[] sums = target.computeIfAbsent(a.getKey(), k -> new double[2]);
                    sums[0] += a.getValue();
                    sums[1] += 1;
                }
            }
            buffer.clear();
        }
    }

    private record Post(String key, String text, double score) {}

    /** Atomically swaps in the freshly computed aggregates. */
    private void publish(Accumulator acc) {
        List<Object[]> aggRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, double[]>> group : acc.agg.entrySet()) {
            String[] kp = splitKey(group.getKey());
            for (Map.Entry<String, double[]> a : group.getValue().entrySet()) {
                aggRows.add(new Object[]{kp[0], kp[1], a.getKey(), a.getValue()[0], (int) a.getValue()[1]});
            }
        }
        List<Object[]> countRows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : acc.postCounts.entrySet()) {
            String[] kp = splitKey(e.getKey());
            countRows.add(new Object[]{kp[0], kp[1], e.getValue()});
        }

        writeTx.executeWithoutResult(status -> {
            jdbc.update("TRUNCATE TABLE aspect_drivers_agg");
            jdbc.update("TRUNCATE TABLE aspect_drivers_post_counts");
            batchInsert("INSERT INTO aspect_drivers_agg" +
                    "(keyword, platform, aspect, sentiment_sum, mention_count) VALUES (?, ?, ?, ?, ?)", aggRows);
            batchInsert("INSERT INTO aspect_drivers_post_counts" +
                    "(keyword, platform, post_count) VALUES (?, ?, ?)", countRows);
        });
    }

    private void batchInsert(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(sql, rows.subList(i, Math.min(i + BATCH_SIZE, rows.size())));
        }
    }

    private static String[] splitKey(String key) {
        int sep = key.indexOf(KEY_SEP);
        return new String[]{key.substring(0, sep), key.substring(sep + 1)};
    }

    /** Mirrors the category→score mapping the endpoint previously applied at request time. */
    private static double categoryToScore(String category) {
        if (category == null) return 0.0;
        return switch (category.toLowerCase()) {
            case "positive" -> 0.6;
            case "negative" -> -0.6;
            default         -> 0.0;
        };
    }

    private static String combine(String a, String b) {
        return ((a != null ? a : "") + " " + (b != null ? b : "")).trim();
    }
}
