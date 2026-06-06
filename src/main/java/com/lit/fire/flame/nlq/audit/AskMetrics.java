package com.lit.fire.flame.nlq.audit;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, in-memory operational counters for the Ask engine (F10).
 *
 * <p>Rather than pull in Spring Boot Actuator / Micrometer, the engine keeps a handful of process-wide
 * {@link AtomicLong} counters and exposes a {@link #snapshot()} of them through a small admin endpoint
 * ({@code GET /api/ask/admin/metrics}). Counts are monotonic since process start and reset on restart.
 *
 * <p>The orchestrator bumps these as each request resolves: {@link #incrementRequests()} on entry,
 * then exactly one terminal counter per request — {@link #incrementAnswers()},
 * {@link #incrementClarifications()}, or {@link #incrementErrors()} — plus, on the error path, a more
 * specific failure counter ({@link #incrementUnsafeSqlRejections()}, {@link #incrementExecutionTimeouts()},
 * or {@link #incrementLlmFailures()}) where the cause is known.
 */
@Component
public class AskMetrics {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong answers = new AtomicLong();
    private final AtomicLong clarifications = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong unsafeSqlRejections = new AtomicLong();
    private final AtomicLong executionTimeouts = new AtomicLong();
    private final AtomicLong llmFailures = new AtomicLong();

    public void incrementRequests() {
        requests.incrementAndGet();
    }

    public void incrementAnswers() {
        answers.incrementAndGet();
    }

    public void incrementClarifications() {
        clarifications.incrementAndGet();
    }

    public void incrementErrors() {
        errors.incrementAndGet();
    }

    public void incrementUnsafeSqlRejections() {
        unsafeSqlRejections.incrementAndGet();
    }

    public void incrementExecutionTimeouts() {
        executionTimeouts.incrementAndGet();
    }

    public void incrementLlmFailures() {
        llmFailures.incrementAndGet();
    }

    /** An immutable point-in-time view of every counter, for the admin endpoint. */
    public Snapshot snapshot() {
        return new Snapshot(requests.get(), answers.get(), clarifications.get(), errors.get(),
                unsafeSqlRejections.get(), executionTimeouts.get(), llmFailures.get());
    }

    /** Immutable counter snapshot, serialized as-is by the admin endpoint. */
    public static final class Snapshot {
        private final long requests;
        private final long answers;
        private final long clarifications;
        private final long errors;
        private final long unsafeSqlRejections;
        private final long executionTimeouts;
        private final long llmFailures;

        Snapshot(long requests, long answers, long clarifications, long errors,
                 long unsafeSqlRejections, long executionTimeouts, long llmFailures) {
            this.requests = requests;
            this.answers = answers;
            this.clarifications = clarifications;
            this.errors = errors;
            this.unsafeSqlRejections = unsafeSqlRejections;
            this.executionTimeouts = executionTimeouts;
            this.llmFailures = llmFailures;
        }

        /** Total Ask requests received. */
        public long getRequests() {
            return requests;
        }

        /** Requests that returned an answer. */
        public long getAnswers() {
            return answers;
        }

        /** Requests that returned a clarification instead of an answer. */
        public long getClarifications() {
            return clarifications;
        }

        /** Requests that failed with an error (any cause). */
        public long getErrors() {
            return errors;
        }

        /** Requests rejected because the generated SQL failed the safety guard. */
        public long getUnsafeSqlRejections() {
            return unsafeSqlRejections;
        }

        /** Requests whose query execution timed out. */
        public long getExecutionTimeouts() {
            return executionTimeouts;
        }

        /** Requests that failed because of an LLM call (error or timeout). */
        public long getLlmFailures() {
            return llmFailures;
        }
    }
}
