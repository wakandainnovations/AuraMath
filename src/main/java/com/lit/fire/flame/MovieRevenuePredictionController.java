package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Feature 9: on-demand "what would this movie make" prediction for a single
 * upcoming/unreleased title -- the product-facing counterpart to Feature 8's
 * batch model comparison, which only ever backtests already-released movies.
 * Feature 10 will add the GET endpoints (existing-prediction lookup,
 * factor-impact-scores, the accuracy report) and the weekly re-scoring
 * scheduler to this same controller/package; this is deliberately scoped to
 * just the /predict path Feature 9 needs.
 */
@RestController
@RequestMapping("/api/marketing/movie-revenue-prediction")
public class MovieRevenuePredictionController {

    @Autowired
    private MovieRevenuePredictionService predictionService;

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
}
