package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Populates the pre-existing (but previously unwritten) {@code graph_nodes}/{@code graph_edges}
 * tables from mentions/engagement data, following the same precompute-and-persist pattern as
 * {@link UserEngagementRatingService}.
 *
 * <p>Node identity: {@code graph_nodes} has no unique constraint on any {@code attributes} field,
 * so MOVIE nodes are upserted keyed on {@code attributes->>'managed_entity_id'} and USER nodes on
 * {@code attributes->>'global_user_id'} via a bulk lookup query ({@link #existingNodeIdsByKey})
 * run once per node type, rather than one existence-check query per row. This keeps node ids
 * stable across repeated runs.
 *
 * <p>Edge idempotency: {@code graph_edges} has no natural unique key either. Rather than upsert on
 * {@code (from_node_id, to_node_id, relation_type)}, each run deletes every existing POSTED_ABOUT/
 * RETWEETED edge and rebuilds them from scratch (mirroring {@link RetweetResolver}'s
 * reset-then-recompute idempotency style). This is simpler and guarantees stale edges (e.g. from a
 * mention that no longer exists) never linger; the tradeoff is that {@code graph_edges.id} values
 * are not stable across runs, which is fine since nothing else references them. There is no
 * per-owner scoping (no other precompute service in this codebase partitions its recompute by
 * tenant either) — each run rebuilds the whole graph.
 *
 * <p>USER node {@code owner_id} is intentionally left NULL: a social-media user isn't owned by one
 * tenant the way a MOVIE {@code managed_entities} row is, and the same author can be relevant to
 * multiple entity owners. Revisit if a tenant-scoping requirement shows up for users later.
 *
 * <p>RETWEETED edges need a per-(retweeter, retweeted) pairing, but {@link RetweetResolver}'s own
 * exposed aggregate ({@code retweetCountsByNormalizedAuthor()}) is collapsed across all retweeting
 * authors into a single count per retweeted handle, so it can't supply the edge's "from" side. This
 * class instead re-derives the pairwise breakdown directly against {@code x_posts} using the same
 * {@code RT @(\w+):} regex and normalization RetweetResolver uses, grouped by (author, handle).
 *
 * <p>{@code recomputeAndPersist()} is {@code @Transactional} (same fix {@link
 * CrossPlatformIdentityResolver} already applies) so the whole run shares one physical JDBC
 * connection: {@link DataSourceConfig}'s {@code DriverManagerDataSource} is unpooled, and with
 * tens of thousands of USER nodes to upsert individually, one new TCP connection per statement
 * exhausts local ephemeral ports well before a run completes.
 */
@Service
public class GraphPopulationService {

    private static final Logger log = LoggerFactory.getLogger(GraphPopulationService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Gson gson = new Gson();

    // Mirrors RetweetResolver's RETWEET_HANDLE_COUNTS_SQL detection regex/normalization, but keeps
    // the retweeting author alongside the retweeted handle instead of collapsing across authors.
    private static final String RETWEET_PAIR_COUNTS_SQL =
            "SELECT author AS retweeting_author, normalized_handle AS retweeted_handle, count(*) AS retweet_count " +
            "FROM (" +
            "  SELECT author, " +
            "         regexp_replace(lower((regexp_match(text, '^RT @(\\w+):'))[1]), '[^a-zA-Z0-9]', '', 'g') AS normalized_handle " +
            "  FROM x_posts WHERE text ~ '^RT @\\w+:' AND author IS NOT NULL AND author <> ''" +
            ") t WHERE normalized_handle <> '' " +
            "GROUP BY author, normalized_handle";

    /** Rebuilds MOVIE/USER graph_nodes and POSTED_ABOUT/RETWEETED graph_edges from current data. */
    @Transactional
    public synchronized Map<String, Object> recomputeAndPersist() {
        long start = System.currentTimeMillis();

        Map<String, String> identities = loadIdentityIndex();

        Map<Long, Long> movieNodeIdByEntityId = populateMovieNodes();
        Map<String, Long> userNodeIdByGlobalUserId = populateUserNodes(identities);

        int postedAboutEdges = populatePostedAboutEdges(identities, movieNodeIdByEntityId, userNodeIdByGlobalUserId);
        int retweetedEdges = populateRetweetedEdges(identities, userNodeIdByGlobalUserId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("movieNodes", movieNodeIdByEntityId.size());
        summary.put("userNodes", userNodeIdByGlobalUserId.size());
        summary.put("postedAboutEdges", postedAboutEdges);
        summary.put("retweetedEdges", retweetedEdges);
        summary.put("elapsedMs", System.currentTimeMillis() - start);
        log.info("Graph population complete: {}", summary);
        return summary;
    }

    // -------------------------------------------------------------------------
    // MOVIE nodes
    // -------------------------------------------------------------------------

    private Map<Long, Long> populateMovieNodes() {
        List<Map<String, Object>> movies = jdbcTemplate.queryForList(
                "SELECT id, name, language, genre, release_date, owner_id FROM managed_entities WHERE type = 'MOVIE'");

        Map<String, Long> existingByEntityId = existingNodeIdsByKey("MOVIE", "managed_entity_id");

        Map<Long, Long> nodeIdByEntityId = new LinkedHashMap<>();
        for (Map<String, Object> row : movies) {
            long entityId = ((Number) row.get("id")).longValue();

            JsonObject attrs = new JsonObject();
            attrs.addProperty("managed_entity_id", entityId);
            attrs.addProperty("name", (String) row.get("name"));
            attrs.addProperty("language", (String) row.get("language"));
            attrs.addProperty("genre", (String) row.get("genre"));
            Object releaseDate = row.get("release_date");
            attrs.addProperty("release_date", releaseDate == null ? null : releaseDate.toString());
            Number ownerIdNum = (Number) row.get("owner_id");
            Long ownerId = ownerIdNum == null ? null : ownerIdNum.longValue();

            Long existingNodeId = existingByEntityId.get(String.valueOf(entityId));
            long nodeId = existingNodeId != null
                    ? updateNode(existingNodeId, gson.toJson(attrs), ownerId)
                    : insertNode(gson.toJson(attrs), "MOVIE", ownerId);
            nodeIdByEntityId.put(entityId, nodeId);
        }
        return nodeIdByEntityId;
    }

    // -------------------------------------------------------------------------
    // USER nodes — only authors who mentioned a MOVIE entity (Feature 4's join), not every
    // author in the raw platform tables.
    // -------------------------------------------------------------------------

    private Map<String, Long> populateUserNodes(Map<String, String> identities) {
        Set<String> globalUserIds = new LinkedHashSet<>();
        jdbcTemplate.query(
                "SELECT DISTINCT m.author AS author " +
                "FROM mentions m " +
                "JOIN mention_entities me_j ON me_j.mention_id = m.id " +
                "JOIN managed_entities me ON me.id = me_j.managed_entity_id " +
                "WHERE me.type = 'MOVIE' AND m.author IS NOT NULL AND m.author <> ''",
                rs -> {
                    String globalUserId = identities.get(normalize(rs.getString("author")));
                    if (globalUserId != null) {
                        globalUserIds.add(globalUserId);
                    }
                });

        Map<String, Map<String, Object>> enrichment = fetchEngagementEnrichment(globalUserIds);
        Map<String, Long> existingByGlobalUserId = existingNodeIdsByKey("USER", "global_user_id");

        Map<String, Long> nodeIdByGlobalUserId = new LinkedHashMap<>();
        for (String globalUserId : globalUserIds) {
            Map<String, Object> enrich = enrichment.getOrDefault(globalUserId, Map.of());

            JsonObject attrs = new JsonObject();
            attrs.addProperty("global_user_id", globalUserId);
            Object ratingObj = enrich.get("engagement_rating");
            attrs.addProperty("engagement_rating", ratingObj instanceof Number n ? n.doubleValue() : null);
            attrs.addProperty("tribe_label", (String) enrich.get("tribe_label"));

            // owner_id intentionally NULL — see class javadoc.
            Long existingNodeId = existingByGlobalUserId.get(globalUserId);
            long nodeId = existingNodeId != null
                    ? updateNode(existingNodeId, gson.toJson(attrs), null)
                    : insertNode(gson.toJson(attrs), "USER", null);
            nodeIdByGlobalUserId.put(globalUserId, nodeId);
        }
        return nodeIdByGlobalUserId;
    }

    private Map<String, Map<String, Object>> fetchEngagementEnrichment(Set<String> globalUserIds) {
        if (globalUserIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", globalUserIds.stream().map(x -> "?").toList());
        String sql = "SELECT global_user_id, engagement_rating, tribe_label " +
                     "FROM marketing_target_profiles WHERE global_user_id IN (" + placeholders + ")";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, globalUserIds.toArray());
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("global_user_id"), row);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // POSTED_ABOUT edges — weight is the summed EngagementScoreCalculator score across just
    // that user's posts about that movie (mentions.platform + mentions.post_id join to the
    // matching raw platform table row, confirmed live: mentions.post_id equals the platform
    // table's own id, keyed by mentions.platform).
    // -------------------------------------------------------------------------

    private int populatePostedAboutEdges(Map<String, String> identities,
                                          Map<Long, Long> movieNodeIdByEntityId,
                                          Map<String, Long> userNodeIdByGlobalUserId) {
        jdbcTemplate.update("DELETE FROM graph_edges WHERE relation_type = 'POSTED_ABOUT'");

        Map<String, Double> xScores = loadXPostScores();
        Map<String, Double> ytScores = loadYoutubeCommentScores();
        Map<String, Double> redditScores = loadRedditPostScores();
        Map<String, Double> igScores = loadInstagramPostScores();

        // Aggregated per (globalUserId, entityId) pair, keyed by a composite string since Java
        // has no built-in pair type handy here.
        Map<String, Double> weightByPair = new LinkedHashMap<>();
        Map<String, Timestamp> latestPostDateByPair = new LinkedHashMap<>();
        Map<String, String> userIdByPair = new LinkedHashMap<>();
        Map<String, Long> entityIdByPair = new LinkedHashMap<>();

        jdbcTemplate.query(
                "SELECT m.author AS author, me.id AS entity_id, m.platform AS platform, " +
                "       m.post_id AS post_id, m.post_date AS post_date " +
                "FROM mentions m " +
                "JOIN mention_entities me_j ON me_j.mention_id = m.id " +
                "JOIN managed_entities me ON me.id = me_j.managed_entity_id " +
                "WHERE me.type = 'MOVIE' AND m.author IS NOT NULL AND m.author <> ''",
                rs -> {
                    String globalUserId = identities.get(normalize(rs.getString("author")));
                    if (globalUserId == null || !userNodeIdByGlobalUserId.containsKey(globalUserId)) {
                        return;
                    }
                    long entityId = rs.getLong("entity_id");
                    if (!movieNodeIdByEntityId.containsKey(entityId)) {
                        return;
                    }

                    double score = scoreForMention(rs.getString("platform"), rs.getString("post_id"),
                            xScores, ytScores, redditScores, igScores);
                    Timestamp postDate = rs.getTimestamp("post_date");

                    String key = globalUserId + ' ' + entityId;
                    weightByPair.merge(key, score, Double::sum);
                    latestPostDateByPair.merge(key, postDate, (a, b) -> a.after(b) ? a : b);
                    userIdByPair.putIfAbsent(key, globalUserId);
                    entityIdByPair.putIfAbsent(key, entityId);
                });

        int inserted = 0;
        for (String key : weightByPair.keySet()) {
            long fromNodeId = userNodeIdByGlobalUserId.get(userIdByPair.get(key));
            long toNodeId = movieNodeIdByEntityId.get(entityIdByPair.get(key));
            insertEdge(fromNodeId, toNodeId, "POSTED_ABOUT", weightByPair.get(key), latestPostDateByPair.get(key));
            inserted++;
        }
        return inserted;
    }

    private static double scoreForMention(String platform, String postId,
                                           Map<String, Double> xScores, Map<String, Double> ytScores,
                                           Map<String, Double> redditScores, Map<String, Double> igScores) {
        Map<String, Double> scores = switch (platform) {
            case "X" -> xScores;
            case "YOUTUBE" -> ytScores;
            case "REDDIT" -> redditScores;
            case "INSTAGRAM" -> igScores;
            default -> null;
        };
        // Unrecognized platform, or a mention whose post_id has no matching raw-table row (e.g.
        // the source post was later purged), contributes 0 rather than dropping the pair.
        return scores == null ? 0.0 : scores.getOrDefault(postId, 0.0);
    }

    private Map<String, Double> loadXPostScores() {
        Map<String, Double> scores = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, comment_count, shares_count, likes_count, views_count FROM x_posts",
                rs -> {
                    scores.put(rs.getString("id"), EngagementScoreCalculator.scoreXPost(
                            rs.getObject("comment_count", Integer.class),
                            rs.getObject("shares_count", Integer.class),
                            rs.getObject("likes_count", Integer.class),
                            rs.getObject("views_count", Integer.class)));
                });
        return scores;
    }

    private Map<String, Double> loadYoutubeCommentScores() {
        Map<String, Double> scores = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, reply_count, likes_count FROM youtube_comments",
                rs -> {
                    scores.put(rs.getString("id"), EngagementScoreCalculator.scoreYoutubeComment(
                            rs.getObject("reply_count", Integer.class),
                            rs.getObject("likes_count", Integer.class)));
                });
        return scores;
    }

    private Map<String, Double> loadRedditPostScores() {
        Map<String, Double> scores = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, num_comments, score FROM reddit_posts",
                rs -> {
                    scores.put(rs.getString("id"), EngagementScoreCalculator.scoreRedditPost(
                            rs.getObject("num_comments", Integer.class),
                            rs.getObject("score", Integer.class)));
                });
        return scores;
    }

    private Map<String, Double> loadInstagramPostScores() {
        Map<String, Double> scores = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, comments_count, like_count FROM instagram_posts",
                rs -> {
                    scores.put(rs.getString("id"), EngagementScoreCalculator.scoreInstagramPost(
                            rs.getObject("comments_count", Integer.class),
                            rs.getObject("like_count", Integer.class)));
                });
        return scores;
    }

    // -------------------------------------------------------------------------
    // RETWEETED edges
    // -------------------------------------------------------------------------

    private int populateRetweetedEdges(Map<String, String> identities, Map<String, Long> userNodeIdByGlobalUserId) {
        jdbcTemplate.update("DELETE FROM graph_edges WHERE relation_type = 'RETWEETED'");

        List<Map<String, Object>> pairs = jdbcTemplate.queryForList(RETWEET_PAIR_COUNTS_SQL);

        Timestamp now = Timestamp.from(Instant.now());
        int inserted = 0;
        for (Map<String, Object> row : pairs) {
            String retweetingUserId = identities.get(normalize((String) row.get("retweeting_author")));
            // retweeted_handle is already normalized by the SQL itself (same lower+strip
            // non-alphanumeric sequence as normalize()), so it's looked up directly.
            String retweetedUserId = identities.get((String) row.get("retweeted_handle"));
            if (retweetingUserId == null || retweetedUserId == null) {
                continue;
            }
            Long fromNodeId = userNodeIdByGlobalUserId.get(retweetingUserId);
            Long toNodeId = userNodeIdByGlobalUserId.get(retweetedUserId);
            if (fromNodeId == null || toNodeId == null) {
                // One side never mentioned a MOVIE entity, so it has no USER node from step 2.
                continue;
            }
            long retweetCount = ((Number) row.get("retweet_count")).longValue();
            insertEdge(fromNodeId, toNodeId, "RETWEETED", (double) retweetCount, now);
            inserted++;
        }
        return inserted;
    }

    // -------------------------------------------------------------------------
    // Shared node/edge helpers
    // -------------------------------------------------------------------------

    /** Bulk existence lookup used to decide insert-vs-update per row without a query per node. */
    private Map<String, Long> existingNodeIdsByKey(String type, String attributeKey) {
        Map<String, Long> result = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, attributes->>'" + attributeKey + "' AS key FROM graph_nodes WHERE type = ?",
                rs -> {
                    result.put(rs.getString("key"), rs.getLong("id"));
                },
                type);
        return result;
    }

    private long insertNode(String attributesJson, String type, Long ownerId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO graph_nodes (attributes, type, owner_id) VALUES (?::jsonb, ?, ?) RETURNING id",
                Long.class, attributesJson, type, ownerId);
    }

    private long updateNode(long nodeId, String attributesJson, Long ownerId) {
        jdbcTemplate.update("UPDATE graph_nodes SET attributes = ?::jsonb, owner_id = ? WHERE id = ?",
                attributesJson, ownerId, nodeId);
        return nodeId;
    }

    private void insertEdge(long fromNodeId, long toNodeId, String relationType, double weight, Timestamp timestamp) {
        jdbcTemplate.update(
                "INSERT INTO graph_edges (from_node_id, to_node_id, relation_type, weight, timestamp) " +
                "VALUES (?, ?, ?, ?, ?)",
                fromNodeId, toNodeId, relationType, weight, timestamp);
    }

    private Map<String, String> loadIdentityIndex() {
        Map<String, String> index = new HashMap<>();
        jdbcTemplate.query("SELECT normalized_author, global_user_id FROM user_identity_link", rs -> {
            index.put(rs.getString("normalized_author"), rs.getString("global_user_id"));
        });
        return index;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
    }
}
