package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyword-scoped brand evangelists.
 *
 * Returns the authors classified as "Brand Evangelist" (positive tone, high
 * branching ratio — see {@code MarketingUserReportController.audienceType}) who
 * have also posted about {@code keyword}. The classification lives in the global
 * {@code author_categories} table and is not keyword-aware, so the work of
 * intersecting it with per-keyword post activity is done in
 * {@link EntityMarketingService#brandEvangelists(String)}.
 *
 * An empty {@code evangelists} list is legitimate: it means no categorised
 * evangelist has posted about this keyword (e.g. the keyword is new, or
 * {@code /api/marketing/users/sync} has not run to populate author_categories).
 */
@RestController
@RequestMapping("/api/marketing")
public class BrandEvangelistController {

    @Autowired
    private EntityMarketingService marketing;

    @GetMapping("/brand-evangelists/{keyword}")
    public ResponseEntity<Map<String, Object>> getBrandEvangelists(@PathVariable String keyword) {
        List<Map<String, Object>> evangelists = marketing.brandEvangelists(keyword);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keyword",          keyword);
        resp.put("totalEvangelists", evangelists.size());
        resp.put("evangelists",      evangelists);
        return ResponseEntity.ok(resp);
    }
}
