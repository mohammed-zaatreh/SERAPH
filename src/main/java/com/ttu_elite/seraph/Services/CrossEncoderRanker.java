package com.ttu_elite.seraph.Services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CrossEncoderRanker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderRanker.class);
    private final RestClient restClient = RestClient.create();

    // Specific Cross-Encoder Model hosted on HuggingFace
    private static final String API_URL = "https://api-inference.huggingface.co/models/cross-encoder/ms-marco-MiniLM-L-6-v2";

    public void init() {
        // No heavy loading needed anymore!
        log.info("Cross-Encoder configured to use HuggingFace Cloud API.");
    }

    public Map<String, Double> rank(String postText, Map<String, String> categoryDefinitions) {
        Map<String, Double> results = new ConcurrentHashMap<>();

        if (postText == null || postText.isBlank()) {
            return results;
        }

        for (var entry : categoryDefinitions.entrySet()) {
            String catKey = entry.getKey();
            String catDef = entry.getValue();

            try {
                // We construct the input payload expected by HF API
                // Format: {"inputs": {"source_sentence": "...", "sentences": ["..."]}}
                // OR for this specific model: {"inputs": {"text": "...", "text_pair": "..."}}

                Map<String, Object> payload = Map.of(
                        "inputs", Map.of(
                                "source_sentence", postText,
                                "sentences", List.of(catDef)
                        )
                );

                // Send Request


            } catch (Exception e) {
                log.error("API Call Failed for category " + catKey + ": " + e.getMessage());
                // Fallback: If API fails/throttles, assume 0
                results.put(catKey, 0.0);
            }
        }
        return results;
    }
}