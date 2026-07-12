package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/marketing/narrative-novelty")
public class NarrativeNoveltyController {

    @Autowired
    private NarrativeNoveltyService narrativeNoveltyService;

    public record ScoreRequest(String movieName, String genre, String synopsis) {}

    /** Score any movie by synopsis, including upcoming/unreleased titles not yet in the DB. */
    @PostMapping("/score")
    public ResponseEntity<?> score(@RequestBody ScoreRequest request) {
        if (request == null || request.synopsis() == null || request.synopsis().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "synopsis is required"));
        }
        String name = (request.movieName() == null || request.movieName().isBlank()) ? "Untitled" : request.movieName();
        var result = narrativeNoveltyService.computeNovelty(name, request.genre(), request.synopsis());
        return ResponseEntity.ok(result);
    }

    /** Score a title already in movies_data_collection, using its stored genre/synopsis. */
    @GetMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestParam String movieName) {
        var result = narrativeNoveltyService.computeNoveltyForExisting(movieName);
        if (result == null) {
            return ResponseEntity.status(404).body(Map.of("message", "No synopsis found for this movie_name"));
        }
        return ResponseEntity.ok(result);
    }
}
