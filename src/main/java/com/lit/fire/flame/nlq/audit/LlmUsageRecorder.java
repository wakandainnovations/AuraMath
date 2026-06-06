package com.lit.fire.flame.nlq.audit;

import org.springframework.stereotype.Component;

/**
 * Per-request accumulator for LLM token usage (F10), so the audit line can report how many tokens an
 * Ask consumed without threading usage data back through every service signature.
 *
 * <p>One Ask runs its whole F1–F7 pipeline on a single request thread, issuing several model calls
 * (SQL generation, computation plan, narrative). This recorder holds a {@link ThreadLocal} tally that
 * the {@link RecordingLlmClient} decorator feeds on each {@link com.lit.fire.flame.nlq.llm.LlmClient#complete}
 * call; the {@link com.lit.fire.flame.nlq.api.AskOrchestrator orchestrator} {@link #start() starts} a
 * tally before the pipeline, {@link #snapshot() reads} it for the audit record, and {@link #clear()
 * clears} it in a {@code finally} block so the thread carries nothing into the next request.
 *
 * <p>Token counts reported by a provider as {@code -1} (unknown) are simply not added; {@link #snapshot()}
 * therefore reports {@code 0} tokens (but a non-zero call count) when a provider does not meter usage.
 * This records <b>counts only</b> — never any prompt, completion text, or API key.
 */
@Component
public class LlmUsageRecorder {

    private final ThreadLocal<Usage> current = new ThreadLocal<>();

    /** Begin a fresh tally for the current thread, discarding any previous one. */
    public void start() {
        current.set(new Usage());
    }

    /**
     * Add one completed model call's token usage to the current thread's tally. A no-op when no tally
     * is active. Negative (unknown) token counts are ignored; the call is still counted.
     */
    public void record(int inputTokens, int outputTokens) {
        Usage usage = current.get();
        if (usage == null) {
            return;
        }
        usage.calls++;
        if (inputTokens > 0) {
            usage.inputTokens += inputTokens;
        }
        if (outputTokens > 0) {
            usage.outputTokens += outputTokens;
        }
    }

    /** An immutable copy of the current thread's tally, or a zeroed tally when none is active. */
    public Usage snapshot() {
        Usage usage = current.get();
        return (usage == null) ? new Usage() : usage.copy();
    }

    /** Drop the current thread's tally. Always call this in a {@code finally} after {@link #start()}. */
    public void clear() {
        current.remove();
    }

    /** A small, copyable tally of token usage across the model calls of one Ask. */
    public static final class Usage {
        private int calls;
        private int inputTokens;
        private int outputTokens;

        /** Number of model calls made. */
        public int getCalls() {
            return calls;
        }

        /** Total input (prompt) tokens billed across all calls (unknown counts excluded). */
        public int getInputTokens() {
            return inputTokens;
        }

        /** Total output (completion) tokens billed across all calls (unknown counts excluded). */
        public int getOutputTokens() {
            return outputTokens;
        }

        private Usage copy() {
            Usage c = new Usage();
            c.calls = calls;
            c.inputTokens = inputTokens;
            c.outputTokens = outputTokens;
            return c;
        }
    }
}
