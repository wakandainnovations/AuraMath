package com.lit.fire.flame.nlq.audit;

import com.lit.fire.flame.nlq.llm.LlmClient;
import com.lit.fire.flame.nlq.llm.LlmException;
import com.lit.fire.flame.nlq.llm.LlmRequest;
import com.lit.fire.flame.nlq.llm.LlmResponse;

/**
 * A transparent {@link LlmClient} decorator (F10) that meters token usage into the request-scoped
 * {@link LlmUsageRecorder} without changing the provider-neutral contract.
 *
 * <p>It delegates {@link #complete} straight to the wrapped client and, on a successful response,
 * records the response's reported token counts on the current thread's tally. A failed call (an
 * {@link LlmException}) propagates unchanged and records nothing. The decorator never inspects or logs
 * prompt or completion content — only the numeric token counts already present on the
 * {@link LlmResponse}.
 *
 * <p>This is how the Claude (or any future) {@code LlmClient} is wired in
 * {@code AskEngineConfiguration}, so the audit line can report per-request token usage while callers
 * keep talking to the plain {@link LlmClient} interface.
 */
public class RecordingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final LlmUsageRecorder usageRecorder;

    public RecordingLlmClient(LlmClient delegate, LlmUsageRecorder usageRecorder) {
        this.delegate = delegate;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        LlmResponse response = delegate.complete(request);
        usageRecorder.record(response.getInputTokens(), response.getOutputTokens());
        return response;
    }
}
