package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.audit.AskMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Small admin endpoint exposing the Ask engine's operational counters (F10).
 *
 * <p>Sits at {@code GET /api/ask/admin/metrics} and returns a point-in-time {@link AskMetrics.Snapshot}
 * — process-wide counts (monotonic since boot) of requests, answers, clarifications, errors, and the
 * specific failure modes (unsafe-SQL rejections, execution timeouts, LLM failures). It is the
 * dependency-free alternative to Actuator/Micrometer; the per-request detail lives in the audit log.
 *
 * <p>The endpoint exposes <b>counts only</b> — no questions, SQL, credentials, or row data.
 */
@RestController
@RequestMapping("/api/ask/admin")
public class AskMetricsController {

    private final AskMetrics metrics;

    public AskMetricsController(AskMetrics metrics) {
        this.metrics = metrics;
    }

    /** Current operational counters for the Ask engine. */
    @GetMapping("/metrics")
    public AskMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }
}
