package com.lit.fire.flame;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 9: on-demand "what would this movie make" prediction for a single
 * upcoming/unreleased title -- the product-facing counterpart to Feature 8's
 * batch model comparison, which only ever backtests already-released movies.
 *
 * Feature 10 adds the read side on top: existing-prediction lookup by name
 * (upcoming or backtested, same table, distinguished by {@code isUpcoming}),
 * the factor-impact-scores listing, and the accuracy report -- "let me see
 * how accurate this is" made directly queryable instead of buried in a JSON
 * file under {@code output/}.
 */
@RestController
@RequestMapping("/api/marketing/movie-revenue-prediction")
public class MovieRevenuePredictionController {

    @Autowired
    private MovieRevenuePredictionService predictionService;

    @Autowired
    private MovieRevenuePredictionRepository repository;

    private final Gson gson = new Gson();

    /**
     * Accepts the same attribute payload {@code predict_movie.py --from-json}
     * does (movie_name, release_date, language, budget required; genre,
     * country, director, cast, trailer/teaser timing, etc. optional) and
     * shells out to it synchronously, returning predicted_revenue,
     * confidence_band_low/high, disclosure_likelihood, and the top-5 SHAP
     * drivers explaining the prediction.
     */
    @PostMapping("/predict")
    public ResponseEntity<?> predict(@RequestBody Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "request body is required"));
        }
        for (String required : new String[] {"movie_name", "release_date", "language", "budget"}) {
            if (!attrs.containsKey(required) || attrs.get(required) == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing required field: " + required));
            }
        }
        try {
            Map<String, Object> result = predictionService.predict(attrs);
            return ResponseEntity.ok(result);
        } catch (MovieRevenuePredictionService.PredictionException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Whatever row(s) match this title in {@code movie_revenue_predictions} --
     * an upcoming prediction, a backtested historical one, or (across
     * multiple release years/language editions of the same name) several of
     * either. {@code factor_keys_used} is decoded from jsonb into a JSON tree
     * for the response.
     */
    @GetMapping("/{movieName}")
    public ResponseEntity<?> getByMovieName(@PathVariable String movieName) {
        List<Map<String, Object>> rows = repository.findByMovieName(movieName);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                "message", "No movie_revenue_predictions row for movie_name=" + movieName));
        }
        List<Map<String, Object>> decoded = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            decoded.add(withDecodedFactorKeys(row));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", decoded.size());
        resp.put("predictions", decoded);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/factor-impact-scores")
    public ResponseEntity<Map<String, Object>> factorImpactScores() {
        List<Map<String, Object>> rows = repository.listFactorImpactScores();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", rows.size());
        resp.put("factors", rows);
        return ResponseEntity.ok(resp);
    }

    /**
     * The direct answer to "let me see how accurate this is": the latest
     * training run's summary metrics (Feature 11's {@code model_comparison_history},
     * within_20/30/50pct + median_abs_pct_error + n_movies + run_at) alongside a
     * paginated list of backtested rows, each showing predicted_revenue next to
     * actual_revenue and abs_pct_error side by side.
     */
    @GetMapping("/accuracy")
    public ResponseEntity<Map<String, Object>> accuracy(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 200) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "page must be >= 0 and size must be between 1 and 200"));
        }
        Map<String, Object> latestRun = repository.latestModelComparisonHistory();
        List<Map<String, Object>> pageRows = repository.pagedBacktestedPredictions(page, size);
        List<Map<String, Object>> decodedRows = new ArrayList<>(pageRows.size());
        for (Map<String, Object> row : pageRows) {
            decodedRows.add(withDecodedFactorKeys(row));
        }
        long totalElements = repository.countBacktestedPredictions();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("latestRun", latestRun == null ? null : withDecodedFactorKeys(latestRun));
        Map<String, Object> moviesPage = new LinkedHashMap<>();
        moviesPage.put("content", decodedRows);
        moviesPage.put("page", page);
        moviesPage.put("size", size);
        moviesPage.put("totalElements", totalElements);
        resp.put("movies", moviesPage);
        return ResponseEntity.ok(resp);
    }

    /** jdbcTemplate.queryForList() surfaces jsonb columns as PGobject -- decode
     * factor_keys_used into a real JSON array for the response (see JsonbUtil). */
    private Map<String, Object> withDecodedFactorKeys(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        if (out.containsKey("factor_keys_used")) {
            out.put("factor_keys_used", JsonbUtil.asTree(out.get("factor_keys_used"), gson));
        }
        return out;
    }
}
