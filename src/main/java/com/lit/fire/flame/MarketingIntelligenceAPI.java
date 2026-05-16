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

@RestController
@RequestMapping("/v1")
public class MarketingIntelligenceAPI {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Gson gson = new Gson();

    private static final String[] JSONB_COLUMNS = {"platform_handles", "top_genres", "peak_activity_times"};

    @GetMapping("/targets")
    public List<Map<String, Object>> getTargets(
            @RequestParam(required = false) String genre,
            @RequestParam(required = false, defaultValue = "0.0") Double minInfluenceScore,
            @RequestParam(required = false) String platform) {

        StringBuilder sql = new StringBuilder("SELECT * FROM marketing_target_profiles WHERE influence_rank >= ?");
        List<Object> params = new ArrayList<>();
        params.add(minInfluenceScore);

        if (genre != null && !genre.isEmpty()) {
            sql.append(" AND top_genres LIKE ?");
            params.add("%" + genre + "%");
        }

        if (platform != null && !platform.isEmpty()) {
            sql.append(" AND platform_handles LIKE ?");
            params.add("%" + platform + "%");
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

            String peakTimes = JsonbUtil.asJsonString(row.get("peak_activity_times"));
            if (peakTimes == null || peakTimes.isEmpty() || peakTimes.equals("{}") || peakTimes.equals("[]")) {
                peakTimes = "peak hours";
            }
            
            // Note: The prompt asks to use `media_type` and `peak_activity_times`. 
            // In the provided table marketing_target_profiles, there is no media_type column.
            // But I'll assume we can use platform or a generic tip based on the instructions.
            String targetPlatform = platform != null ? platform : "their preferred platform";
            String tip = String.format("Target on %s during %s with visual-heavy ads", targetPlatform, peakTimes);
            
            userTarget.put("Marketing Tip", tip);
            result.add(userTarget);
        }

        return result;
    }
}
