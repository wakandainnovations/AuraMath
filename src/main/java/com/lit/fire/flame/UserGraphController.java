package com.lit.fire.flame;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filterable read API over the already-populated {@code graph_nodes}/{@code graph_edges}
 * tables (see {@link GraphPopulationService}), returned in a {@code {nodes, edges}} shape
 * consumable directly by graph-viz frontends (D3/Cytoscape/vis.js).
 *
 * Unlike {@link LanguageMarketingAPI} (Features 4/5), this controller does not re-derive
 * the mentions -> managed_entities join: that join is already baked into graph_nodes/
 * graph_edges by GraphPopulationService's precompute step, so filtering here is a direct
 * read against the graph tables' own attributes/edges rather than a third copy of the
 * join SQL.
 */
@RestController
@RequestMapping("/api/graph")
public class UserGraphController {

    @Autowired private JdbcTemplate jdbc;
    private final Gson gson = new Gson();

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> usersGraph(
            @RequestParam String language,
            @RequestParam(required = false) String movie) {

        List<Map<String, Object>> movieRows = queryMovieNodes(language, movie);

        if (movieRows.isEmpty()) {
            // With no movie filter, this query was already language-only, so emptiness
            // means the language itself has zero MOVIE nodes. With a movie filter, an
            // empty combined result is ambiguous, so re-check the language alone before
            // deciding between 404 (no such language) and 200 + empty (no such movie).
            boolean languageHasMatches = movie != null && languageHasAnyMovie(language);
            if (!languageHasMatches) {
                Map<String, Object> notFound = new LinkedHashMap<>();
                notFound.put("language", language);
                notFound.put("message", "No MOVIE nodes found for this language");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound);
            }
        }

        List<Long> movieIds = movieRows.stream().map(UserGraphController::rowId).collect(Collectors.toList());

        List<Map<String, Object>> postedAboutEdges = movieIds.isEmpty()
                ? List.of()
                : queryEdges("POSTED_ABOUT", null, movieIds);

        Set<Long> userIds = new LinkedHashSet<>();
        for (Map<String, Object> edge : postedAboutEdges) {
            userIds.add(((Number) edge.get("from_node_id")).longValue());
        }

        List<Map<String, Object>> userRows = userIds.isEmpty()
                ? List.of()
                : queryNodesByIds(new ArrayList<>(userIds));

        // RETWEETED edges among the filtered audience only; not expensive since it's
        // scoped to the (typically small) set of USER node ids already resolved above.
        List<Map<String, Object>> retweetedEdges = userIds.size() < 2
                ? List.of()
                : queryEdges("RETWEETED", new ArrayList<>(userIds), new ArrayList<>(userIds));

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> row : movieRows) nodes.add(toNodeJson(row));
        for (Map<String, Object> row : userRows) nodes.add(toNodeJson(row));

        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> row : postedAboutEdges) edges.add(toEdgeJson(row));
        for (Map<String, Object> row : retweetedEdges) edges.add(toEdgeJson(row));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalUsers", userRows.size());
        summary.put("totalMovies", movieRows.size());
        summary.put("totalEdges", edges.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodes", nodes);
        body.put("edges", edges);
        body.put("summary", summary);
        return ResponseEntity.ok(body);
    }

    private List<Map<String, Object>> queryMovieNodes(String language, String movie) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, type, attributes FROM graph_nodes WHERE type = 'MOVIE' AND attributes->>'language' ILIKE ?");
        List<Object> params = new ArrayList<>();
        params.add(language);
        if (movie != null) {
            sql.append(" AND attributes->>'name' ILIKE ?");
            params.add(movie);
        }
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    private boolean languageHasAnyMovie(String language) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM graph_nodes WHERE type = 'MOVIE' AND attributes->>'language' ILIKE ?)",
                Boolean.class, language);
        return Boolean.TRUE.equals(exists);
    }

    private List<Map<String, Object>> queryNodesByIds(List<Long> ids) {
        String sql = "SELECT id, type, attributes FROM graph_nodes WHERE id IN (" + placeholders(ids.size()) + ")";
        return jdbc.queryForList(sql, ids.toArray());
    }

    /**
     * {@code fromIds}/{@code toIds} of {@code null} means "unconstrained" on that side.
     * POSTED_ABOUT lookups only constrain the movie ('to') side, since the user ('from')
     * side isn't known until these edges are read; RETWEETED lookups constrain both
     * sides to the already-resolved user id set.
     */
    private List<Map<String, Object>> queryEdges(String relationType, List<Long> fromIds, List<Long> toIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, from_node_id, to_node_id, relation_type, weight, timestamp " +
                "FROM graph_edges WHERE relation_type = ?");
        List<Object> params = new ArrayList<>();
        params.add(relationType);
        if (fromIds != null) {
            sql.append(" AND from_node_id IN (").append(placeholders(fromIds.size())).append(")");
            params.addAll(fromIds);
        }
        if (toIds != null) {
            sql.append(" AND to_node_id IN (").append(placeholders(toIds.size())).append(")");
            params.addAll(toIds);
        }
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private Map<String, Object> toNodeJson(Map<String, Object> row) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", row.get("id"));
        node.put("type", row.get("type"));
        node.put("attributes", JsonbUtil.asTree(row.get("attributes"), gson));
        return node;
    }

    private Map<String, Object> toEdgeJson(Map<String, Object> row) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", row.get("id"));
        edge.put("from", row.get("from_node_id"));
        edge.put("to", row.get("to_node_id"));
        edge.put("relationType", row.get("relation_type"));
        edge.put("weight", row.get("weight"));
        Object ts = row.get("timestamp");
        edge.put("timestamp", ts instanceof Timestamp t ? t.toLocalDateTime().toString() : null);
        return edge;
    }

    private static Long rowId(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }
}
