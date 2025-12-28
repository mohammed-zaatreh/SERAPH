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

    private static final double SEMANTIC_THRESHOLD = 0.25;

    // CHANGE RETURN TYPE TO String
    public String analyzeProfile(String profileUrl) {
        try {
            String username = extractUsername(profileUrl);

            // 1. CACHE HIT
            Optional<ProfileAnalysis> existingProfile = profileRepo.findByUsername(username);
            if (existingProfile.isPresent()) {
                log.info("CACHE HIT: Returning full analysis for {}", username);
                List<RedditPost> posts = postRepo.findAllByUsernameOrderByCreatedUtcDesc(username);
                // Serialize to JSON String
                return objectMapper.writeValueAsString(new AnalysisResult(existingProfile.get(), posts));
            }

            // 2. FETCH DATA
            String token = redditClient.getAppToken();
            List<Map<String, Object>> rawPosts = fetchAllPosts(username, token);

            if (rawPosts.isEmpty()) {
                return "{\"error\": \"EMPTY_PROFILE: No posts found for user: " + username + "\"}";
            }

            // 3. ANALYZE
            List<RedditPost> analyzedPosts = runSemanticAnalysis(username, rawPosts);

            // 4. SAVE & RETURN
            postRepo.saveAll(analyzedPosts);
            ProfileAnalysis summary = saveProfileSummary(username, analyzedPosts);

            // Serialize to JSON String
            return objectMapper.writeValueAsString(new AnalysisResult(summary, analyzedPosts));

        } catch (Exception e) {
            log.error("Analysis Failed", e);
            // Return Error String
            return "{\"error\": \"Analysis Failed: " + e.getMessage() + "\"}";
        }
    }

    // 2. Update runSemanticAnalysis method (Replace the whole method with this):
    private List<RedditPost> runSemanticAnalysis(String username, List<Map<String, Object>> rawPosts) {
        List<RedditPost> results = new ArrayList<>();
        List<String> texts = rawPosts.stream().map(p -> (String) p.get("fullText")).toList();

        // MODEL A: Neural (Embedding)
        Map<String, List<Double>> neuralScores = embeddingRanker.scorePosts(texts);

        // MODEL B: Statistical (BM25)
        Map<String, List<Double>> bm25Scores = bm25Ranker.scorePosts(texts);

        Set<String> categories = neuralScores.keySet(); // Use Neural keys as master list

        for (int i = 0; i < rawPosts.size(); i++) {
            Map<String, Object> raw = rawPosts.get(i);
            Map<String, Double> postScores = new HashMap<>();

            for (String cat : categories) {
                // Get Score A
                List<Double> nList = neuralScores.get(cat);
                double scoreA = (nList != null && i < nList.size()) ? nList.get(i) : 0.0;
                if (scoreA < SEMANTIC_THRESHOLD) scoreA = 0.0;

                // Get Score B (BM25) - Handle case where BM25 might not have that category key
                List<Double> bList = bm25Scores.getOrDefault(cat, Collections.emptyList());
                double scoreB = (bList != null && i < bList.size()) ? bList.get(i) : 0.0;

                // --- ENSEMBLE LOGIC (Weighted Average) ---
                // 70% Semantic, 30% Keyword
                double finalScore = (scoreA * 0.7) + (scoreB * 0.3);

                postScores.put(cat, finalScore);
            }

            // Logic: "Normal" Injection (Same as before)
            double maxRisk = postScores.entrySet().stream()
                    .filter(e -> !e.getKey().equals("FUNCTIONAL_BASELINE"))
                    .mapToDouble(Map.Entry::getValue)
                    .max().orElse(0.0);

            if (maxRisk == 0.0) {
                postScores.put("FUNCTIONAL_BASELINE", 0.9);
            }

            // Convert to Percent
            Map<String, Double> percentScores = new HashMap<>();
            postScores.forEach((k, v) -> percentScores.put(k, v * 100.0));

            RedditPost post = RedditPost.builder()
                    .username(username)
                    .redditPostId((String) raw.get("postId"))
                    .permalink((String) raw.get("permalink"))
                    .title((String) raw.get("title"))
                    .content((String) raw.get("text"))
                    .createdUtc((Long) raw.get("createdUtc"))
                    .tokens(toJson(percentScores))
                    .build();
            results.add(post);
        }
        return results;
    }

    private ProfileAnalysis saveProfileSummary(String username, List<RedditPost> posts) {
        Map<String, Double> totals = new HashMap<>();

        // Aggregate (Sums will now be sums of percentages, e.g. 90.0 + 90.0 = 180.0)
        for (RedditPost p : posts) {
            try {
                Map<String, Double> scores = objectMapper.readValue(p.getTokens(), Map.class);
                scores.forEach((cat, score) -> totals.merge(cat, score, Double::sum));
            } catch (Exception ignored) {}
        }

        double totalMass = totals.values().stream().mapToDouble(d -> d).sum();
        Map<String, Double> percentages = new HashMap<>();

        // Calculate relative percentage (0-100 scale)
        // (Individual Score / Total Mass) * 100
        totals.forEach((k, v) -> percentages.put(k, totalMass > 0 ? (v / totalMass) * 100.0 : 0.0)); // <--- CONVERT TO PERCENT

        String topOverall = totals.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getValue() > 0 ? e.getKey() : "FUNCTIONAL_BASELINE")
                .orElse("FUNCTIONAL_BASELINE");

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
}