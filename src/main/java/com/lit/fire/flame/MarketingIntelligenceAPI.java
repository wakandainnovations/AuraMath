package com.lit.fire.flame;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1")
public class MarketingIntelligenceAPI {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Gson gson = new Gson();

    private static final String[] JSONB_COLUMNS = {"platform_handles", "top_genres", "top_movie_genres", "peak_activity_times"};

    // post_count is metadata in peak_activity_times, not a time bucket — exclude when picking the peak.
    private static final Set<String> NON_TIME_BUCKET_KEYS = Set.of("post_count");

    @GetMapping("/targets")
    public List<Map<String, Object>> getTargets(
            @RequestParam(required = false) String classifier,
            @RequestParam(required = false) String movieGenre,
            @RequestParam(required = false, defaultValue = "0.0") Double minInfluenceScore,
            @RequestParam(required = false) String platform) {

        StringBuilder sql = new StringBuilder("SELECT * FROM marketing_target_profiles WHERE influence_rank >= ?");
        List<Object> params = new ArrayList<>();
        params.add(minInfluenceScore);

        // `classifier` matches the broad aspect/noun vocabulary stored in top_genres
        // (hashtags, names, generic keywords). For real movie genres use movieGenre.
        if (classifier != null && !classifier.isEmpty()) {
            sql.append(" AND top_genres::text ILIKE ?");
            params.add("%\"" + classifier + "\"%");
        }

        if (movieGenre != null && !movieGenre.isEmpty()) {
            sql.append(" AND top_movie_genres::text ILIKE ?");
            params.add("%\"" + movieGenre + "\"%");
        }

        if (platform != null && !platform.isEmpty()) {
            sql.append(" AND platform_handles::text ILIKE ?");
            params.add("%\"" + platform + "\"%");
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            Map<String, Object> userTarget = new HashMap<>(row);
            for (String col : JSONB_COLUMNS) {
                if (userTarget.containsKey(col)) {
                    userTarget.put(col, JsonbUtil.asTree(row.get(col), gson));
                }
            }

            String peakBucket = pickPeakBucket(userTarget.get("peak_activity_times"));
            String targetPlatform = platform != null && !platform.isEmpty()
                    ? platform
                    : primaryPlatform(userTarget.get("platform_handles"));
            String tip = String.format("Target on %s during %s with visual-heavy ads", targetPlatform, peakBucket);

            userTarget.put("Marketing Tip", tip);
            result.add(userTarget);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static String pickPeakBucket(Object peakActivityTimes) {
        if (!(peakActivityTimes instanceof Map<?, ?> map) || map.isEmpty()) {
            return "peak hours";
        }
        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
            if (NON_TIME_BUCKET_KEYS.contains(e.getKey()) || !(e.getValue() instanceof Number n)) {
                continue;
            }
            double score = n.doubleValue();
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return (best == null || bestScore <= 0.0) ? "peak hours" : best;
    }

    @SuppressWarnings("unchecked")
    private static String primaryPlatform(Object platformHandles) {
        if (platformHandles instanceof Map<?, ?> map) {
            Object primary = ((Map<String, Object>) map).get("primary_platform");
            if (primary instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return "their preferred platform";
    }
}
