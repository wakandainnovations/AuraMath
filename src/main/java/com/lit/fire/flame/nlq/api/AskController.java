package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.llm.LlmException;
import com.lit.fire.flame.nlq.sql.QueryExecutionException;
import com.lit.fire.flame.nlq.sql.UnsafeSqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Locale;

/**
 * REST entry point for the Ask engine's end-to-end endpoint (F8): {@code POST /api/ask}.
 *
 * <p>A sibling of {@link AskConnectionController} (which owns {@code /api/ask/test-connection}); both
 * sit under {@code /api/ask}. This controller delegates the whole F1–F7 pipeline to
 * {@link AskOrchestrator} and maps its typed outcomes to clean HTTP responses:
 *
 * <ul>
 *   <li><b>200</b> with an {@link AskResponse} on a successful answer.</li>
 *   <li><b>400</b> with the {@link AskResponse} when the engine asks for clarification, or with an
 *       {@link AskErrorResponse} for a malformed/invalid request.</li>
 *   <li><b>422</b> when the drafted SQL cannot be made safe (unsafe / ungeneratable query).</li>
 *   <li><b>502</b> for an LLM or target-connection failure, <b>504</b> for a timeout.</li>
 *   <li><b>503</b> when the engine is disabled via {@code aura.ask.enabled=false}.</li>
 * </ul>
 *
 * <p>Every error body is <b>sanitized</b> — no credentials, raw driver text, prompts, or stack
 * traces — and the password supplied in the request body is never echoed or logged.
 */
@RestController
@RequestMapping("/api/ask")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final AskOrchestrator orchestrator;
    private final AskEngineProperties properties;

    public AskController(AskOrchestrator orchestrator, AskEngineProperties properties) {
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    /**
     * Answer a natural-language {@code question} against the per-request target database. Returns the
     * SQL, a rows preview, the formulas applied, and a natural-language answer — or a clarification
     * (HTTP 400) when the question cannot be answered from the non-skipped schema.
     */
    @PostMapping
    public ResponseEntity<?> ask(@RequestBody AskRequest request) {
        if (!properties.isEnabled()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "the Ask engine is disabled");
        }
        try {
            AskResponse response = orchestrator.ask(request);
            if (response.isClarificationNeeded()) {
                // A clarification is a well-formed "please refine" — surfaced as 400 with the body.
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Bad request / rejected connection details. The message is safe (no credentials).
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (UnsafeSqlException e) {
            // The model produced a query the safety guard refuses to run.
            return error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "the generated query was rejected by the safety guard (" + e.getReason() + ")");
        } catch (LlmException e) {
            HttpStatus status = (e.getKind() == LlmException.Kind.TIMEOUT)
                    ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            return error(status, "the language model call failed (" + e.getKind() + ")");
        } catch (QueryExecutionException e) {
            HttpStatus status;
            switch (e.getKind()) {
                case TIMEOUT:
                    status = HttpStatus.GATEWAY_TIMEOUT;
                    break;
                case UNSAFE_SQL:
                    status = HttpStatus.UNPROCESSABLE_ENTITY;
                    break;
                default:
                    status = HttpStatus.BAD_GATEWAY;
            }
            // QueryExecutionException messages are already sanitized.
            return error(status, e.getMessage());
        } catch (SQLException e) {
            HttpStatus status = looksLikeTimeout(e) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            // Never surface the raw driver message — it can echo connection details.
            return error(status, "could not establish a connection to the target database");
        } catch (RuntimeException e) {
            log.warn("Ask request failed unexpectedly", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "the request could not be completed");
        }
    }

    private static ResponseEntity<AskErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new AskErrorResponse(message));
    }

    private static boolean looksLikeTimeout(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout") || lower.contains("timed out") || lower.contains("login timeout");
    }
}
