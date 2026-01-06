package com.ttu_elite.seraph.Controllers;

import com.ttu_elite.seraph.Entities.ProfileAnalysis;
import com.ttu_elite.seraph.Entities.RedditPost;
import com.ttu_elite.seraph.Repositories.ProfileAnalysisRepository;
import com.ttu_elite.seraph.Repositories.RedditPostRepository;
import com.ttu_elite.seraph.Services.RedditAnalyzeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/SERAPH")
@RequiredArgsConstructor
public class RedditAnalyzeController {

    private final RedditAnalyzeService service;
    private final ProfileAnalysisRepository repository;
    private final RedditPostRepository postRepo;

    // --- ANALYZE (The Eye) ---
    @CrossOrigin(origins = "*")
    @PostMapping("/reddit")
    public ResponseEntity<?> analyzeUser(
            @RequestBody Map<String, String> payload,
            @RequestParam(required = false) boolean force
    ) {
        String url = payload.get("profileUrl");
        if (url == null) return ResponseEntity.badRequest().body("Missing profileUrl");

        // 1. Force Cleanup Logic
        if (force) {
            String username = url.contains("/user/") ? url.split("/user/")[1].split("/")[0] : url;
            if (repository.existsByUsername(username)) {
                // Requires custom method in Repository
                repository.deleteByUsername(username);

                List<RedditPost> oldPosts = postRepo.findAllByUsernameOrderByCreatedUtcDesc(username);
                postRepo.deleteAll(oldPosts);
            }
        }

        try {
            // Service returns JSON String
            String jsonResult = service.analyzeProfile(url);

            if (jsonResult.contains("\"error\":")) {
                return ResponseEntity.badRequest().body(jsonResult);
            }

            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(jsonResult);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    // --- CHRONICLES (The Memory) ---

    // 1. THE ARCHIVE: Get latest snapshot of all tracked targets
    // Endpoint: GET /SERAPH/chronicles
    @CrossOrigin(origins = "*")
    @GetMapping("/chronicles")
    @Transactional(readOnly = true)
    public ResponseEntity<List<ProfileAnalysis>> getTheArchives() {
        return ResponseEntity.ok(repository.findLatestProfiles());
    }

    // 2. THE TESTAMENT: Get full history for one target
    // Endpoint: GET /SERAPH/chronicles/{username}
    @CrossOrigin(origins = "*")
    @GetMapping("/chronicles/{username}") // <--- CHANGED for cleaner routing
    @Transactional(readOnly = true)
    public ResponseEntity<List<ProfileAnalysis>> getUserTestament(@PathVariable String username) {
        return ResponseEntity.ok(repository.findAllByUsernameOrderByCreatedAtDesc(username));
    }
}