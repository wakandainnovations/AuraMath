package com.lit.fire.flame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Feature 10: weekly full-corpus re-scoring job. {@code movie_revenue_impact_model.py}
 * is a Python job, not a Java service, so this shells out via {@link ProcessBuilder}
 * (same {@link MovieRevenueModelDbConnectionDetails}-sourced connection args
 * {@link MovieRevenuePredictionService} already uses for the on-demand /predict
 * path), reusing {@link MarketingEnrichmentScheduler}'s cron + session-level
 * Postgres advisory-lock convention so only one replica runs the job at a time.
 *
 * Box-office factors don't move day to day the way the marketing enrichment
 * job's social signals do, so this runs weekly rather than daily -- Monday
 * 04:00 UTC by default. Set {@code movie.revenue.model.cron} to {@code -} to
 * disable the schedule (the {@code /api/admin/run-movie-revenue-model} trigger
 * still works). The full run's stdout+stderr is captured to a timestamped log
 * file under {@code movie.revenue.model.log-dir} rather than kept only in the
 * application log, since a single run can be a large, slow, multi-stage
 * pipeline (Stage A/B fits, SHAP, market comparison, ...) worth inspecting on
 * its own; {@link #getLastRun()} backs {@code GET /api/admin/movie-revenue-model-status}.
 *
 * <p>{@code --market} (india/global/all, {@code movie.revenue.model.market},
 * default {@code india}) selects the corpus the <em>primary</em> pipeline
 * trains/persists on -- the champion model artifact, and everything Feature
 * 10 writes to {@code movie_revenue_predictions}/{@code factor_impact_scores}/
 * {@code model_comparison_history}, all reflect this run's market. The
 * india-vs-pooled-global-vs-per-market <em>comparison</em> in
 * {@code model_comparison.json} always runs regardless, per the script's own
 * {@code --market} flag semantics -- only the persisted/served champion
 * changes.
 */
@Service
public class MovieRevenuePredictionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MovieRevenuePredictionScheduler.class);

    /** Same choices movie_revenue_impact_model.py's own --market argparse flag accepts. */
    public static final Set<String> VALID_MARKETS = Set.of("india", "global", "all");

    // Application-defined key for pg_advisory_lock so only one instance runs the model job at a time.
    // Arbitrary but stable constant (ASCII "MovieRev"), distinct from MarketingEnrichmentScheduler's own lock key.
    private static final long ADVISORY_LOCK_KEY = 0x4D6F766965526576L;

    private final DataSource dataSource; // owns the connection that holds the advisory lock
    private final String pythonExecutable;
    private final String scriptPath;
    private final String logDir;
    private final long timeoutSeconds;
    private final String defaultMarket;

    private final AtomicReference<RunStatus> lastRun = new AtomicReference<>();

    public MovieRevenuePredictionScheduler(
            DataSource dataSource,
            @Value("${movie.revenue.model.python:python3}") String pythonExecutable,
            @Value("${movie.revenue.model.script:scripts/movie_revenue_impact_model.py}") String scriptPath,
            @Value("${movie.revenue.model.log-dir:logs/movie-revenue-model}") String logDir,
            @Value("${movie.revenue.model.run-timeout-seconds:1800}") long timeoutSeconds,
            @Value("${movie.revenue.model.market:india}") String defaultMarket) {
        this.dataSource = dataSource;
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.logDir = logDir;
        this.timeoutSeconds = timeoutSeconds;
        if (!VALID_MARKETS.contains(defaultMarket)) {
            throw new IllegalArgumentException(
                "movie.revenue.model.market must be one of " + VALID_MARKETS + ", got: " + defaultMarket);
        }
        this.defaultMarket = defaultMarket;
    }

    /**
     * @param startedAt  ISO-8601 instant the subprocess was launched, or null if the run never started
     * @param finishedAt ISO-8601 instant the subprocess exited/was killed, or null if it never finished
     * @param exitCode   the subprocess's exit code, or null on a timeout/skip/launch failure
     * @param logPath    path to the full captured stdout+stderr log, or null if none was produced
     * @param logTail    last ~200 lines of that log, for a quick look without opening the file
     * @param error      human-readable failure/skip reason, or null on a clean exit-0 run
     * @param market     which --market this run used ("india", "global", or "all")
     */
    public record RunStatus(String startedAt, String finishedAt, Integer exitCode,
                             String logPath, String logTail, String error, String market) {
        public boolean success() {
            return exitCode != null && exitCode == 0;
        }
    }

    @Scheduled(cron = "${movie.revenue.model.cron:0 0 4 * * MON}", zone = "${movie.revenue.model.zone:UTC}")
    public void runScheduled() {
        run(defaultMarket);
    }

    /** Runs with the configured default market ({@code movie.revenue.model.market}, "india" unless overridden). */
    public RunStatus run() {
        return run(defaultMarket);
    }

    /**
     * Runs the full pipeline synchronously (blocking) for the given market --
     * called by both the cron trigger above (with the configured default) and
     * {@code POST /api/admin/run-movie-revenue-model} (optionally overriding
     * it per call), matching the existing {@code /api/admin/run-enrichment}
     * trigger's synchronous shape.
     *
     * @param market one of {@link #VALID_MARKETS}; the caller (the controller)
     *               is responsible for rejecting anything else with a 400
     *               before this is reached.
     */
    public RunStatus run(String market) {
        try (Connection lockConn = dataSource.getConnection()) {
            if (!tryAdvisoryLock(lockConn)) {
                log.info("Movie revenue model run skipped: another instance holds the run lock");
                return new RunStatus(null, null, null, null, null,
                    "skipped: another instance holds the run lock", market);
            }
            try {
                RunStatus status = doRun(market);
                lastRun.set(status);
                return status;
            } finally {
                releaseAdvisoryLock(lockConn);
            }
        } catch (Exception e) {
            log.error("Movie revenue model run failed to start", e);
            RunStatus status = new RunStatus(null, null, null, null, null, e.getMessage(), market);
            lastRun.set(status);
            return status;
        }
    }

    private RunStatus doRun(String market) throws IOException, InterruptedException {
        MovieRevenueModelDbConnectionDetails db = MovieRevenueModelDbConnectionDetails.load();
        Files.createDirectories(Paths.get(logDir));
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .format(LocalDateTime.now(ZoneOffset.UTC));
        Path logPath = Paths.get(logDir, "run-" + timestamp + ".log");

        ProcessBuilder pb = new ProcessBuilder(
            pythonExecutable, scriptPath,
            "--db-host", db.host, "--db-port", db.port, "--db-name", db.name,
            "--db-user", db.user, "--db-password", db.password,
            "--market", market);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logPath.toFile());

        String startedAt = Instant.now().toString();
        log.info("Starting movie revenue model run (market={}), logging to {}", market, logPath);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new RunStatus(startedAt, Instant.now().toString(), null, null, null,
                "Failed to start " + pythonExecutable + " " + scriptPath + ": " + e.getMessage(), market);
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        String finishedAt = Instant.now().toString();
        Integer exitCode = null;
        String error = null;
        if (!finished) {
            process.destroyForcibly();
            error = "Timed out after " + timeoutSeconds + "s";
        } else {
            exitCode = process.exitValue();
            if (exitCode != 0) {
                error = "Exited " + exitCode;
            }
        }
        String tail = readLogTail(logPath, 200);
        log.info("Movie revenue model run finished: exitCode={} log={}", exitCode, logPath);
        return new RunStatus(startedAt, finishedAt, exitCode, logPath.toString(), tail, error, market);
    }

    /** Last completed/attempted run's status, or null if this instance has never run it. */
    public RunStatus getLastRun() {
        return lastRun.get();
    }

    private static String readLogTail(Path logPath, int maxLines) {
        try {
            List<String> lines = Files.readAllLines(logPath);
            int from = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return null;
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
            log.warn("Could not explicitly release movie-revenue-model advisory lock: {}", e.getMessage());
        }
    }
}
