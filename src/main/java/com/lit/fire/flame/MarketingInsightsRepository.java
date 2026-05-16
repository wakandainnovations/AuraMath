package com.lit.fire.flame;

import com.google.gson.Gson;
import com.lit.fire.flame.models.UserPersonaProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;

@Repository
public class MarketingInsightsRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Gson gson = new Gson();

    public static final String TABLE_MARKETING_TARGET_PROFILES = "marketing_target_profiles";
    public static final String COLUMN_GLOBAL_USER_ID = "global_user_id";
    public static final String COLUMN_PLATFORM_HANDLES = "platform_handles"; // JSON string
    public static final String COLUMN_TRIBE_LABEL = "tribe_label";
    public static final String COLUMN_INFLUENCE_RANK = "influence_rank";
    public static final String COLUMN_TOP_GENRES = "top_genres"; // JSON string
    public static final String COLUMN_PEAK_ACTIVITY_TIMES = "peak_activity_times"; // JSON string
    public static final String COLUMN_MOI_SCORE = "moi_score";

    @PostConstruct
    public void init() {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_MARKETING_TARGET_PROFILES + " (" +
                COLUMN_GLOBAL_USER_ID + " TEXT PRIMARY KEY," +
                COLUMN_PLATFORM_HANDLES + " TEXT," +
                COLUMN_TRIBE_LABEL + " TEXT," +
                COLUMN_INFLUENCE_RANK + " REAL," +
                COLUMN_TOP_GENRES + " TEXT," +
                COLUMN_PEAK_ACTIVITY_TIMES + " TEXT," +
                COLUMN_MOI_SCORE + " REAL)";
        jdbcTemplate.execute(sql);
    }

    /**
     * Upserts (Updates or Inserts) a UserPersonaProfile into the marketing_target_profiles table.
     *
     * @param profile The UserPersonaProfile to upsert.
     * @param platformHandles A JSON string representing platform handles.
     * @param peakActivityTimes A JSON string representing peak activity times.
     * @param moiScore The user's MOI score.
     */
    public void upsertUserPersonaProfile(UserPersonaProfile profile, String platformHandles, String peakActivityTimes, double moiScore) {
        String topGenresJson = gson.toJson(profile.getAverageAspectSentiments());

        String sql = "INSERT INTO " + TABLE_MARKETING_TARGET_PROFILES + " (" +
                COLUMN_GLOBAL_USER_ID + ", " +
                COLUMN_PLATFORM_HANDLES + ", " +
                COLUMN_TRIBE_LABEL + ", " +
                COLUMN_INFLUENCE_RANK + ", " +
                COLUMN_TOP_GENRES + ", " +
                COLUMN_PEAK_ACTIVITY_TIMES + ", " +
                COLUMN_MOI_SCORE +
                ") VALUES (?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?) " +
                "ON CONFLICT (" + COLUMN_GLOBAL_USER_ID + ") DO UPDATE SET " +
                COLUMN_PLATFORM_HANDLES + " = EXCLUDED." + COLUMN_PLATFORM_HANDLES + ", " +
                COLUMN_TRIBE_LABEL + " = EXCLUDED." + COLUMN_TRIBE_LABEL + ", " +
                COLUMN_INFLUENCE_RANK + " = EXCLUDED." + COLUMN_INFLUENCE_RANK + ", " +
                COLUMN_TOP_GENRES + " = EXCLUDED." + COLUMN_TOP_GENRES + ", " +
                COLUMN_PEAK_ACTIVITY_TIMES + " = EXCLUDED." + COLUMN_PEAK_ACTIVITY_TIMES + ", " +
                COLUMN_MOI_SCORE + " = EXCLUDED." + COLUMN_MOI_SCORE;

        jdbcTemplate.update(sql,
                profile.getUserId(),
                platformHandles,
                profile.getTribe(),
                profile.getInfectivityScore(),
                topGenresJson,
                peakActivityTimes,
                moiScore);
    }
}
