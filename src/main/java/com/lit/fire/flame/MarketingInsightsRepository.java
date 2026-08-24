package com.lit.fire.flame;

import com.google.gson.Gson;
import com.lit.fire.flame.models.UserPersonaProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.List;

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
    public static final String COLUMN_TOP_GENRES = "top_genres"; // JSON string — noun/aspect terms from posts
    public static final String COLUMN_TOP_MOVIE_GENRES = "top_movie_genres"; // JSON string — movie-genre weights from GenreClassifier
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
                COLUMN_TOP_MOVIE_GENRES + " jsonb," +
                COLUMN_PEAK_ACTIVITY_TIMES + " TEXT," +
                COLUMN_MOI_SCORE + " REAL)";
        jdbcTemplate.execute(sql);
        // Migrate existing deployments where top_movie_genres was created as TEXT.
        jdbcTemplate.execute("ALTER TABLE " + TABLE_MARKETING_TARGET_PROFILES +
                " ALTER COLUMN " + COLUMN_TOP_MOVIE_GENRES + " TYPE jsonb USING " +
                COLUMN_TOP_MOVIE_GENRES + "::jsonb");
    }

    /**
     * Upserts (Updates or Inserts) a UserPersonaProfile into the marketing_target_profiles table.
     *
     * @param profile The UserPersonaProfile to upsert.
     * @param platformHandles A JSON string representing platform handles.
     * @param peakActivityTimes A JSON string representing peak activity times.
     * @param topMovieGenresJson JSON string of movie-genre weights from GenreClassifier (may be null/empty).
     * @param moiScore The user's MOI score.
     */
    public void upsertUserPersonaProfile(UserPersonaProfile profile,
                                         String platformHandles,
                                         String peakActivityTimes,
                                         String topMovieGenresJson,
                                         double moiScore) {
        String topGenresJson = gson.toJson(profile.getAverageAspectSentiments());

        String sql = "INSERT INTO " + TABLE_MARKETING_TARGET_PROFILES + " (" +
                COLUMN_GLOBAL_USER_ID + ", " +
                COLUMN_PLATFORM_HANDLES + ", " +
                COLUMN_TRIBE_LABEL + ", " +
                COLUMN_INFLUENCE_RANK + ", " +
                COLUMN_TOP_GENRES + ", " +
                COLUMN_TOP_MOVIE_GENRES + ", " +
                COLUMN_PEAK_ACTIVITY_TIMES + ", " +
                COLUMN_MOI_SCORE +
                ") VALUES (?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?) " +
                "ON CONFLICT (" + COLUMN_GLOBAL_USER_ID + ") DO UPDATE SET " +
                COLUMN_PLATFORM_HANDLES + " = EXCLUDED." + COLUMN_PLATFORM_HANDLES + ", " +
                COLUMN_TRIBE_LABEL + " = EXCLUDED." + COLUMN_TRIBE_LABEL + ", " +
                COLUMN_INFLUENCE_RANK + " = EXCLUDED." + COLUMN_INFLUENCE_RANK + ", " +
                COLUMN_TOP_GENRES + " = EXCLUDED." + COLUMN_TOP_GENRES + ", " +
                COLUMN_TOP_MOVIE_GENRES + " = EXCLUDED." + COLUMN_TOP_MOVIE_GENRES + ", " +
                COLUMN_PEAK_ACTIVITY_TIMES + " = EXCLUDED." + COLUMN_PEAK_ACTIVITY_TIMES + ", " +
                COLUMN_MOI_SCORE + " = EXCLUDED." + COLUMN_MOI_SCORE;

        jdbcTemplate.update(sql,
                profile.getUserId(),
                platformHandles,
                profile.getTribe(),
                profile.getInfectivityScore(),
                topGenresJson,
                topMovieGenresJson,
                peakActivityTimes,
                moiScore);
    }

    /**
     * Upserts only {@code platform_handles} for a batch of authors, leaving every other column
     * (tribe_label, influence_rank, moi_score, ...) untouched on conflict and NULL on a fresh
     * insert. Used by {@link PlatformHandlesRefreshService}, which — unlike the full
     * {@link MarketingEnrichmentEngine} run — only needs to keep profile-URL/handle attribution
     * current and has no tribe/genre/influence data to write.
     */
    public void batchUpsertPlatformHandles(List<Object[]> globalUserIdAndHandles) {
        String sql = "INSERT INTO " + TABLE_MARKETING_TARGET_PROFILES + " (" +
                COLUMN_GLOBAL_USER_ID + ", " + COLUMN_PLATFORM_HANDLES + ") " +
                "VALUES (?, ?::jsonb) " +
                "ON CONFLICT (" + COLUMN_GLOBAL_USER_ID + ") DO UPDATE SET " +
                COLUMN_PLATFORM_HANDLES + " = EXCLUDED." + COLUMN_PLATFORM_HANDLES;
        jdbcTemplate.batchUpdate(sql, globalUserIdAndHandles);
    }
}
