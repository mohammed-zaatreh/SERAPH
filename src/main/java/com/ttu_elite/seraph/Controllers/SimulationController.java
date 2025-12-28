package com.ttu_elite.seraph.Controllers;

import com.ttu_elite.seraph.Services.RedditAnalyzeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test") // Separate namespace
@RequiredArgsConstructor
public class SimulationController {

    private final RedditAnalyzeService service;

    @PostMapping("/simulate")
    public ResponseEntity<?> runSimulation(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.getOrDefault("username", "test_subject");
        List<String> posts = (List<String>) payload.get("posts");

        if (posts == null || posts.isEmpty()) {
            return ResponseEntity.badRequest().body("No posts provided");
        }

        String result = service.simulateAnalysis(username, posts);

        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(result);
    }
}