package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java-side admin surface for Feature 2's factor registry -- lets a
 * non-Python teammate register/promote a factor or hand the system a
 * spreadsheet of {@code movie_factor_values} scores without touching
 * {@code movie_revenue_impact_model.py} or the Postgres console directly.
 * See {@code scripts/register_factor.py} for the equivalent CLI path.
 */
@RestController
@RequestMapping("/api/admin")
public class FactorDefinitionController {

    @Autowired
    private FactorDefinitionRepository repository;

    /** GET /api/admin/factor-definitions?status=active */
    @GetMapping("/factor-definitions")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String status) {
        if (status != null && !status.isBlank() && !FactorDefinitionRepository.STATUS_VALUES.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "status must be one of " + FactorDefinitionRepository.STATUS_VALUES));
        }
        List<Map<String, Object>> rows = repository.list(status);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", rows.size());
        resp.put("factors", rows);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/factor-definitions/{key}")
    public ResponseEntity<?> getOne(@PathVariable("key") String factorKey) {
        Map<String, Object> row = repository.get(factorKey);
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of("message", "No factor_definitions row for " + factorKey));
        }
        return ResponseEntity.ok(row);
    }

    /**
     * Create-or-update one factor_definitions row -- the direct answer to
     * "let me add more parameters in the future without touching the code".
     */
    @PostMapping("/factor-definitions")
    public ResponseEntity<?> upsert(@RequestBody FactorDefinitionRepository.FactorDefinitionRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "request body is required"));
        }
        List<String> errors = FactorDefinitionRepository.validate(request, true);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
        repository.upsert(request);
        return ResponseEntity.ok(Map.of("factorKey", request.factorKey(),
            "status", request.status() == null ? "candidate" : request.status()));
    }

    public record StatusUpdateRequest(String status) {}

    /** Promote candidate -&gt; active, or deprecate/mark explanatory_only. */
    @PatchMapping("/factor-definitions/{key}/status")
    public ResponseEntity<?> updateStatus(@PathVariable("key") String factorKey,
                                           @RequestBody StatusUpdateRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }
        if (!FactorDefinitionRepository.STATUS_VALUES.contains(request.status())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "status must be one of " + FactorDefinitionRepository.STATUS_VALUES));
        }
        boolean updated = repository.updateStatus(factorKey, request.status());
        if (!updated) {
            return ResponseEntity.status(404).body(Map.of("message", "No factor_definitions row for " + factorKey));
        }
        return ResponseEntity.ok(Map.of("factorKey", factorKey, "status", request.status()));
    }

    public record FactorValuesBulkRequest(List<FactorDefinitionRepository.FactorValueEntry> values) {}

    /**
     * Bulk upsert into movie_factor_values -- "hand the system a spreadsheet
     * of scores" for a factor that doesn't warrant a dedicated column. Each
     * entry needs either {@code movieKey} directly, or
     * {@code movieName}/{@code releaseDate}/{@code language} (the same
     * composite Feature 1's data_sources uses) to derive it from.
     */
    @PostMapping("/factor-values")
    public ResponseEntity<?> bulkUpsertValues(@RequestBody FactorValuesBulkRequest request) {
        if (request == null || request.values() == null || request.values().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "values must be a non-empty list"));
        }
        for (FactorDefinitionRepository.FactorValueEntry e : request.values()) {
            if (e.factorKey() == null || e.factorKey().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "every entry needs factorKey"));
            }
            boolean hasMovieKey = e.movieKey() != null && !e.movieKey().isBlank();
            boolean hasComposite = e.movieName() != null && e.releaseDate() != null && e.language() != null;
            if (!hasMovieKey && !hasComposite) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "every entry needs movieKey, or movieName+releaseDate+language"));
            }
            if (e.valueNumeric() == null && (e.valueText() == null || e.valueText().isBlank())) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "every entry needs valueNumeric or valueText"));
            }
        }
        int count = repository.bulkUpsertValues(request.values());
        return ResponseEntity.ok(Map.of("upserted", count));
    }

    /** Live coverage count -- {active, candidate, deprecated, explanatory_only}. */
    @GetMapping("/factor-definitions/status-counts")
    public ResponseEntity<Map<String, Integer>> statusCounts() {
        return ResponseEntity.ok(repository.statusCounts());
    }

    /**
     * Feature 11: the single "how much of the catalogue is actually live"
     * number (today's equivalent: 12/80, 15%) -- grows automatically as
     * Features 3/5/6/7 (and anything registered afterward) promote more
     * factors to {@code active}, instead of being visible only by reading
     * the script's docstring. {@link #statusCounts()} above has the full
     * breakdown; this is the single-metric summary of it.
     */
    @GetMapping("/factor-coverage")
    public ResponseEntity<Map<String, Object>> factorCoverage() {
        Map<String, Integer> counts = repository.statusCounts();
        int activeCount = counts.getOrDefault("active", 0);
        int totalCount = counts.values().stream().mapToInt(Integer::intValue).sum();
        double pct = totalCount == 0 ? 0.0 : Math.round(1000.0 * activeCount / totalCount) / 10.0;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("activeCount", activeCount);
        resp.put("totalCount", totalCount);
        resp.put("pct", pct);
        return ResponseEntity.ok(resp);
    }
}
