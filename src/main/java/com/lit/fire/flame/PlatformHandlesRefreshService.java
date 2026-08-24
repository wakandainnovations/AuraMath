package com.lit.fire.flame;

import com.lit.fire.flame.mappers.PostMapper;
import com.lit.fire.flame.models.UniversalPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps {@code marketing_target_profiles.platform_handles} (profile URLs, per-platform post
 * counts) current, independent of {@link MarketingEnrichmentEngine}'s nightly run.
 *
 * The full enrichment run is slow because of its Stanford CoreNLP aspect-sentiment pass and
 * per-author Hawkes fit — both real dependencies (tribe_label/top_genres drive
 * {@link LookalikeDiscoveryService}'s topic-similarity scoring and tribe filter, and
 * {@code ViralSeedController}'s genre search), so that run can't be skipped. But
 * platform_handles itself needs none of that — it's pure aggregation over each author's own
 * posts (see {@link PlatformHandlesBuilder}), so authors newly ingested since the last full
 * enrichment run don't have to wait for it just to get profile-URL attribution: a full sweep
 * over ~99k authors takes under 3 seconds (measured), so this runs on its own much shorter cron
 * rather than waiting for the next nightly enrichment.
 *
 * Unlike {@link MarketingEnrichmentEngine#enrichAndSave()}, this scans every author regardless
 * of sentiment_score: platform_handles/profile_url identify who someone is, which doesn't depend
 * on whether their sentiment score was usable.
 */
@Service
public class PlatformHandlesRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PlatformHandlesRefreshService.class);
    private static final String[] PLATFORM_TABLES = {"x_posts", "youtube_comments", "reddit_posts", "instagram_posts"};

    // Application-defined key for pg_advisory_lock so only one instance refreshes at a time,
    // packed the same way MarketingEnrichmentScheduler packs "MktEnrch" (ASCII "PlatHndl").
    private static final long ADVISORY_LOCK_KEY = 0x506C6174486E646CL;

    private final DataSource dataSource; // owns the connection that holds the advisory lock
    private final JdbcTemplate jdbcTemplate;
    private final PostMapper postMapper;
    private final MarketingInsightsRepository marketingInsightsRepository;

    public PlatformHandlesRefreshService(DataSource dataSource,
                                          JdbcTemplate jdbcTemplate,
                                          PostMapper postMapper,
                                          MarketingInsightsRepository marketingInsightsRepository) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.postMapper = postMapper;
        this.marketingInsightsRepository = marketingInsightsRepository;
    }

    /**
     * Runs every 15 minutes by default — cheap enough (a few seconds over the whole population)
     * that this cadence closes the attribution gap for newly-ingested authors far faster than
     * waiting on MarketingEnrichmentScheduler's nightly run. A Postgres session-level advisory
     * lock guards the run so only one instance refreshes when scaled to multiple replicas.
     */
    @Scheduled(cron = "${platform.handles.refresh.cron:0 */15 * * * *}",
               zone = "${platform.handles.refresh.zone:UTC}")
    public void scheduledRefresh() {
        try (Connection lockConn = dataSource.getConnection()) {
            if (!tryAdvisoryLock(lockConn)) {
                log.info("Platform-handles refresh skipped: another instance holds the refresh lock");
                return;
            }
            try {
                recomputeAndPersist();
            } finally {
                releaseAdvisoryLock(lockConn);
            }
        } catch (Exception e) {
            log.error("Platform-handles refresh failed", e);
        }
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
            log.warn("Could not explicitly release platform-handles refresh advisory lock: {}", e.getMessage());
        }
    }

    public Map<String, Object> recomputeAndPersist() {
        long start = System.currentTimeMillis();

        Stream<UniversalPost> allPosts = Stream.of(PLATFORM_TABLES).flatMap(this::streamTable);
        Map<String, List<UniversalPost>> postsByAuthor = allPosts
                .filter(p -> p.getAuthorId() != null && !p.getAuthorId().isBlank())
                .collect(Collectors.groupingBy(UniversalPost::getAuthorId));

        List<Object[]> batchArgs = new ArrayList<>(postsByAuthor.size());
        for (Map.Entry<String, List<UniversalPost>> entry : postsByAuthor.entrySet()) {
            String authorId = entry.getKey();
            String platformHandles = PlatformHandlesBuilder.build(authorId, entry.getValue());
            batchArgs.add(new Object[]{authorId, platformHandles});
        }
        marketingInsightsRepository.batchUpsertPlatformHandles(batchArgs);

        long elapsedMs = System.currentTimeMillis() - start;
        log.info("Platform-handles refresh complete: {} authors in {} ms", batchArgs.size(), elapsedMs);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("authorsRefreshed", batchArgs.size());
        summary.put("elapsedMs", elapsedMs);
        return summary;
    }

    private Stream<UniversalPost> streamTable(String tableName) {
        String sql = "SELECT * FROM " + tableName + " WHERE author IS NOT NULL AND author <> ''";
        return jdbcTemplate.queryForStream(sql, (rs, rowNum) -> postMapper.map(rs, tableName));
    }
}
