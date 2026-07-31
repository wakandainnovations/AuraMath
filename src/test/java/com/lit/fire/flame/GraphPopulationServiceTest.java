package com.lit.fire.flame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Like {@link RetweetResolverTest}, this drives {@link GraphPopulationService} against the real
 * local 'aura' DB (the mentions->mention_entities->managed_entities join and jsonb attribute
 * matching aren't mockable via a fake JdbcTemplate). recomputeAndPersist() processes the whole
 * live dataset, not just this test's rows, so assertions are scoped to this fixture's own
 * managed_entities/mentions/users (id/name prefix {@code gptest-}) rather than asserting total
 * graph-wide counts, mirroring RetweetResolverTest's narrow-assertion approach.
 */
@SpringBootTest
public class GraphPopulationServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GraphPopulationService graphPopulationService;

    private static final String MOVIE_NAME = "gptest-Movie One";
    private static final String AUTHOR_A = "GraphTestAuthorA";
    private static final String AUTHOR_B = "GraphTestAuthorB";
    private static final String AUTHOR_UNKNOWN = "GraphTestUnknownAuthor";
    private static final String USER_A = "gptest-user-a";
    private static final String USER_B = "gptest-user-b";

    private long movieEntityId;

    private void deleteTestRows() {
        jdbcTemplate.update("DELETE FROM mention_entities WHERE mention_id IN " +
                "(SELECT id FROM mentions WHERE post_id LIKE 'gptest-%')");
        jdbcTemplate.update("DELETE FROM mentions WHERE post_id LIKE 'gptest-%'");
        jdbcTemplate.update("DELETE FROM managed_entities WHERE name LIKE 'gptest-%'");
        jdbcTemplate.update("DELETE FROM x_posts WHERE id LIKE 'gptest-%'");
        jdbcTemplate.update("DELETE FROM user_identity_link WHERE global_user_id LIKE 'gptest-%'");
        jdbcTemplate.update("DELETE FROM marketing_target_profiles WHERE global_user_id LIKE 'gptest-%'");
        jdbcTemplate.update("DELETE FROM graph_edges WHERE from_node_id IN (SELECT id FROM graph_nodes WHERE " +
                "attributes->>'global_user_id' LIKE 'gptest-%' OR attributes->>'name' LIKE 'gptest-%') " +
                "OR to_node_id IN (SELECT id FROM graph_nodes WHERE " +
                "attributes->>'global_user_id' LIKE 'gptest-%' OR attributes->>'name' LIKE 'gptest-%')");
        jdbcTemplate.update("DELETE FROM graph_nodes WHERE " +
                "attributes->>'global_user_id' LIKE 'gptest-%' OR attributes->>'name' LIKE 'gptest-%'");
    }

    @BeforeEach
    public void setUp() {
        deleteTestRows(); // in case a prior run crashed before cleanup

        movieEntityId = jdbcTemplate.queryForObject(
                "INSERT INTO managed_entities (name, type, language, genre, release_date) " +
                "VALUES (?, 'MOVIE', 'Tamil', 'Action', ?) RETURNING id",
                Long.class, MOVIE_NAME, Date.valueOf("2024-01-01"));

        insertMention("gptest-post-a", AUTHOR_A, Timestamp.valueOf("2024-06-01 10:00:00"));
        insertMention("gptest-post-b", AUTHOR_B, Timestamp.valueOf("2024-06-02 10:00:00"));

        // A's post about the movie: score = 3*2 + 2*0 + 1.5*3 + 1*10 = 20.5
        insertXPost("gptest-post-a", AUTHOR_A, Timestamp.valueOf("2024-06-01 09:00:00"), 2, 3, 10);
        // B's post about the movie: score = 3*1 + 2*0 + 1.5*2 + 1*5 = 11.0
        insertXPost("gptest-post-b", AUTHOR_B, Timestamp.valueOf("2024-06-02 09:00:00"), 1, 2, 5);
        // B retweets A -> RETWEETED edge from userB to userA, weight 1.
        insertRetweetXPost("gptest-post-rt", AUTHOR_A, AUTHOR_B, Timestamp.valueOf("2024-06-03 09:00:00"));
        // An unresolved author (no identity link) also retweets A -> must be skipped entirely.
        insertRetweetXPost("gptest-post-rt-unknown", AUTHOR_A, AUTHOR_UNKNOWN, Timestamp.valueOf("2024-06-03 10:00:00"));

        insertIdentity(normalize(AUTHOR_A), USER_A);
        insertIdentity(normalize(AUTHOR_B), USER_B);

        // Only A has a marketing_target_profiles row, so B's USER node should end up with null
        // engagement_rating/tribe_label (LEFT JOIN semantics, not dropped).
        jdbcTemplate.update(
                "INSERT INTO marketing_target_profiles (global_user_id, engagement_rating, tribe_label) VALUES (?, ?, ?)",
                USER_A, 42.0, "Explorers");
    }

    @AfterEach
    public void tearDown() {
        deleteTestRows();
    }

    private void insertMention(String postId, String author, Timestamp postDate) {
        long mentionId = jdbcTemplate.queryForObject(
                "INSERT INTO mentions (managed_entity_id, platform, post_id, author, post_date, sentiment) " +
                "VALUES (?, 'X', ?, ?, ?, 'positive') RETURNING id",
                Long.class, movieEntityId, postId, author, postDate);
        jdbcTemplate.update(
                "INSERT INTO mention_entities (mention_id, managed_entity_id) VALUES (?, ?)",
                mentionId, movieEntityId);
    }

    private void insertXPost(String id, String author, Timestamp createdAt, Integer commentCount, Integer likesCountUnused, Integer viewsCount) {
        // Signature kept close to RetweetResolverTest's insertPost helper; commentCount/likesCount/
        // viewsCount map onto x_posts' comment_count/likes_count/views_count columns.
        jdbcTemplate.update(
                "INSERT INTO x_posts (id, author, created_at, comment_count, likes_count, views_count, shares_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0)",
                id, author, createdAt, commentCount, likesCountUnused, viewsCount);
    }

    private void insertRetweetXPost(String id, String retweetedHandle, String author, Timestamp createdAt) {
        jdbcTemplate.update(
                "INSERT INTO x_posts (id, text, author, created_at) VALUES (?, ?, ?, ?)",
                id, "RT @" + retweetedHandle + ": something", author, createdAt);
    }

    private void insertIdentity(String normalizedAuthor, String globalUserId) {
        jdbcTemplate.update(
                "INSERT INTO user_identity_link (global_user_id, normalized_author) VALUES (?, ?)",
                globalUserId, normalizedAuthor);
    }

    private Long movieNodeId() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM graph_nodes WHERE type = 'MOVIE' AND attributes->>'managed_entity_id' = ?",
                Long.class, String.valueOf(movieEntityId));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long userNodeId(String globalUserId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM graph_nodes WHERE type = 'USER' AND attributes->>'global_user_id' = ?",
                Long.class, globalUserId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Double edgeWeight(long fromNodeId, long toNodeId, String relationType) {
        List<Double> weights = jdbcTemplate.queryForList(
                "SELECT weight FROM graph_edges WHERE from_node_id = ? AND to_node_id = ? AND relation_type = ?",
                Double.class, fromNodeId, toNodeId, relationType);
        return weights.isEmpty() ? null : weights.get(0);
    }

    private static String normalize(String raw) {
        return raw.toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
    }

    @Test
    public void populatesNodesAndEdgesFromFixture() {
        Map<String, Object> summary = graphPopulationService.recomputeAndPersist();
        assertNotNull(summary.get("movieNodes"));

        Long movieNodeId = movieNodeId();
        assertNotNull(movieNodeId, "MOVIE node should have been created for the fixture entity");

        Map<String, Object> movieAttrs = jdbcTemplate.queryForMap(
                "SELECT attributes->>'name' AS name, attributes->>'language' AS language, " +
                "attributes->>'genre' AS genre, owner_id " +
                "FROM graph_nodes WHERE id = ?", movieNodeId);
        assertEquals(MOVIE_NAME, movieAttrs.get("name"));
        assertEquals("Tamil", movieAttrs.get("language"));
        assertEquals("Action", movieAttrs.get("genre"));
        assertNull(movieAttrs.get("owner_id"), "fixture managed_entities row has no owner_id");

        Long userANodeId = userNodeId(USER_A);
        Long userBNodeId = userNodeId(USER_B);
        assertNotNull(userANodeId, "USER node should exist for an author who mentioned a MOVIE");
        assertNotNull(userBNodeId, "USER node should exist for an author who mentioned a MOVIE");

        Map<String, Object> userAAttrs = jdbcTemplate.queryForMap(
                "SELECT attributes->>'engagement_rating' AS rating, attributes->>'tribe_label' AS tribe " +
                "FROM graph_nodes WHERE id = ?", userANodeId);
        assertEquals(42.0, Double.parseDouble((String) userAAttrs.get("rating")), 0.001);
        assertEquals("Explorers", userAAttrs.get("tribe"));

        Map<String, Object> userBAttrs = jdbcTemplate.queryForMap(
                "SELECT attributes->>'engagement_rating' AS rating, attributes->>'tribe_label' AS tribe " +
                "FROM graph_nodes WHERE id = ?", userBNodeId);
        assertNull(userBAttrs.get("rating"), "B has no marketing_target_profiles row");
        assertNull(userBAttrs.get("tribe"), "B has no marketing_target_profiles row");

        assertEquals(20.5, edgeWeight(userANodeId, movieNodeId, "POSTED_ABOUT"), 0.001);
        assertEquals(11.0, edgeWeight(userBNodeId, movieNodeId, "POSTED_ABOUT"), 0.001);

        // B retweeted A -> edge B -> A, weight 1. The unresolved-author retweet of A must not
        // produce a second edge.
        assertEquals(1.0, edgeWeight(userBNodeId, userANodeId, "RETWEETED"), 0.001);
        Integer retweetedEdgesIntoA = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM graph_edges WHERE to_node_id = ? AND relation_type = 'RETWEETED'",
                Integer.class, userANodeId);
        assertEquals(1, retweetedEdgesIntoA, "only B's retweet resolves to a USER node; the unknown author's does not");
    }

    @Test
    public void recomputeIsIdempotent() {
        graphPopulationService.recomputeAndPersist();
        Long movieNodeIdFirst = movieNodeId();
        Long userANodeIdFirst = userNodeId(USER_A);

        graphPopulationService.recomputeAndPersist();
        Long movieNodeIdSecond = movieNodeId();
        Long userANodeIdSecond = userNodeId(USER_A);

        assertEquals(movieNodeIdFirst, movieNodeIdSecond, "re-running should update the same MOVIE node, not duplicate it");
        assertEquals(userANodeIdFirst, userANodeIdSecond, "re-running should update the same USER node, not duplicate it");

        Integer postedAboutCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM graph_edges WHERE from_node_id = ? AND to_node_id = ? AND relation_type = 'POSTED_ABOUT'",
                Integer.class, userANodeIdSecond, movieNodeIdSecond);
        assertEquals(1, postedAboutCount, "re-running should not duplicate the POSTED_ABOUT edge");
    }
}
