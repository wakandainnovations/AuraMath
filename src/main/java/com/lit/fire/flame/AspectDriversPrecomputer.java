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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   aspect_drivers_agg(keyword, platform, aspect, sentiment_sum, mention_count, distinct_authors)
 *       — one row per (source keyword, platform, aspect noun). sentiment_sum/mention_count are
 *         kept (rather than a precomputed average) so the endpoint can re-aggregate across every
 *         keyword its ILIKE substring matches, exactly reproducing the old per-request grouping.
 *         distinct_authors is the count of distinct post authors that mentioned the aspect — kept
 *         alongside mention_count so the ranking stage can require genuine author diversity, not
 *         just post volume, before treating an aspect as a real strength/weakness (see class doc
 *         on {@link AspectSentimentAnalyzer#analyzeAspectSentiment}: a handful of near-duplicate
 *         posts from one thread or one bot/campaign account would otherwise be able to dominate
 *         the ranking on mention_count alone).
 *   aspect_drivers_post_counts(keyword, platform, post_count)
 *       — posts considered per (keyword, platform); backs the response's totalPostsAnalyzed.
 *
 * Each post is annotated exactly once per run (capped to the MAX_POSTS_PER_PLATFORM most recent
 * posts per keyword per platform, matching the endpoint's historical limit) using
 * {@link AspectSentimentAnalyzer#analyzeAspectSentiment}, which scores each aspect from its own
 * sentence rather than copying the whole post's sentiment onto every noun. On any failure the
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
    // stream enough work per flush to keep every core busy. Lower than before analyzeAspectSentiment
    // added constituency parsing + neural sentiment scoring to this pipeline (see
    // AspectSentimentAnalyzer): those annotators are far more memory-hungry per sentence than the
    // original tokenize/pos-only pass, so the same batch size that was safe before could hold too
    // many parse trees alive at once under parallelStream and exhaust the heap.
    private static final int NLP_BATCH = 500;

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
                    "distinct_authors INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (keyword, platform, aspect))");
            // Table may already exist from before distinct_authors was added; CREATE ... IF NOT
            // EXISTS alone wouldn't add it to an already-existing table.
            jdbc.execute("ALTER TABLE aspect_drivers_agg ADD COLUMN IF NOT EXISTS distinct_authors INTEGER NOT NULL DEFAULT 0");
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
        } catch (Throwable t) {
            // Throwable, not Exception: a pathological post can make the parser exhaust the heap
            // (OutOfMemoryError is an Error, not an Exception) partway through the scan. This is a
            // scheduled job's outer boundary — the doc comment above promises a failed run leaves
            // the previously published tables intact, which requires catching that too, not just
            // letting it escape to Spring's generic scheduled-task error handler (which would log
            // it but silently skip straight to the next scheduled run 24h later).
            log.error("Aspect-drivers precompute failed; previously published tables left in place", t);
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

    /**
     * x_posts carries a numeric sentiment_score on a 1–100 scale (50 = neutral, 0 = invalid).
     * Scores of 0 are excluded by the SQL filter — this is still used as a "did the primary
     * pipeline consider this post valid" gate, even though the value itself is no longer used to
     * score aspects (each aspect is scored from its own sentence by
     * {@link AspectSentimentAnalyzer#analyzeAspectSentiment}, not this document-level number).
     */
    private void scanX(Accumulator acc) {
        String sql =
                "SELECT keyword, text, sentiment_score, author FROM (" +
                "  SELECT keyword, text, sentiment_score, author, " +
                "         ROW_NUMBER() OVER (PARTITION BY keyword ORDER BY created_at DESC) AS rn " +
                "  FROM x_posts WHERE keyword IS NOT NULL AND text IS NOT NULL" +
                "    AND sentiment_score BETWEEN 1 AND 100" +
                ") t WHERE rn <= ?";
        streamingJdbc.query(sql, rs -> {
            String keyword = rs.getString("keyword");
            String text = rs.getString("text");
            double raw = rs.getDouble("sentiment_score");
            if (rs.wasNull() || raw < 1.0 || raw > 100.0) return; // 0 = invalid; valid range is [1, 100]
            acc.add(keyword, "x", text, rs.getString("author"));
        }, MAX_POSTS_PER_PLATFORM);
    }

    /**
     * youtube / reddit / instagram store a sentiment_category string instead of a numeric score;
     * a non-null category is used the same way as x_posts's valid-score gate above. {@code titleCol}
     * is non-null only for reddit, whose title is concatenated with the body.
     */
    private void scanCategoryPlatform(Accumulator acc, String platform, String table,
                                      String textCol, String titleCol, String orderCol) {
        String selectCols = (titleCol != null ? titleCol + ", " : "") + textCol + ", sentiment_category, keyword, author";
        String sql =
                "SELECT keyword, " + (titleCol != null ? titleCol + ", " : "") + textCol + ", sentiment_category, author FROM (" +
                "  SELECT " + selectCols + ", " +
                "         ROW_NUMBER() OVER (PARTITION BY keyword ORDER BY " + orderCol + " DESC) AS rn " +
                "  FROM " + table + " WHERE keyword IS NOT NULL AND sentiment_category IS NOT NULL" +
                ") t WHERE rn <= ?";
        streamingJdbc.query(sql, rs -> {
            String keyword = rs.getString("keyword");
            String text = titleCol != null
                    ? combine(rs.getString(titleCol), rs.getString(textCol))
                    : rs.getString(textCol);
            acc.add(keyword, platform, text, rs.getString("author"));
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
        // (keyword|platform) -> aspect -> distinct authors who mentioned it. Kept separately from
        // agg (rather than folded into a running count) since a Set is the only way to de-duplicate
        // an author across posts/batches.
        final Map<String, Map<String, Set<String>>> authorsByAspect = new HashMap<>();
        // (keyword|platform) -> posts considered
        final Map<String, Integer> postCounts = new HashMap<>();
        // Posts awaiting annotation in the current batch.
        private final List<Post> buffer = new ArrayList<>(NLP_BATCH);

        /** Counts every row the capped query returned (matching the old rows.size()); blanks add no aspects. */
        void add(String keyword, String platform, String text, String author) {
            if (keyword == null) return;
            String key = keyword + KEY_SEP + platform;
            postCounts.merge(key, 1, Integer::sum);
            if (text == null || text.isBlank()) return;
            buffer.add(new Post(key, text, author));
            if (buffer.size() >= NLP_BATCH) flush();
        }

        /**
         * Annotates the buffered posts in parallel, then folds the aspects into the running sums.
         * Real-world social-media text occasionally produces a pathological CoreNLP parse — a
         * post with no sentence-ending punctuation can hand the parser one huge "sentence"
         * (OutOfMemoryError), or an unusual token/punctuation pattern can build a degenerate,
         * deeply-nested tree during sentiment binarization (StackOverflowError) — either of which
         * previously crashed this entire scheduled run over a single bad post. One post's analysis
         * failing is now isolated and skipped (logged once at WARN, not per-occurrence) rather than
         * losing the whole batch; catching Throwable (not just Exception) is deliberate since both
         * failure modes seen so far are Errors.
         */
        void flush() {
            if (buffer.isEmpty()) return;
            List<AnnotatedPost> annotated = buffer.parallelStream()
                    .map(p -> {
                        try {
                            return new AnnotatedPost(p.key(), p.author(), analyzer.analyzeAspectSentiment(p.text()));
                        } catch (Throwable t) {
                            log.warn("Skipping one post during aspect analysis (key={}): {}: {}",
                                    p.key(), t.getClass().getSimpleName(), t.getMessage());
                            return new AnnotatedPost(p.key(), p.author(), Map.of());
                        }
                    })
                    .collect(Collectors.toList());
            for (AnnotatedPost ap : annotated) {
                Map<String, double[]> target = agg.computeIfAbsent(ap.key(), k -> new HashMap<>());
                Map<String, Set<String>> authorTarget = authorsByAspect.computeIfAbsent(ap.key(), k -> new HashMap<>());
                for (Map.Entry<String, Double> a : ap.aspects().entrySet()) {
                    double[] sums = target.computeIfAbsent(a.getKey(), k -> new double[2]);
                    sums[0] += a.getValue();
                    sums[1] += 1;
                    if (ap.author() != null) {
                        authorTarget.computeIfAbsent(a.getKey(), k -> new HashSet<>()).add(ap.author());
                    }
                }
            }
            buffer.clear();
        }
    }

    private record Post(String key, String text, String author) {}
    private record AnnotatedPost(String key, String author, Map<String, Double> aspects) {}

    /** Atomically swaps in the freshly computed aggregates. */
    private void publish(Accumulator acc) {
        List<Object[]> aggRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, double[]>> group : acc.agg.entrySet()) {
            String[] kp = splitKey(group.getKey());
            Map<String, Set<String>> authorsForGroup = acc.authorsByAspect.getOrDefault(group.getKey(), Map.of());
            for (Map.Entry<String, double[]> a : group.getValue().entrySet()) {
                int distinctAuthors = authorsForGroup.getOrDefault(a.getKey(), Set.of()).size();
                aggRows.add(new Object[]{
                        kp[0], kp[1], a.getKey(), a.getValue()[0], (int) a.getValue()[1], distinctAuthors});
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
                    "(keyword, platform, aspect, sentiment_sum, mention_count, distinct_authors) VALUES (?, ?, ?, ?, ?, ?)", aggRows);
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

    private static String combine(String a, String b) {
        return ((a != null ? a : "") + " " + (b != null ? b : "")).trim();
    }
}
