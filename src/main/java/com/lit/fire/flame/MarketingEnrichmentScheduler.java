package com.lit.fire.flame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Periodically rebuilds {@code marketing_target_profiles} by re-running
 * {@link MarketingEnrichmentEngine#enrichAndSave()}, then refreshes
 * {@link UserEngagementRatingService}'s engagement_score_raw/engagement_rating columns on
 * top of the rebuilt rows.
 *
 * The table is a snapshot: profiles only exist for authors that were present in the
 * source tables the last time enrichment ran. With no refresh it drifts stale, and
 * authors ingested afterwards have no profile — so /find-lookalikes 400s for them
 * with "Unknown seedAuthorId" even though their posts are in the database. This job
 * keeps the snapshot current so newly-ingested authors become resolvable.
 *
 * The run is CPU-heavy (per-author Hawkes/aspect analysis plus a full tribe
 * re-clustering, several minutes over the whole population), so it is cron-scheduled
 * for a low-traffic hour rather than fired at a fixed rate from startup. A
 * session-level Postgres advisory lock guards the run: when scaled to multiple
 * replicas only one rebuilds the shared table, the rest skip. Set the cron to
 * {@code -} to disable.
 */
@Service
public class MarketingEnrichmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketingEnrichmentScheduler.class);

    // Application-defined key for pg_advisory_lock so only one instance rebuilds at a time.
    // Arbitrary but stable constant (ASCII "MktEnrch").
    private static final long ADVISORY_LOCK_KEY = 0x4D6B744565726368L;

    private final MarketingEnrichmentEngine engine;
    private final UserEngagementRatingService engagementRatingService;
    private final DataSource dataSource; // owns the connection that holds the advisory lock

    public MarketingEnrichmentScheduler(MarketingEnrichmentEngine engine,
                                         UserEngagementRatingService engagementRatingService,
                                         DataSource dataSource) {
        this.engine = engine;
        this.engagementRatingService = engagementRatingService;
        this.dataSource = dataSource;
    }

    /**
     * Rebuilds marketing_target_profiles on a cron (default 03:30 daily, UTC). Configure via
     * {@code marketing.enrichment.cron} / {@code marketing.enrichment.zone}; set the cron to
     * {@code -} to disable the schedule (the /api/admin/run-enrichment endpoint still works).
     */
    @Scheduled(cron = "${marketing.enrichment.cron:0 30 3 * * *}", zone = "${marketing.enrichment.zone:UTC}")
    public void refresh() {
        // The advisory lock is session-scoped, so it must be acquired and released on the same
        // physical connection — hold one open for the duration rather than going through JdbcTemplate.
        try (Connection lockConn = dataSource.getConnection()) {
            if (!tryAdvisoryLock(lockConn)) {
                log.info("Marketing enrichment skipped: another instance holds the refresh lock");
                return;
            }
            try {
                long start = System.currentTimeMillis();
                log.info("Marketing enrichment refresh starting");
                engine.enrichAndSave();
                // Runs after enrichAndSave(), under the same advisory lock: engagement_rating is an
                // UPDATE against existing marketing_target_profiles rows, so it depends on this
                // run's enrichment having (re)populated them first.
                engagementRatingService.recomputeAndPersist();
                log.info("Marketing enrichment refresh complete in {} ms", System.currentTimeMillis() - start);
            } finally {
                releaseAdvisoryLock(lockConn);
            }
        } catch (Exception e) {
            log.error("Marketing enrichment refresh failed", e);
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
            log.warn("Could not explicitly release marketing-enrichment advisory lock: {}", e.getMessage());
        }
    }
}
