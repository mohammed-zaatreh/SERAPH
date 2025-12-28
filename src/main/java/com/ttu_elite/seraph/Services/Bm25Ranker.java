package com.ttu_elite.seraph.Services;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class Bm25Ranker {

    // Define KEYWORDS for each category (Explicit triggers)
    // These act as the "Documents" we compare against
    private static final Map<String, List<String>> KEYWORD_CORPUS = Map.of(
            "SADNESS", List.of("sad", "crying", "grief", "depressed", "lonely", "hopeless", "misery", "pain", "tears", "empty"),
            "HOSTILITY", List.of("hate", "kill", "angry", "punch", "stupid", "idiot", "fight", "destroy", "enemy", "rage"),
            "ANXIETY_STRESS", List.of("panic", "anxiety", "scared", "nervous", "breathe", "pressure", "fail", "worry", "stress", "attack"),
            "SELF_HARM_RISK", List.of("suicide", "end", "die", "kill", "goodbye", "overdose", "cutting", "hang", "rope", "gun"),
            "FUNCTIONAL_BASELINE", List.of("work", "hobby", "job", "game", "movie", "book", "code", "run", "gym", "cook", "friend", "happy", "cool")
    );

    // BM25 Constants (Standard Tuning)
    private static final double k1 = 1.5;
    private static final double b = 0.75;

    public Map<String, List<Double>> scorePosts(List<String> postTexts) {
        Map<String, List<Double>> results = new LinkedHashMap<>();
        for (String cat : KEYWORD_CORPUS.keySet()) results.put(cat, new ArrayList<>());

        // Pre-compute average doc length (avgdl) for this batch
        double avgdl = postTexts.stream().mapToInt(this::countWords).average().orElse(1.0);

        // 1. Score each post against each Category
        for (String text : postTexts) {
            List<String> postTokens = tokenize(text);
            int docLen = postTokens.size();

            for (String cat : KEYWORD_CORPUS.keySet()) {
                double score = 0.0;
                List<String> keywords = KEYWORD_CORPUS.get(cat);

                // For every keyword in the category, run BM25 formula against the post
                for (String term : keywords) {
                    long tf = postTokens.stream().filter(t -> t.equals(term)).count();

                    // Inverse Document Frequency (Simplified for single-doc query context)
                    // We treat the "Keyword List" as the corpus. If the term is rare, it's weighted higher.
                    // Ideally IDF is computed over a large corpus, but for this 'Sidecar', we assume standard weight 1.0
                    double idf = 1.0;

                    double numerator = tf * (k1 + 1);
                    double denominator = tf + k1 * (1 - b + b * (docLen / avgdl));

                    score += idf * (numerator / denominator);
                }

                // Normalization: Clamp score to roughly 0.0 - 1.0 range for mixing
                // BM25 is unbounded, so we use a simple sigmoid-like clamp or min-max if needed.
                // For simplicity here, we divide by a factor to match the 0-1 scale of the Neural model.
                double normalizedScore = Math.min(score / 5.0, 1.0);

                results.get(cat).add(normalizedScore);
            }
        }
        return results;
    }

    private List<String> tokenize(String text) {
        if (text == null) return Collections.emptyList();
        // Simple tokenizer: Lowercase, remove punctuation, split by space
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-zA-Z ]", "").split("\\s+"))
                .filter(s -> s.length() > 2) // Ignore tiny words like "is", "a"
                .collect(Collectors.toList());
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.split("\\s+").length;
    }
}