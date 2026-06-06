/**
 * Auditability and operational visibility for the Ask engine (F10).
 *
 * <p>Every Ask produces one {@link com.lit.fire.flame.nlq.audit.AskAuditRecord} — a credential-free,
 * row-value-free trace (request id, target host/product, question, generated SQL, tables used,
 * row count/truncation, per-stage latency, LLM token usage, outcome + sanitized reason, and which
 * objects were skipped/masked). {@link com.lit.fire.flame.nlq.audit.AskAuditLogger} emits it as a
 * structured JSON log line via SLF4J and, when {@code aura.ask.audit.persist=true}, also persists it
 * to a table in AuraMath's own database (via the application {@code JdbcTemplate}, never the
 * per-request target connection).
 *
 * <p>Token usage is gathered without changing the provider-neutral
 * {@link com.lit.fire.flame.nlq.llm.LlmClient} contract: the
 * {@link com.lit.fire.flame.nlq.audit.RecordingLlmClient} decorator meters each completion into a
 * request-scoped {@link com.lit.fire.flame.nlq.audit.LlmUsageRecorder}.
 *
 * <p>{@link com.lit.fire.flame.nlq.audit.AskMetrics} keeps lightweight in-memory counters (requests,
 * clarifications, unsafe-SQL rejections, execution timeouts, LLM failures) surfaced by the
 * {@code GET /api/ask/admin/metrics} admin endpoint — no Actuator/Micrometer dependency is required.
 */
package com.lit.fire.flame.nlq.audit;
