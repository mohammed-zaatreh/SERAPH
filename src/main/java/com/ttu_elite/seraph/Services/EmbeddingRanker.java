package com.ttu_elite.seraph.Services;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmbeddingRanker {

    private Predictor<String, float[]> predictor;
    private final Map<String, float[]> categoryVectors = new ConcurrentHashMap<>();

    // UPDATED ANCHORS: Added FUNCTIONAL_BASELINE to explicitly detect normal content
    private static final Map<String, String> ANCHORS = Map.of(
            "FUNCTIONAL_BASELINE", "Content about daily life, hobbies, work, technology, news, or casual conversation without strong emotion.",
            "SADNESS", "I feel overwhelmed with grief, hopelessness, and deep emotional pain that will not go away.",
            "HOSTILITY", "I hate everyone and want to violently hurt others or destroy things out of anger.",
            "ANXIETY_STRESS", "I am having a panic attack and cannot breathe because the pressure is too much.",
            "SELF_HARM_RISK", "I want to end my life and commit suicide because I cannot take this anymore."
    );

    @PostConstruct
    public void init() throws Exception {
        // Load Model (all-MiniLM-L6-v2 is standard for this)
        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2")
                .optEngine("PyTorch")
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                .build();

        ZooModel<String, float[]> model = criteria.loadModel();
        this.predictor = model.newPredictor();

        // Pre-compute Category Vectors (Done once at startup)
        for (var entry : ANCHORS.entrySet()) {
            categoryVectors.put(entry.getKey(), predictor.predict(entry.getValue()));
        }
    }

    public Map<String, List<Double>> scorePosts(List<String> postTexts) {
        Map<String, List<Double>> results = new LinkedHashMap<>();

        // Initialize lists
        for (String cat : ANCHORS.keySet()) results.put(cat, new ArrayList<>());

        for (String text : postTexts) {
            try {
                float[] postVec = predictor.predict(text); // Vectorize post

                for (String cat : ANCHORS.keySet()) {
                    float[] catVec = categoryVectors.get(cat);
                    double sim = cosineSimilarity(postVec, catVec);
                    results.get(cat).add(Math.max(0.0, sim)); // Clamp negative cosine
                }
            } catch (Exception e) {
                // Handle error (log it, add 0.0)
                for (String cat : ANCHORS.keySet()) results.get(cat).add(0.0);
            }
        }
        return results;
    }

    private double cosineSimilarity(float[] A, float[] B) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < A.length; i++) {
            dot += A[i] * B[i];
            normA += A[i] * A[i];
            normB += B[i] * B[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}