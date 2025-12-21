package com.ttu_elite.seraph.Services;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class TfidfRanker {

    /**
     * Returns: category -> list of scores for each post index (same order as postTokens list).
     * Scoring uses TF-IDF cosine similarity (vector space model).
     */
    public Map<String, List<Double>> scoreAllPostsAgainstCategories(
            List<List<String>> postTokens,
            Map<String, List<String>> categoryTokens
    ) {
        int N = postTokens.size();
        if (N == 0) return Map.of();

        // Document frequency (DF)
        Map<String, Integer> df = new HashMap<>();
        for (List<String> doc : postTokens) {
            Set<String> uniq = new HashSet<>(doc);
            for (String t : uniq) df.merge(t, 1, Integer::sum);
        }

        // IDF
        Map<String, Double> idf = new HashMap<>();
        for (var e : df.entrySet()) {
            // smooth IDF
            double idfi = Math.log((N + 1.0) / (e.getValue() + 1.0)) + 1.0;
            idf.put(e.getKey(), idfi);
        }

        // Doc vectors
        List<Map<String, Double>> docVecs = new ArrayList<>(N);
        for (List<String> tokens : postTokens) {
            docVecs.add(tfidf(tokens, idf));
        }

        // Category vectors
        Map<String, Map<String, Double>> catVecs = new LinkedHashMap<>();
        for (var e : categoryTokens.entrySet()) {
            catVecs.put(e.getKey(), tfidf(e.getValue(), idf));
        }

        // Scores: category -> [score_i]
        Map<String, List<Double>> out = new LinkedHashMap<>();
        for (var cat : catVecs.entrySet()) {
            String category = cat.getKey();
            Map<String, Double> qVec = cat.getValue();

            List<Double> scores = new ArrayList<>(N);
            for (Map<String, Double> dVec : docVecs) {
                scores.add(cosine(dVec, qVec));
            }
            out.put(category, scores);
        }

        return out;
    }

    private Map<String, Double> tfidf(List<String> tokens, Map<String, Double> idf) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);

        Map<String, Double> vec = new HashMap<>();
        for (var e : tf.entrySet()) {
            Double idfi = idf.get(e.getKey());
            if (idfi == null) continue;
            vec.put(e.getKey(), e.getValue() * idfi);
        }
        return vec;
    }

    private double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        double dot = 0.0;
        for (var e : a.entrySet()) {
            Double bv = b.get(e.getKey());
            if (bv != null) dot += e.getValue() * bv;
        }

        double na = 0.0;
        for (double v : a.values()) na += v * v;

        double nb = 0.0;
        for (double v : b.values()) nb += v * v;

        if (na == 0.0 || nb == 0.0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
