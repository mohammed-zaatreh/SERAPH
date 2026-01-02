package com.ttu_elite.seraph.Services;

import com.ttu_elite.seraph.Entities.ProfileAnalysis;
import com.ttu_elite.seraph.Entities.RedditPost;
import com.ttu_elite.seraph.Repositories.ProfileAnalysisRepository;
import com.ttu_elite.seraph.Repositories.RedditPostRepository;
import com.ttu_elite.seraph.dto.AnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RedditAnalyzeService {
    private final Bm25Ranker bm25Ranker;
    private final RedditClient redditClient;
    private final RedditPostRepository postRepo;
    private final ProfileAnalysisRepository profileRepo;
    private final EmbeddingRanker embeddingRanker;
    private final ObjectMapper objectMapper;

    // Tuning Weights (Must add up to 1.0)
    private static final double WEIGHT_SEMANTIC = 0.7;
    private static final double WEIGHT_KEYWORD = 0.3;
    private static final double SEMANTIC_THRESHOLD = 0.15; // Minimum score to matter

    // CHANGE RETURN TYPE TO String
    public String analyzeProfile(String profileUrl) {
        try {
            String username = extractUsername(profileUrl);

            // 1. CACHE HIT (Fetch the LATEST snapshot)
            // We use the new query: findTop...OrderByCreatedAtDesc
            Optional<ProfileAnalysis> latestProfile = profileRepo.findTopByUsernameOrderByCreatedAtDesc(username);

            // Logic: If it exists and is recent (e.g., < 24 hours), return it.
            // If you want "Force" logic, you can check a flag here.
            if (latestProfile.isPresent()) {
                ProfileAnalysis profile = latestProfile.get();
                // Optional: Check if it's too old? if (profile.getCreatedAt()... > 7 days) { ... }

                log.info("SNAPSHOT HIT: Returning latest analysis for {} from {}", username, profile.getCreatedAt());

                // FETCH POSTS BY SNAPSHOT ID (Not Username!)
                List<RedditPost> posts = postRepo.findAllByAnalysisId(profile.getId());

                return objectMapper.writeValueAsString(new AnalysisResult(profile, posts));
            }

            // 2. FETCH DATA (Same as before)
            String token = redditClient.getAppToken();
            List<Map<String, Object>> rawPosts = fetchAllPosts(username, token);

            if (rawPosts.isEmpty()) {
                return "{\"error\": \"EMPTY_PROFILE: No posts found for user: " + username + "\"}";
            }

            // 3. ANALYZE (Hybrid)
            List<RedditPost> analyzedPosts = runHybridAnalysis(username, rawPosts);

            // 4. SAVE SNAPSHOT (The New Order of Operations)

            // A. Create & Save the Profile Summary FIRST
            ProfileAnalysis summary = saveProfileSummary(username, analyzedPosts); // (Helper method that builds the object)
            summary = profileRepo.save(summary); // Save to generate the ID
            Long newAnalysisId = summary.getId(); // <--- GRAB THE ID

            // B. Link Posts to this specific Snapshot ID
            for (RedditPost post : analyzedPosts) {
                post.setAnalysisId(newAnalysisId); // <--- STAMP THE ID
            }

            // C. Save Posts
            postRepo.saveAll(analyzedPosts);

            return objectMapper.writeValueAsString(new AnalysisResult(summary, analyzedPosts));

        } catch (Exception e) {
            log.error("Analysis Failed", e);
            return "{\"error\": \"Analysis Failed: " + e.getMessage() + "\"}";
        }
    }

    private List<RedditPost> runHybridAnalysis(String username, List<Map<String, Object>> rawPosts) {
        List<RedditPost> results = new ArrayList<>();

        // Extract just the text for batch processing
        List<String> texts = rawPosts.stream().map(p -> (String) p.get("fullText")).toList();

        // --- STEP A: RUN BOTH MODELS ---
        // 1. Neural Model (Context/Vibe)
        Map<String, List<Double>> semanticScores = embeddingRanker.scorePosts(texts);

        // 2. Lexical Model (Keywords)
        Map<String, List<Double>> keywordScores = bm25Ranker.scorePosts(texts);

        Set<String> categories = semanticScores.keySet();

        // --- STEP B: MERGE SCORES ---
        for (int i = 0; i < rawPosts.size(); i++) {
            Map<String, Object> raw = rawPosts.get(i);
            Map<String, Double> finalPostScores = new HashMap<>();

            for (String cat : categories) {
                // Get Semantic Score (Default 0.0)
                List<Double> semList = semanticScores.get(cat);
                double sScore = (semList != null && i < semList.size()) ? semList.get(i) : 0.0;

                // Get BM25 Score (Default 0.0)
                List<Double> kwList = keywordScores.get(cat);
                double kScore = (kwList != null && i < kwList.size()) ? kwList.get(i) : 0.0;

                // HYBRID FORMULA: Weighted Average
                // If Neural says 0.8 (high risk) and Keyword says 0.0 (no explicit words) -> Result 0.56
                // If Both say high -> Result is very high.
                double hybridScore = (sScore * WEIGHT_SEMANTIC) + (kScore * WEIGHT_KEYWORD);

                // Filter Noise
                if (hybridScore < SEMANTIC_THRESHOLD) hybridScore = 0.0;

                finalPostScores.put(cat, hybridScore);
            }

            // --- STEP C: BASELINE LOGIC ---
            // Calculate max risk to see if this is a "normal" post
            double maxRisk = finalPostScores.entrySet().stream()
                    .filter(e -> !e.getKey().equals("FUNCTIONAL_BASELINE"))
                    .mapToDouble(Map.Entry::getValue)
                    .max().orElse(0.0);

            // If no risk categories were triggered, boost Functional Baseline
            if (maxRisk == 0.0) {
                finalPostScores.put("FUNCTIONAL_BASELINE", 0.9);
            }

            // --- STEP D: RENAME & FORMAT FOR UI ---
            Map<String, Double> uiScores = new HashMap<>();
            finalPostScores.forEach((k, v) -> {
                String uiName = getDisplayName(k);
                double rounded = Math.round(v * 100.0) / 100.0;
                uiScores.put(uiName, rounded);
            });

            RedditPost post = RedditPost.builder()
                    .username(username)
                    .redditPostId((String) raw.get("postId"))
                    .permalink((String) raw.get("permalink"))
                    .title((String) raw.get("title"))
                    .content((String) raw.get("text"))
                    .createdUtc((Long) raw.get("createdUtc"))
                    .tokens(toJson(uiScores))
                    .build();
            results.add(post);
        }
        return results;
    }

    private List<RedditPost> runSemanticAnalysis(String username, List<Map<String, Object>> rawPosts) {
        List<RedditPost> results = new ArrayList<>();
        List<String> texts = rawPosts.stream().map(p -> (String) p.get("fullText")).toList();

        Map<String, List<Double>> scoresMap = embeddingRanker.scorePosts(texts);
        Set<String> categories = scoresMap.keySet();

        for (int i = 0; i < rawPosts.size(); i++) {
            Map<String, Object> raw = rawPosts.get(i);
            Map<String, Double> postScores = new HashMap<>();

            // 1. Fill Raw Scores (0.0 to 1.0)
            for (String cat : categories) {
                List<Double> catScores = scoresMap.get(cat);
                double score = (catScores != null && i < catScores.size()) ? catScores.get(i) : 0.0;
                if (score < SEMANTIC_THRESHOLD) score = 0.0;
                postScores.put(cat, score);
            }

            // 2. Logic: "Normal" Injection
            double maxRisk = postScores.entrySet().stream()
                    .filter(e -> !e.getKey().equals("FUNCTIONAL_BASELINE"))
                    .mapToDouble(Map.Entry::getValue)
                    .max().orElse(0.0);

            if (maxRisk == 0.0) {
                postScores.put("FUNCTIONAL_BASELINE", 0.9);
            }

            // 3. RENAME AND ROUND (0.288 -> 0.29)
            Map<String, Double> cleanScores = new HashMap<>();
            postScores.forEach((k, v) -> {
                String uiName = getDisplayName(k); // <--- Rename to UI Label
                double rounded = Math.round(v * 100.0) / 100.0; // <--- Round to 2 decimals
                cleanScores.put(uiName, rounded);
            });

            RedditPost post = RedditPost.builder()
                    .username(username)
                    .redditPostId((String) raw.get("postId"))
                    .permalink((String) raw.get("permalink"))
                    .title((String) raw.get("title"))
                    .content((String) raw.get("text"))
                    .createdUtc((Long) raw.get("createdUtc"))
                    .tokens(toJson(cleanScores)) // Save the clean version
                    .build();
            results.add(post);
        }
        return results;
    }

    // 2. UPDATED: Summary with Renaming and Rounding
    private ProfileAnalysis saveProfileSummary(String username, List<RedditPost> posts) {
        Map<String, Double> totals = new HashMap<>();

        // Sum up the CLEAN scores (already renamed)
        for (RedditPost p : posts) {
            try {
                Map<String, Double> scores = objectMapper.readValue(p.getTokens(), Map.class);
                scores.forEach((cat, score) -> totals.merge(cat, score, Double::sum));
            } catch (Exception ignored) {}
        }

        double totalMass = totals.values().stream().mapToDouble(d -> d).sum();
        Map<String, Double> percentages = new HashMap<>();

        // Calculate Percentages (0.0 - 1.0) and Round
        totals.forEach((k, v) -> {
            double ratio = totalMass > 0 ? (v / totalMass) : 0.0;
            double rounded = Math.round(ratio * 100.0) / 100.0; // <--- Round to 2 decimals
            percentages.put(k, rounded);
        });

        // Determine Top Category (Using UI Names now)
        String topOverall = totals.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getValue() > 0 ? e.getKey() : "Sentiment") // Default to Sentiment
                .orElse("Sentiment");

        ProfileAnalysis profile = ProfileAnalysis.builder()
                .platform("reddit")
                .username(username)
                .postCount(posts.size())
                .topCategoryOverall(topOverall)
                .confidence(totalMass > 0 ? 1.0 : 0.0)
                .profileTotalsJson(toJson(totals))
                .profilePercentagesJson(toJson(percentages))
                .createdAt(Instant.now())
                .build();

        return profileRepo.save(profile);
    }

    // 3. HELPER: The Dictionary (Copy this to bottom of class)
    private String getDisplayName(String internalKey) {
        return switch (internalKey) {
            case "FUNCTIONAL_BASELINE" -> "Sentiment";
            case "ANXIETY_STRESS" -> "Distress";
            case "HOSTILITY" -> "Hostility";
            case "SADNESS" -> "Sadness";
            case "SELF_HARM_RISK" -> "Self-Harm";
            default -> internalKey;
        };
    }

    private List<Map<String, Object>> fetchAllPosts(String username, String token) {
        // ... (This helper remains unchanged) ...
        List<Map<String, Object>> allPosts = new ArrayList<>();
        String after = null;
        int maxPosts = 50;

        while (allPosts.size() < maxPosts) {
            Map<String, Object> response = redditClient.fetchUserSubmitted(token, username, after);
            if (response == null || !response.containsKey("data")) break;

            Map data = (Map) response.get("data");
            List<Map> children = (List<Map>) data.get("children");
            if (children == null || children.isEmpty()) break;

            for (Map child : children) {
                Map d = (Map) child.get("data");
                Map<String, Object> clean = new HashMap<>();
                clean.put("postId", d.get("id"));
                clean.put("title", d.get("title"));
                clean.put("text", d.getOrDefault("selftext", ""));
                clean.put("permalink", "https://www.reddit.com" + d.get("permalink"));
                clean.put("createdUtc", ((Number) d.getOrDefault("created_utc", 0)).longValue());
                clean.put("fullText", (clean.get("title") + " " + clean.get("text")).trim());
                allPosts.add(clean);
            }
            after = (String) data.get("after");
            if (after == null) break;
        }
        return allPosts;
    }

    private String extractUsername(String url) {
        if (url.contains("/user/")) return url.split("/user/")[1].split("/")[0].split("\\?")[0];
        return url;
    }

    private String toJson(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    /////////////////////
    /// ////////////
    /// ///////
    //////////////////////////












    // NEW SIMULATION METHOD
    public String simulateAnalysis(String mockUsername, List<String> texts) {
        try {
            // 1. Convert simple strings to the Map structure the pipeline expects
            List<Map<String, Object>> mockPosts = new ArrayList<>();
            long fakeTime = Instant.now().getEpochSecond();

            for (int i = 0; i < texts.size(); i++) {
                Map<String, Object> p = new HashMap<>();
                p.put("postId", "sim_" + i);
                p.put("title", "Simulation Post " + (i + 1));
                p.put("text", texts.get(i));
                p.put("fullText", texts.get(i)); // The text you sent
                p.put("permalink", "https://localhost/simulation");
                p.put("createdUtc", fakeTime - (i * 86400)); // Each post 1 day apart
                mockPosts.add(p);
            }

            // 2. RUN THE EXACT SAME AI PIPELINE
            List<RedditPost> analyzedPosts = runSemanticAnalysis(mockUsername, mockPosts);

            // 3. Generate Summary (Don't save to DB to keep production clean)
            ProfileAnalysis summary = generateTransientSummary(mockUsername, analyzedPosts);

            // 4. Return Result
            return objectMapper.writeValueAsString(new AnalysisResult(summary, analyzedPosts));

        } catch (Exception e) {
            log.error("Simulation Failed", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // Helper to generate summary without saving to DB
    private ProfileAnalysis generateTransientSummary(String username, List<RedditPost> posts) {
        Map<String, Double> totals = new HashMap<>();
        for (RedditPost p : posts) {
            try {
                Map<String, Double> scores = objectMapper.readValue(p.getTokens(), Map.class);
                scores.forEach((cat, score) -> totals.merge(cat, score, Double::sum));
            } catch (Exception ignored) {}
        }

        double totalMass = totals.values().stream().mapToDouble(d -> d).sum();
        Map<String, Double> percentages = new HashMap<>();
        totals.forEach((k, v) -> {
            double ratio = totalMass > 0 ? (v / totalMass) : 0.0;
            percentages.put(k, Math.round(ratio * 100.0) / 100.0);
        });

        String topOverall = totals.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getValue() > 0 ? e.getKey() : "Sentiment")
                .orElse("Sentiment");

        return ProfileAnalysis.builder()
                .platform("simulation")
                .username(username)
                .postCount(posts.size())
                .topCategoryOverall(topOverall)
                .confidence(1.0)
                .profileTotalsJson(toJson(totals))
                .profilePercentagesJson(toJson(percentages))
                .createdAt(Instant.now())
                .build();
    }


    public List<ProfileAnalysis> getProfileHistory(String username) {
        // Returns the lightweight headers (stats + timestamps) without the heavy post text
        return profileRepo.findAllByUsernameOrderByCreatedAtDesc(username);
    }
}