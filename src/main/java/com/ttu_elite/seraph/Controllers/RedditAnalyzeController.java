package com.ttu_elite.seraph.Controllers;

import com.ttu_elite.seraph.Entities.ProfileAnalysis;
import com.ttu_elite.seraph.Entities.RedditPost;
import com.ttu_elite.seraph.Repositories.ProfileAnalysisRepository;
import com.ttu_elite.seraph.Repositories.RedditPostRepository;
import com.ttu_elite.seraph.Services.RedditAnalyzeService;
import com.ttu_elite.seraph.dto.AnalysisResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analyze")
@RequiredArgsConstructor
public class RedditAnalyzeController {

    private final RedditAnalyzeService service;
    private final ProfileAnalysisRepository profileRepo;
    private final RedditPostRepository postRepo;

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
            if (profileRepo.existsByUsername(username)) {
                profileRepo.deleteByUsername(username);
                // Make sure to implement deleteByUsername in Repository or use deleteAll for now
                List<RedditPost> oldPosts = postRepo.findAllByUsernameOrderByCreatedUtcDesc(username);
                postRepo.deleteAll(oldPosts);
            }
        }

        try {
            // Service now returns a String (JSON), so we return it directly
            String jsonResult = service.analyzeProfile(url);

            // We verify if it is an error string to set the status code
            if (jsonResult.contains("\"error\":")) {
                return ResponseEntity.badRequest().body(jsonResult);
            }

            // Return raw JSON string with correct header
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(jsonResult);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    }
