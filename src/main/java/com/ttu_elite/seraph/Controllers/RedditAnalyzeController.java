package com.ttu_elite.seraph.Controllers;


import com.ttu_elite.seraph.Services.RedditAnalyzeService;
import com.ttu_elite.seraph.dto.AnalyzeRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analyze")
@CrossOrigin(origins = "*")
public class RedditAnalyzeController {



    private final RedditAnalyzeService redditAnalyzeService;

    public RedditAnalyzeController(RedditAnalyzeService redditAnalyzeService) {
        this.redditAnalyzeService = redditAnalyzeService;
    }

    @PostMapping("/reddit")
    public ResponseEntity<?> analyzeReddit(@Valid @RequestBody AnalyzeRequest request) {
        return ResponseEntity.ok(redditAnalyzeService.analyzeProfile(request.getProfileUrl()));
    }
}


