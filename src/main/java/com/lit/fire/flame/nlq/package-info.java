/**
 * The AuraMath "Ask" engine: a natural-language &rarr; database + mathematician feature.
 *
 * <p>Given a target database connection (supplied per-request and fully isolated from
 * AuraMath's own {@link com.lit.fire.flame.DataSourceConfig} datasource) and a question in
 * plain English, the engine introspects the schema, asks a pluggable LLM to draft SQL,
 * validates and executes that SQL <strong>read-only</strong>, then optionally runs
 * mathematical post-processing before composing a natural-language answer.
 *
 * <p>Hard guarantees enforced throughout this package:
 * <ul>
 *   <li><b>Read-only:</b> only {@code SELECT}/{@code WITH} queries are ever generated or executed.</li>
 *   <li><b>Isolation:</b> target connections are per-request and never touch the app datasource.</li>
 *   <li><b>Skip-list:</b> tables/columns a request asks to skip are excluded from the schema given
 *       to the model AND re-enforced at validation/execution.</li>
 *   <li><b>Pluggable LLM:</b> the model layer sits behind an {@code LlmClient} interface
 *       (Claude implementation first).</li>
 * </ul>
 *
 * <p>See {@code docs/ask-engine/DESIGN.md} for the end-to-end pipeline and the F0&ndash;F11 checklist.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code nlq.connection} &ndash; per-request target datasource construction &amp; isolation.</li>
 *   <li>{@code nlq.schema} &ndash; schema introspection and skip-list filtering.</li>
 *   <li>{@code nlq.llm} &ndash; pluggable {@code LlmClient} abstraction and providers.</li>
 *   <li>{@code nlq.sql} &ndash; NL&rarr;SQL prompting, read-only validation, and execution.</li>
 *   <li>{@code nlq.math} &ndash; mathematical post-processing of result sets.</li>
 *   <li>{@code nlq.api} &ndash; REST controllers and request/response DTOs.</li>
 *   <li>{@code nlq.config} &ndash; configuration properties and bean wiring.</li>
 * </ul>
 */
package com.lit.fire.flame.nlq;
