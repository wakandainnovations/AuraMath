package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Like {@link GraphPopulationServiceTest}, this drives {@link UserGraphController} against the
 * real local 'aura' DB (jsonb attribute matching via ILIKE isn't mockable via a fake
 * JdbcTemplate). Fixture rows are inserted directly into graph_nodes/graph_edges — this
 * controller only reads those tables, so there's no need to go through mentions/
 * managed_entities/GraphPopulationService to set up a fixture. The language value used
 * ("ugctest-Tamil") is prefixed so it can't collide with real ILIKE-matched data.
 */
@SpringBootTest
public class UserGraphControllerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserGraphController userGraphController;

    private final Gson gson = new Gson();

    private static final String LANGUAGE = "ugctest-Tamil";
    private static final String OTHER_LANGUAGE = "ugctest-Telugu";
    private static final String MOVIE_NAME = "ugctest-Movie One";
    private static final String OTHER_MOVIE_NAME = "ugctest-Movie Two";

    private long movieNodeId;
    private long otherLanguageMovieNodeId;
    private long userANodeId;
    private long userBNodeId;
    private long unreachableUserNodeId;

    private void deleteTestRows() {
        jdbcTemplate.update("DELETE FROM graph_edges WHERE from_node_id IN " +
                "(SELECT id FROM graph_nodes WHERE attributes->>'global_user_id' LIKE 'ugctest-%' " +
                "OR attributes->>'name' LIKE 'ugctest-%') " +
                "OR to_node_id IN (SELECT id FROM graph_nodes WHERE attributes->>'global_user_id' LIKE 'ugctest-%' " +
                "OR attributes->>'name' LIKE 'ugctest-%')");
        jdbcTemplate.update("DELETE FROM graph_nodes WHERE attributes->>'global_user_id' LIKE 'ugctest-%' " +
                "OR attributes->>'name' LIKE 'ugctest-%'");
    }

    @BeforeEach
    public void setUp() {
        deleteTestRows(); // in case a prior run crashed before cleanup

        movieNodeId = insertMovieNode(MOVIE_NAME, LANGUAGE, "Action");
        otherLanguageMovieNodeId = insertMovieNode(OTHER_MOVIE_NAME, OTHER_LANGUAGE, "Drama");

        userANodeId = insertUserNode("ugctest-user-a", 42.0, "Explorers");
        userBNodeId = insertUserNode("ugctest-user-b", 7.0, "Casuals");
        unreachableUserNodeId = insertUserNode("ugctest-user-c", null, null);

        insertEdge(userANodeId, movieNodeId, "POSTED_ABOUT", 20.5, Timestamp.valueOf("2024-06-01 09:00:00"));
        insertEdge(userBNodeId, movieNodeId, "POSTED_ABOUT", 11.0, Timestamp.valueOf("2024-06-02 09:00:00"));
        // Retweet among the reachable users -> should be included as an amplification edge.
        insertEdge(userBNodeId, userANodeId, "RETWEETED", 1.0, Timestamp.valueOf("2024-06-03 09:00:00"));
        // Retweet involving a user not reachable via POSTED_ABOUT -> must not appear in results.
        insertEdge(unreachableUserNodeId, userANodeId, "RETWEETED", 3.0, Timestamp.valueOf("2024-06-03 10:00:00"));
    }

    @AfterEach
    public void tearDown() {
        deleteTestRows();
    }

    private long insertMovieNode(String name, String language, String genre) {
        JsonObject attrs = new JsonObject();
        attrs.addProperty("name", name);
        attrs.addProperty("language", language);
        attrs.addProperty("genre", genre);
        return jdbcTemplate.queryForObject(
                "INSERT INTO graph_nodes (attributes, type, owner_id) VALUES (?::jsonb, 'MOVIE', NULL) RETURNING id",
                Long.class, gson.toJson(attrs));
    }

    private long insertUserNode(String globalUserId, Double engagementRating, String tribeLabel) {
        JsonObject attrs = new JsonObject();
        attrs.addProperty("global_user_id", globalUserId);
        attrs.addProperty("engagement_rating", engagementRating);
        attrs.addProperty("tribe_label", tribeLabel);
        return jdbcTemplate.queryForObject(
                "INSERT INTO graph_nodes (attributes, type, owner_id) VALUES (?::jsonb, 'USER', NULL) RETURNING id",
                Long.class, gson.toJson(attrs));
    }

    private void insertEdge(long fromNodeId, long toNodeId, String relationType, double weight, Timestamp timestamp) {
        jdbcTemplate.update(
                "INSERT INTO graph_edges (from_node_id, to_node_id, relation_type, weight, timestamp) " +
                "VALUES (?, ?, ?, ?, ?)",
                fromNodeId, toNodeId, relationType, weight, timestamp);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodesOf(ResponseEntity<Map<String, Object>> resp) {
        return (List<Map<String, Object>>) resp.getBody().get("nodes");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> edgesOf(ResponseEntity<Map<String, Object>> resp) {
        return (List<Map<String, Object>>) resp.getBody().get("edges");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(ResponseEntity<Map<String, Object>> resp) {
        return (Map<String, Object>) resp.getBody().get("summary");
    }

    private boolean hasNodeId(List<Map<String, Object>> nodes, long id) {
        return nodes.stream().anyMatch(n -> ((Number) n.get("id")).longValue() == id);
    }

    @Test
    public void languageOnly_returnsMoviesUsersAndBothEdgeTypes() {
        ResponseEntity<Map<String, Object>> resp = userGraphController.usersGraph(LANGUAGE, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        List<Map<String, Object>> nodes = nodesOf(resp);
        assertTrue(hasNodeId(nodes, movieNodeId), "matching-language MOVIE node should be present");
        assertTrue(hasNodeId(nodes, userANodeId), "user who posted about the movie should be present");
        assertTrue(hasNodeId(nodes, userBNodeId), "user who posted about the movie should be present");
        assertTrue(nodes.stream().noneMatch(n -> ((Number) n.get("id")).longValue() == otherLanguageMovieNodeId),
                "other-language MOVIE node must not appear");
        assertTrue(nodes.stream().noneMatch(n -> ((Number) n.get("id")).longValue() == unreachableUserNodeId),
                "a user with no POSTED_ABOUT edge to a matched movie must not appear");

        List<Map<String, Object>> edges = edgesOf(resp);
        assertEquals(3, edges.size(), "2 POSTED_ABOUT + 1 RETWEETED among the reachable users");
        assertTrue(edges.stream().anyMatch(e -> "POSTED_ABOUT".equals(e.get("relationType"))
                && ((Number) e.get("from")).longValue() == userANodeId
                && ((Number) e.get("to")).longValue() == movieNodeId
                && ((Number) e.get("weight")).doubleValue() == 20.5));
        assertTrue(edges.stream().anyMatch(e -> "RETWEETED".equals(e.get("relationType"))
                && ((Number) e.get("from")).longValue() == userBNodeId
                && ((Number) e.get("to")).longValue() == userANodeId));
        assertTrue(edges.stream().noneMatch(e -> ((Number) e.get("from")).longValue() == unreachableUserNodeId),
                "a RETWEETED edge touching an unreachable user must be excluded");

        Map<String, Object> summary = summaryOf(resp);
        assertEquals(2, summary.get("totalUsers"));
        assertEquals(1, summary.get("totalMovies"));
        assertEquals(3, summary.get("totalEdges"));
    }

    @Test
    public void languageAndMovie_scopesToThatMovie() {
        ResponseEntity<Map<String, Object>> resp = userGraphController.usersGraph(LANGUAGE, MOVIE_NAME);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> summary = summaryOf(resp);
        assertEquals(1, summary.get("totalMovies"));
        assertEquals(2, summary.get("totalUsers"));
    }

    @Test
    public void languageExistsButMovieDoesNot_returnsEmptyNotFound() {
        ResponseEntity<Map<String, Object>> resp = userGraphController.usersGraph(LANGUAGE, "ugctest-No Such Movie");
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "an unmatched movie name is an empty result, not a 404");

        assertTrue(nodesOf(resp).isEmpty());
        assertTrue(edgesOf(resp).isEmpty());
        Map<String, Object> summary = summaryOf(resp);
        assertEquals(0, summary.get("totalMovies"));
        assertEquals(0, summary.get("totalUsers"));
        assertEquals(0, summary.get("totalEdges"));
    }

    @Test
    public void unknownLanguage_returns404() {
        ResponseEntity<Map<String, Object>> resp = userGraphController.usersGraph("ugctest-NoSuchLanguage", null);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody().get("message"));
    }

    @Test
    public void caseInsensitiveLanguageMatch() {
        ResponseEntity<Map<String, Object>> resp = userGraphController.usersGraph(LANGUAGE.toUpperCase(), null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, summaryOf(resp).get("totalMovies"));
    }
}
