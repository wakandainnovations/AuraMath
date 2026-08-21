package com.lit.fire.flame;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Language-scoped marketing endpoints backed by {@code marketing_target_profiles}.
 *
 * Author -> global_user_id resolution uses the same normalize() (lowercase, strip
 * non-alphanumerics) + user_identity_link lookup as {@link UserEngagementRatingService}/
 * {@link GenreLookalikeService}; mentions whose author has no matching identity are
 * skipped, same as there.
 */
@RestController
@RequestMapping("/api/marketing/language")
public class LanguageMarketingAPI {

    @Autowired private JdbcTemplate jdbc;
    private final Gson gson = new Gson();

    // -------------------------------------------------------------------------
    // GET /api/marketing/language/{language}/users
    //
    // Every distinct user with a mention linked to a MOVIE managed_entity whose
    // language matches {language} (case-insensitive). mention_count/distinct_movies_
    // mentioned are computed from DISTINCT (mention_id, entity_id) pairs so a mention
    // linked to multiple same-language movie entities doesn't inflate the counts.
    // engagement_rating/tribe_label/platform_handles are enriched from
    // marketing_target_profiles via LEFT JOIN semantics (unresolved enrichment ->
    // null fields, user still included).
    // -------------------------------------------------------------------------
    @GetMapping("/{language}/users")
    public ResponseEntity<Map<String, Object>> potentialViewers(@PathVariable String language) {
        String sql =
            "SELECT DISTINCT m.id AS mention_id, m.author AS author, me.id AS entity_id " +
            "FROM mentions m " +
            "JOIN mention_entities me_j ON me_j.mention_id = m.id " +
            "JOIN managed_entities me ON me.id = me_j.managed_entity_id " +
            "WHERE me.type = 'MOVIE' AND me.language ILIKE ? " +
            "  AND m.author IS NOT NULL AND m.author <> ''";

        Map<String, String> identities = loadIdentityIndex();

        Map<String, Set<Object>> mentionsByUser = new HashMap<>();
        Map<String, Set<Object>> moviesByUser = new HashMap<>();

        jdbc.query(sql, rs -> {
            String globalUserId = identities.get(normalize(rs.getString("author")));
            if (globalUserId == null) {
                return;
            }
            mentionsByUser.computeIfAbsent(globalUserId, k -> new HashSet<>()).add(rs.getObject("mention_id"));
            moviesByUser.computeIfAbsent(globalUserId, k -> new HashSet<>()).add(rs.getObject("entity_id"));
        }, language);

        Map<String, Map<String, Object>> enrichByUser = fetchEnrichment(new ArrayList<>(mentionsByUser.keySet()));
        Map<String, Map<String, Object>> causalLiftByUser = fetchCausalLiftScores(new ArrayList<>(mentionsByUser.keySet()));

        List<Map<String, Object>> users = new ArrayList<>();
        for (Map.Entry<String, Set<Object>> entry : mentionsByUser.entrySet()) {
            String globalUserId = entry.getKey();
            Map<String, Object> enrich = enrichByUser.getOrDefault(globalUserId, Map.of());
            Map<String, Object> causalLift = causalLiftByUser.getOrDefault(globalUserId, Map.of());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("global_user_id",            globalUserId);
            row.put("mention_count",              entry.getValue().size());
            row.put("distinct_movies_mentioned",  moviesByUser.getOrDefault(globalUserId, Set.of()).size());
            row.put("engagement_rating",          enrich.get("engagement_rating"));
            row.put("tribe_label",                enrich.get("tribe_label"));
            row.put("platform_handles",           JsonbUtil.asTree(enrich.get("platform_handles"), gson));
            row.put("causal_lift_score",          causalLift.get("causal_lift_score"));
            row.put("n_qualifying_events",        causalLift.get("n_qualifying_events"));
            row.put("confidence",                 causalLift.get("confidence"));
            users.add(row);
        }

        users.sort(Comparator.comparing(
                (Map<String, Object> row) -> toNullableDouble(row.get("engagement_rating")),
                Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language",    language);
        body.put("totalUsers",  users.size());
        body.put("users",       users);
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // GET /api/marketing/language/{language}/movie/{movieName}/users
    //
    // Same join as potentialViewers() above, additionally filtered to managed_entities
    // whose name matches {movieName} (case-insensitive). mention_count/distinct_movies_
    // mentioned are scoped to that filtered set, same DISTINCT (mention_id, entity_id)
    // dedup as Feature 4. mentions_on_this_movie/average_sentiment_score are computed
    // separately over mentions.sentiment_score restricted to the 1-100 bounds check
    // used elsewhere (see GenreMarketingAPI), so an out-of-range/null score doesn't
    // skew the average but the mention still counts toward mention_count.
    // A movie name that doesn't resolve to any managed_entities row for {language}
    // yields an empty result set, not an error.
    // -------------------------------------------------------------------------
    @GetMapping("/{language}/movie/{movieName}/users")
    public ResponseEntity<Map<String, Object>> potentialViewersForMovie(
            @PathVariable String language, @PathVariable String movieName) {
        String sql =
            "SELECT DISTINCT m.id AS mention_id, m.author AS author, me.id AS entity_id " +
            "FROM mentions m " +
            "JOIN mention_entities me_j ON me_j.mention_id = m.id " +
            "JOIN managed_entities me ON me.id = me_j.managed_entity_id " +
            "WHERE me.type = 'MOVIE' AND me.language ILIKE ? AND me.name ILIKE ? " +
            "  AND m.author IS NOT NULL AND m.author <> ''";

        String sentimentSql =
            "SELECT DISTINCT m.id AS mention_id, m.author AS author, m.sentiment_score AS sentiment_score " +
            "FROM mentions m " +
            "JOIN mention_entities me_j ON me_j.mention_id = m.id " +
            "JOIN managed_entities me ON me.id = me_j.managed_entity_id " +
            "WHERE me.type = 'MOVIE' AND me.language ILIKE ? AND me.name ILIKE ? " +
            "  AND m.author IS NOT NULL AND m.author <> '' " +
            "  AND m.sentiment_score BETWEEN 1 AND 100";

        Map<String, String> identities = loadIdentityIndex();

        Map<String, Set<Object>> mentionsByUser = new HashMap<>();
        Map<String, Set<Object>> moviesByUser = new HashMap<>();

        jdbc.query(sql, rs -> {
            String globalUserId = identities.get(normalize(rs.getString("author")));
            if (globalUserId == null) {
                return;
            }
            mentionsByUser.computeIfAbsent(globalUserId, k -> new HashSet<>()).add(rs.getObject("mention_id"));
            moviesByUser.computeIfAbsent(globalUserId, k -> new HashSet<>()).add(rs.getObject("entity_id"));
        }, language, movieName);

        Map<String, Map<Object, Integer>> sentimentByUser = new HashMap<>();
        jdbc.query(sentimentSql, rs -> {
            String globalUserId = identities.get(normalize(rs.getString("author")));
            if (globalUserId == null) {
                return;
            }
            sentimentByUser
                    .computeIfAbsent(globalUserId, k -> new LinkedHashMap<>())
                    .put(rs.getObject("mention_id"), rs.getInt("sentiment_score"));
        }, language, movieName);

        Map<String, Map<String, Object>> enrichByUser = fetchEnrichment(new ArrayList<>(mentionsByUser.keySet()));
        Map<String, Map<String, Object>> causalLiftByUser = fetchCausalLiftScores(new ArrayList<>(mentionsByUser.keySet()));

        List<Map<String, Object>> users = new ArrayList<>();
        for (Map.Entry<String, Set<Object>> entry : mentionsByUser.entrySet()) {
            String globalUserId = entry.getKey();
            Map<String, Object> enrich = enrichByUser.getOrDefault(globalUserId, Map.of());
            Map<String, Object> causalLift = causalLiftByUser.getOrDefault(globalUserId, Map.of());
            Map<Object, Integer> sentimentMentions = sentimentByUser.getOrDefault(globalUserId, Map.of());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("global_user_id",            globalUserId);
            row.put("mention_count",              entry.getValue().size());
            row.put("distinct_movies_mentioned",  moviesByUser.getOrDefault(globalUserId, Set.of()).size());
            row.put("engagement_rating",          enrich.get("engagement_rating"));
            row.put("tribe_label",                enrich.get("tribe_label"));
            row.put("platform_handles",           JsonbUtil.asTree(enrich.get("platform_handles"), gson));
            row.put("causal_lift_score",          causalLift.get("causal_lift_score"));
            row.put("n_qualifying_events",        causalLift.get("n_qualifying_events"));
            row.put("confidence",                 causalLift.get("confidence"));
            row.put("mentions_on_this_movie",     sentimentMentions.size());
            row.put("average_sentiment_score",    averageOf(sentimentMentions.values()));
            users.add(row);
        }

        users.sort(Comparator.comparing(
                (Map<String, Object> row) -> toNullableDouble(row.get("engagement_rating")),
                Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language",    language);
        body.put("movie",       movieName);
        body.put("totalUsers",  users.size());
        body.put("users",       users);
        return ResponseEntity.ok(body);
    }

    private static Double averageOf(Collection<Integer> scores) {
        if (scores.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (Integer s : scores) {
            sum += s;
        }
        return Math.round((sum / scores.size()) * 10.0) / 10.0;
    }

    private Map<String, String> loadIdentityIndex() {
        Map<String, String> index = new HashMap<>();
        jdbc.query("SELECT normalized_author, global_user_id FROM user_identity_link", rs -> {
            index.put(rs.getString("normalized_author"), rs.getString("global_user_id"));
        });
        return index;
    }

    /** LEFT JOIN semantics: a user with no user_causal_lift_scores row yet is simply absent from the map. */
    private Map<String, Map<String, Object>> fetchCausalLiftScores(List<String> globalUserIds) {
        if (globalUserIds.isEmpty()) return Map.of();
        String placeholders = globalUserIds.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT global_user_id, causal_lift_score, n_qualifying_events, confidence " +
                     "FROM user_causal_lift_scores WHERE global_user_id IN (" + placeholders + ")";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, globalUserIds.toArray());
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("global_user_id"), row);
        }
        return result;
    }

    private Map<String, Map<String, Object>> fetchEnrichment(List<String> globalUserIds) {
        if (globalUserIds.isEmpty()) return Map.of();
        String placeholders = globalUserIds.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT global_user_id, engagement_rating, tribe_label, platform_handles " +
                     "FROM marketing_target_profiles WHERE global_user_id IN (" + placeholders + ")";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, globalUserIds.toArray());
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("global_user_id"), row);
        }
        return result;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
    }

    private static Double toNullableDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
