/**
 * Pluggable LLM abstraction for the Ask engine.
 *
 * <p>The model layer sits behind an {@code LlmClient} interface so providers are swappable; the
 * Claude implementation is first. The provider is selected via {@code aura.ask.llm-provider}.
 */
package com.lit.fire.flame.nlq.llm;
