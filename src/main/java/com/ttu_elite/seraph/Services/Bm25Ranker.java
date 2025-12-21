package com.ttu_elite.seraph.Services;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Component
public class Bm25Ranker {

    // Standard defaults
    private final double k1 = 1.5;
    private final double b = 0.75;

    /**
     * Compute BM25 between a document token list and a query token set.
     * Requires corpus stats: df per term, N, avgdl.
     */
    public double score(List<String> docTokens,
                        Set<String> queryTokens,
                        Map<String, Integer> df,
                        int N,
                        double avgdl) {

        if (docTokens == null || docTokens.isEmpty() || queryTokens == null || queryTokens.isEmpty()) return 0.0;

        int dl = docTokens.size();

        // term frequency in doc
        Map<String, Integer> tf = new HashMap<>();
        for (String t : docTokens) tf.merge(t, 1, Integer::sum);

        double score = 0.0;

        for (String term : queryTokens) {
            Integer f = tf.get(term);
            if (f == null || f == 0) continue;

            int dft = df.getOrDefault(term, 0);
            double idf = Math.log(1.0 + ( (N - dft + 0.5) / (dft + 0.5) )); // BM25 idf (common variant)

            double denom = f + k1 * (1.0 - b + b * (dl / avgdl));
            double termScore = idf * (f * (k1 + 1.0)) / denom;

            score += termScore;
        }

        return score;
    }

    public static double safeAvgdl(int totalLen, int N) {
        return (N <= 0) ? 1.0 : Math.max(1.0, (double) totalLen / (double) N);
    }
}
