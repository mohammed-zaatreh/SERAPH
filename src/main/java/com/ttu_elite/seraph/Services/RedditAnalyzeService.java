package com.ttu_elite.seraph.Services;

import com.ttu_elite.seraph.Entities.ProfileAnalysis;
import com.ttu_elite.seraph.Entities.RedditPost;
import com.ttu_elite.seraph.Repositories.ProfileAnalysisRepository;
import com.ttu_elite.seraph.Repositories.RedditPostRepository;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RedditAnalyzeService {

    private final RedditClient redditClient;
    private final TextPreprocessor preprocessor;
    private final RedditPostRepository repo;

    private final TfidfRanker tfidfRanker;
    private final Bm25Ranker bm25Ranker;

    private final ProfileAnalysisRepository profileAnalysisRepo;
    private final ObjectMapper objectMapper;

    private final Map<String, String> categoryQueries = buildCategoryQueries();

    // RRF kept for optional ordering (not used for aggregation/scoring)
    private static final int RRF_K = 60;

    // Best-category gating
    private static final double BEST_ABS_THRESHOLD = 0.20;   // tune
    private static final double BEST_MARGIN_THRESHOLD = 0.03; // tune

    // Scoring weights (tune)
    private static final double W_VSM = 0.50;
    private static final double W_BM25 = 0.50;

    // Evidence thresholds / guards
    private static final int MIN_DOC_TOKENS = 5;
    private static final double EPS = 1e-9;

    public RedditAnalyzeService(
            RedditClient redditClient,
            TextPreprocessor preprocessor,
            RedditPostRepository repo,
            TfidfRanker tfidfRanker,
            Bm25Ranker bm25Ranker,
            ProfileAnalysisRepository profileAnalysisRepo,
            ObjectMapper objectMapper
    ) {
        this.redditClient = redditClient;
        this.preprocessor = preprocessor;
        this.repo = repo;
        this.tfidfRanker = tfidfRanker;
        this.bm25Ranker = bm25Ranker;
        this.profileAnalysisRepo = profileAnalysisRepo;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> analyzeProfile(String profileUrl) {
        String username = extractRedditUsername(profileUrl);
        String token = redditClient.getAppToken();

        // 1) Fetch raw posts
        List<Map<String, Object>> rawPosts = new ArrayList<>();
        String after = null;
        int fetched = 0;
        int maxPosts = 200;

        while (fetched < maxPosts) {
            Map<String, Object> listing = redditClient.fetchUserSubmitted(token, username, after);
            Map<String, Object> data = safeMap(listing.get("data"));
            List<Map<String, Object>> children = safeListOfMaps(data.get("children"));
            if (children.isEmpty()) break;

            for (Map<String, Object> child : children) {
                Map<String, Object> postData = safeMap(child.get("data"));

                String postId = Objects.toString(postData.get("id"), "");
                if (postId.isBlank()) continue;

                String title = Objects.toString(postData.get("title"), "");
                String selftext = Objects.toString(postData.get("selftext"), "");
                String permalink = "https://www.reddit.com" + Objects.toString(postData.get("permalink"), "");
                Long createdUtc = postData.get("created_utc") == null ? null : ((Number) postData.get("created_utc")).longValue();

                String fullText = (title + " " + selftext).trim();

                Map<String, Object> r = new LinkedHashMap<>();
                r.put("postId", postId);
                r.put("title", title);
                r.put("text", selftext);
                r.put("permalink", permalink);
                r.put("createdUtc", createdUtc);
                r.put("fullText", fullText);
                rawPosts.add(r);

                fetched++;
                if (fetched >= maxPosts) break;
            }

            after = Objects.toString(data.get("after"), null);
            if (after == null) break;
        }

        // Empty response
        if (rawPosts.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("platform", "reddit");
            empty.put("username", username);
            empty.put("count", 0);
            empty.put("categories", categoryQueries.keySet());
            empty.put("results", List.of());
            empty.put("profileTotals", Map.of());
            empty.put("profilePercentages", Map.of());
            empty.put("topCategoryOverall", "NONE");
            empty.put("confidence", 0.0);
            empty.put("ensemble", Map.of("method", "VSM+BM25", "wVsm", W_VSM, "wBm25", W_BM25));
            return empty;
        }

        // 2) Preprocess posts into token lists
        List<List<String>> postTokens = new ArrayList<>(rawPosts.size());
        for (Map<String, Object> p : rawPosts) {
            String fullText = Objects.toString(p.get("fullText"), "");
            postTokens.add(preprocessor.preprocess(fullText));
        }

        // 3) Preprocess category queries into tokens
        Map<String, List<String>> categoryTokens = categoryQueries.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> preprocessor.preprocess(e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        // 4) VSM (TF-IDF cosine) scores: raw continuous values
        Map<String, List<Double>> vsmScores = tfidfRanker.scoreAllPostsAgainstCategories(postTokens, categoryTokens);

        // 5) BM25 raw scores
        CorpusStats stats = buildCorpusStats(postTokens);

        Map<String, List<Double>> bm25RawByCat = new LinkedHashMap<>();
        for (var e : categoryTokens.entrySet()) {
            String cat = e.getKey();
            Set<String> qSet = new HashSet<>(e.getValue());

            List<Double> scores = new ArrayList<>(postTokens.size());
            for (List<String> doc : postTokens) {
                scores.add(bm25Ranker.score(doc, qSet, stats.df, stats.N, stats.avgdl));
            }
            bm25RawByCat.put(cat, scores);
        }

        // 6) Normalize BM25 per-category into [0..1] (robust to outliers)
        //    VSM is already typically [0..1], but we still clamp to [0..1].
        Map<String, List<Double>> bm25NormByCat = new LinkedHashMap<>();
        for (String cat : categoryQueries.keySet()) {
            List<Double> raw = padToN(bm25RawByCat.get(cat), rawPosts.size());
            bm25NormByCat.put(cat, robustNormalize01(raw));
        }

        // 7) Optional: compute RRF ranks for UI ordering (NOT used for aggregation)
        Map<String, List<Double>> rrfRawByCat = new LinkedHashMap<>();
        for (String cat : categoryQueries.keySet()) {
            List<Double> vsm = padToN(vsmScores.get(cat), rawPosts.size());
            List<Double> bm25 = padToN(bm25RawByCat.get(cat), rawPosts.size());
            int n = rawPosts.size();
            int[] rV = ranksDescending(vsm);
            int[] rB = ranksDescending(bm25);

            List<Double> rrf = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double v = 1.0 / (RRF_K + rV[i]);
                double b = 1.0 / (RRF_K + rB[i]);
                rrf.add(v + b);
            }
            rrfRawByCat.put(cat, rrf);
        }

        // 8) Build results; persist posts (only if new)
        List<Map<String, Object>> results = new ArrayList<>(rawPosts.size());

        for (int i = 0; i < rawPosts.size(); i++) {
            Map<String, Object> p = rawPosts.get(i);

            String postId = Objects.toString(p.get("postId"), "");
            String title = Objects.toString(p.get("title"), "");
            String selftext = Objects.toString(p.get("text"), "");
            String permalink = Objects.toString(p.get("permalink"), "");
            Long createdUtc = (Long) p.get("createdUtc");

            List<String> docTok = postTokens.get(i);
            boolean docHasEvidence = docTok != null && docTok.size() >= MIN_DOC_TOKENS;

            // Build per-category scores
            Map<String, Double> ensembleScores = new LinkedHashMap<>();
            Map<String, Map<String, Double>> scoresBreakdown = new LinkedHashMap<>();

            for (String cat : categoryQueries.keySet()) {
                double vsm = clamp01(valueAt(padToN(vsmScores.get(cat), rawPosts.size()), i));
                double b25 = clamp01(valueAt(bm25NormByCat.get(cat), i)); // normalized [0..1]

                // Special-case: SELF_HARM_RISK should be hard evidence only (no rank effects)
                // Score=1 only if any token matches the lexicon terms (after preprocessing).
                if ("SELF_HARM_RISK".equals(cat)) {
                    boolean hit = docHasEvidence && containsAny(docTok, categoryTokens.get(cat));
                    vsm = hit ? 1.0 : 0.0;
                    b25 = hit ? 1.0 : 0.0;
                }

                // If the document is too short, do not allow any category to win
                if (!docHasEvidence) {
                    vsm = 0.0;
                    b25 = 0.0;
                }

                double ens = clamp01(W_VSM * vsm + W_BM25 * b25);

                ensembleScores.put(cat, ens);

                Map<String, Double> parts = new LinkedHashMap<>();
                parts.put("vsm", vsm);                   // continuous cosine
                parts.put("bm25", b25);                  // normalized bm25
                parts.put("ensemble", ens);              // continuous meaningful ensemble
                parts.put("rrfRaw", valueAt(rrfRawByCat.get(cat), i)); // optional for debugging
                scoresBreakdown.put(cat, parts);
            }

            // bestCategory with thresholds
            BestPick pick = bestWithMargin(ensembleScores, BEST_ABS_THRESHOLD, BEST_MARGIN_THRESHOLD);

            if (!repo.existsByRedditPostId(postId)) {
                RedditPost entity = new RedditPost();
                entity.setUsername(username);
                entity.setRedditPostId(postId);
                entity.setPermalink(permalink);
                entity.setTitle(title);
                entity.setContent(selftext);
                entity.setCreatedUtc(createdUtc);
                entity.setTokens(String.join(" ", docTok == null ? List.of() : docTok));
                repo.save(entity);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("postId", postId);
            row.put("permalink", permalink);
            row.put("title", title);
            row.put("text", selftext);
            row.put("scores", scoresBreakdown);

            row.put("ensembleScores", ensembleScores);
            row.put("maxScore", pick.bestScore);
            row.put("bestCategory", pick.bestCategory);

            // Optional: surface whether we considered this post “evidence-bearing”
            row.put("minTokensOk", docHasEvidence);

            results.add(row);
        }

        // 9) Sort posts by strongest evidence (meaningful ensemble)
        results.sort((a, b) -> Double.compare(
                (Double) b.get("maxScore"),
                (Double) a.get("maxScore")
        ));

        // 10) Profile-level aggregation: sum meaningful ensemble scores (NOT RRF)
        Map<String, Double> profileTotals = new LinkedHashMap<>();
        for (String cat : categoryQueries.keySet()) profileTotals.put(cat, 0.0);

        int contributingPosts = 0;
        for (Map<String, Object> r : results) {
            Boolean ok = (Boolean) r.getOrDefault("minTokensOk", Boolean.TRUE);
            if (ok == null || !ok) continue;

            @SuppressWarnings("unchecked")
            Map<String, Double> ens = (Map<String, Double>) r.get("ensembleScores");
            if (ens == null) continue;

            contributingPosts++;
            for (var e : ens.entrySet()) {
                profileTotals.merge(e.getKey(), safe(e.getValue()), Double::sum);
            }
        }

        double totalMass = profileTotals.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> profilePercentages = new LinkedHashMap<>();
        for (var e : profileTotals.entrySet()) {
            profilePercentages.put(e.getKey(), totalMass <= EPS ? 0.0 : e.getValue() / totalMass);
        }

        String topOverall = profileTotals.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getValue() <= EPS ? "NONE" : e.getKey())
                .orElse("NONE");

        // 11) Confidence: now based on meaningful maxScore and evidence-bearing posts
        double avgBest = results.stream()
                .filter(r -> Boolean.TRUE.equals(r.getOrDefault("minTokensOk", Boolean.TRUE)))
                .mapToDouble(r -> (Double) r.get("maxScore"))
                .average().orElse(0.0);

        // Light shaping
        double confidence = clamp01((avgBest - 0.15) / 0.85);

        // 12) Persist profile analysis (best-effort)
        saveProfileAnalysisSafely(username, results.size(), profileTotals, profilePercentages, topOverall, confidence);

        // 13) Response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("platform", "reddit");
        response.put("username", username);
        response.put("count", results.size());
        response.put("categories", categoryQueries.keySet());
        response.put("results", results);

        response.put("profileTotals", profileTotals);
        response.put("profilePercentages", profilePercentages);
        response.put("topCategoryOverall", topOverall);
        response.put("confidence", confidence);

        response.put("ensemble", Map.of(
                "method", "VSM+BM25",
                "wVsm", W_VSM,
                "wBm25", W_BM25,
                "minDocTokens", MIN_DOC_TOKENS,
                "bestAbsThreshold", BEST_ABS_THRESHOLD,
                "bestMarginThreshold", BEST_MARGIN_THRESHOLD,
                "rrfK_debug", RRF_K
        ));

        return response;
    }

    // ---------------- persistence ----------------

    private void saveProfileAnalysisSafely(
            String username,
            int postCount,
            Map<String, Double> profileTotals,
            Map<String, Double> profilePercentages,
            String topOverall,
            double confidence
    ) {
        ProfileAnalysis pa = new ProfileAnalysis();
        pa.setPlatform("reddit");
        pa.setUsername(username);
        pa.setPostCount(postCount);
        pa.setTopCategoryOverall(topOverall);
        pa.setConfidence(confidence);

        try {
            pa.setCreatedAt(Instant.now());
        } catch (Exception ignored) {}

        pa.setProfileTotalsJson(toJsonOrEmpty(profileTotals));
        pa.setProfilePercentagesJson(toJsonOrEmpty(profilePercentages));

        try {
            profileAnalysisRepo.save(pa);
        } catch (Exception ignored) {
            // keep endpoint functional even if persistence fails
        }
    }

    private String toJsonOrEmpty(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    // ---------------- BM25 corpus stats ----------------

    private static class CorpusStats {
        Map<String, Integer> df;
        int N;
        double avgdl;
    }

    private CorpusStats buildCorpusStats(List<List<String>> docs) {
        CorpusStats st = new CorpusStats();
        st.N = docs.size();

        Map<String, Integer> df = new HashMap<>();
        int totalLen = 0;

        for (List<String> doc : docs) {
            totalLen += (doc == null ? 0 : doc.size());
            Set<String> uniq = new HashSet<>(doc == null ? List.of() : doc);
            for (String t : uniq) df.merge(t, 1, Integer::sum);
        }

        st.df = df;
        st.avgdl = Bm25Ranker.safeAvgdl(totalLen, st.N);
        return st;
    }

    // ---------------- scoring helpers ----------------

    private static List<Double> padToN(List<Double> xs, int n) {
        if (xs == null) xs = List.of();
        if (xs.size() == n) return xs;

        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(i < xs.size() && xs.get(i) != null ? xs.get(i) : 0.0);
        return out;
    }

    /**
     * Robust normalize to [0..1] using p10/p90 to avoid outliers dominating.
     * If p90 ~= p10, returns all zeros.
     */
    private static List<Double> robustNormalize01(List<Double> raw) {
        int n = raw == null ? 0 : raw.size();
        if (n == 0) return List.of();

        List<Double> xs = new ArrayList<>(n);
        for (Double d : raw) xs.add(d == null ? 0.0 : d);

        List<Double> sorted = new ArrayList<>(xs);
        sorted.sort(Double::compare);

        double p10 = percentile(sorted, 0.10);
        double p90 = percentile(sorted, 0.90);

        double denom = p90 - p10;
        if (Math.abs(denom) <= EPS) {
            List<Double> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(0.0);
            return out;
        }

        List<Double> out = new ArrayList<>(n);
        for (double v : xs) {
            double z = (v - p10) / denom;
            out.add(clamp01(z));
        }
        return out;
    }

    private static double percentile(List<Double> sortedAsc, double p) {
        if (sortedAsc == null || sortedAsc.isEmpty()) return 0.0;
        int n = sortedAsc.size();
        double pos = p * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sortedAsc.get(lo);
        double a = sortedAsc.get(lo);
        double b = sortedAsc.get(hi);
        double t = pos - lo;
        return a + (b - a) * t;
    }

    private static int[] ranksDescending(List<Double> scores) {
        int n = scores.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;

        idx = Arrays.stream(idx).sorted((i, j) -> Double.compare(
                safe(scores.get(j)),
                safe(scores.get(i))
        )).toArray(Integer[]::new);

        int[] rank = new int[n];
        for (int r = 0; r < n; r++) rank[idx[r]] = r + 1; // rank 1 is best
        return rank;
    }

    private static boolean containsAny(List<String> docTokens, List<String> queryTokens) {
        if (docTokens == null || docTokens.isEmpty() || queryTokens == null || queryTokens.isEmpty()) return false;
        Set<String> docSet = new HashSet<>(docTokens);
        for (String q : queryTokens) {
            if (q == null || q.isBlank()) continue;
            if (docSet.contains(q)) return true;
        }
        return false;
    }

    private static double safe(Double d) {
        return d == null ? 0.0 : d;
    }

    private static double valueAt(List<Double> xs, int idx) {
        if (xs == null || idx < 0 || idx >= xs.size()) return 0.0;
        Double v = xs.get(idx);
        return v == null ? 0.0 : v;
    }

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    // ---------------- best pick ----------------

    private static class BestPick {
        String bestCategory;
        double bestScore;
        BestPick(String bestCategory, double bestScore) {
            this.bestCategory = bestCategory;
            this.bestScore = bestScore;
        }
    }

    private static BestPick bestWithMargin(Map<String, Double> scores, double absThresh, double marginThresh) {
        double best = -1.0;
        double second = -1.0;
        String bestCat = "NONE";

        for (var e : scores.entrySet()) {
            double v = safe(e.getValue());
            if (v > best) {
                second = best;
                best = v;
                bestCat = e.getKey();
            } else if (v > second) {
                second = v;
            }
        }

        if (best < absThresh) return new BestPick("NONE", best < 0 ? 0.0 : best);
        if (second >= 0 && (best - second) < marginThresh) return new BestPick("NONE", best);

        return new BestPick(bestCat, best);
    }

    // ---------------- parsing helpers ----------------

    private static Map<String, Object> safeMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> safeListOfMaps(Object o) {
        if (o instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    out.add(cast);
                }
            }
            return out;
        }
        return List.of();
    }

    private String extractRedditUsername(String url) {
        String s = url.trim();
        if (!s.contains("/")) return s;

        int idx = s.indexOf("/user/");
        if (idx < 0)
            throw new IllegalArgumentException("Expected reddit user URL like https://www.reddit.com/user/<name>/");

        String tail = s.substring(idx + "/user/".length());
        tail = tail.replaceAll("[/?#].*$", "");
        if (tail.isBlank()) throw new IllegalArgumentException("Could not parse username from URL");
        return tail;
    }

    // ---------------- categories ----------------

    private static Map<String, String> buildCategoryQueries() {
        Map<String, String> m = new LinkedHashMap<>();

        m.put("HOSTILITY",
                "hate hatred angry anger rage furious hostile hostility insult insults insulting "
                        + "abuse abusive attack attacks attacking threaten threat threats violence violent "
                        + "kill killing die death hurt hurting harm harmful bully bullying harassment harass "
                        + "stupid idiot dumb moron trash scum loser worthless disgusting "
                        + "racial racist sexism sexist misogyny misogynist homophobia homophobic "
                        + "slur slurs derogatory dehumanize dehumanizing");

        m.put("SADNESS",
                "sad sadness unhappy depress depressed depression hopeless helpless empty numb "
                        + "cry crying tears grief grieving mourn mourning heartbroken lonely loneliness "
                        + "tired fatigue exhausted weary miserable despair down bad day "
                        + "loss lost regret regrets regretful");

        m.put("ANXIETY_STRESS",
                "anxiety anxious panic panicking panicattack stressed stress overwhelm overwhelmed "
                        + "worry worried worrying fear fearful nervous jittery uneasy "
                        + "ruminate rumination intrusive insomnia sleepless sleep "
                        + "burnout burnedout tension tense");

        m.put("SELF_HARM_RISK",
                "suicide suicidal selfharm self-harm killmyself hurtmyself overdose");

        m.put("SUBSTANCE_USE",
                "alcohol drunk drinking blackout hangover vodka whiskey beer wine "
                        + "weed cannabis marijuana thc high stoned "
                        + "cocaine meth heroin opioid fentanyl pills xanax benzo "
                        + "addiction addicted relapse sober sobriety withdrawal");

        m.put("TOXIC_LANGUAGE",
                "fuck fucking shit shitty asshole bastard bitch damn crap "
                        + "screw scumbag prick douche");

        m.put("SOCIAL_WITHDRAWAL",
                "alone isolated isolation withdrawn avoid avoiding "
                        + "no friends friendless nobody cares "
                        + "stay home never go out disconnected");

        m.put("POSITIVITY",
                "happy happiness grateful gratitude thankful appreciate excited hopeful optimism "
                        + "love loved loving kind kindness supportive support "
                        + "proud proudofyou success win winning progress improved better");

        m.put("WORK_STUDY_PRESSURE",
                "exam exams studying study deadline deadlines assignment assignments "
                        + "work job boss manager fired layoff "
                        + "grades gpa fail failing burnout overworked");

        m.put("HARASSMENT_TARGETING",
                "stalk stalking doxx doxxing target targeted harassment harassing "
                        + "threat threatened intimidate intimidation "
                        + "reporting massreport brigade brigading");

        m.put("GRIEF_LOSS",
                "funeral died death passedaway rip condolence condolences "
                        + "miss you missing you "
                        + "grief grieving mourning memorial");

        m.put("MENTAL_HEALTH_GENERAL",
                "mental health therapy therapist counselor counselling psychiatry psychologist "
                        + "diagnosis diagnosed meds medication antidepressant ssri "
                        + "bipolar schizophrenia ocd adhd ptsd trauma "
                        + "episode manic depressive");

        return m;
    }
}
