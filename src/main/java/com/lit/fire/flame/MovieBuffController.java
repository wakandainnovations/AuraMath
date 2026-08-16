package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keyword-scoped movie buffs.
 *
 * Returns the authors classified as "Movie Buff" (positive tone, high
 * branching ratio — see {@code MarketingUserReportController.audienceType}) who
 * have also posted about {@code keyword}, PLUS authors who never made it into the
 * global {@code author_categories} table at all (too few total posts to clear
 * {@link AuthorCategoryRepository#MIN_POSTS} and run the Hawkes audit) but whose
 * posts about this keyword specifically drew real engagement and skewed positive —
 * a one-off poster whose single post about the movie went viral shouldn't be invisible
 * just because they don't post often enough elsewhere to be globally profiled. The work
 * of intersecting/merging both groups by per-keyword post activity is done in
 * {@link EntityMarketingService#movieBuffs(String)}, which also filters out rapid-fire,
 * near-zero-reach "Power Burst Poster" accounts whose branching ratio only looks high
 * because they posted dozens of times in a matter of seconds — not because anyone
 * actually saw or engaged with the posts. Those are returned separately under
 * {@code suspectedBots} rather than silently dropped.
 *
 * An empty {@code movieBuffs} list is legitimate: it means nobody with real, positive
 * engagement has posted about this keyword yet (e.g. the keyword is new).
 */
@RestController
@RequestMapping("/api/marketing")
public class MovieBuffController {

    @Autowired
    private EntityMarketingService marketing;

    @GetMapping("/movie-buffs/{keyword}")
    public ResponseEntity<Map<String, Object>> getMovieBuffs(@PathVariable String keyword) {
        EntityMarketingService.MovieBuffsResult result = marketing.movieBuffs(keyword);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keyword",            keyword);
        resp.put("totalMovieBuffs",    result.movieBuffs.size());
        resp.put("movieBuffs",         result.movieBuffs);
        resp.put("suspectedBotsExcluded", result.suspectedBots.size());
        resp.put("suspectedBots",      result.suspectedBots);
        return ResponseEntity.ok(resp);
    }
}
