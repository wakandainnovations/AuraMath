/**
 * REST controllers and request/response DTOs for the Ask engine.
 *
 * <p>Follows the existing AuraMath controller style ({@code @RestController} +
 * {@code @RequestMapping}, Gson serialization). Two sibling controllers sit under {@code /api/ask}:
 * {@link com.lit.fire.flame.nlq.api.AskConnectionController} exposes the {@code POST
 * /api/ask/test-connection} probe (F1), and {@link com.lit.fire.flame.nlq.api.AskController} exposes
 * the end-to-end {@code POST /api/ask} endpoint (F8).
 *
 * <p>{@link com.lit.fire.flame.nlq.api.AskOrchestrator} runs the full F1–F7 pipeline for one
 * {@link com.lit.fire.flame.nlq.api.AskRequest} and returns an
 * {@link com.lit.fire.flame.nlq.api.AskResponse} (an answer, or a clarification); hard failures are
 * mapped by the controller to sanitized HTTP errors carrying an
 * {@link com.lit.fire.flame.nlq.api.AskErrorResponse}. No credentials ever appear in a response or
 * log.
 */
package com.lit.fire.flame.nlq.api;
