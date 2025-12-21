package com.ttu_elite.seraph.Services;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;


@Component
public class TextPreprocessor {


    private final Analyzer analyzer = new EnglishAnalyzer(); // includes stemming + stopword removal

    public List<String> preprocess(String text) {
        if (text == null) return List.of();
        try (TokenStream ts = analyzer.tokenStream(null, new StringReader(text))) {
            List<String> tokens = new ArrayList<>();
            ts.reset();
            var attr = ts.addAttribute(CharTermAttribute.class);
            while (ts.incrementToken()) tokens.add(attr.toString());
            ts.end();
            return tokens;
        } catch (Exception e) {
            throw new RuntimeException("Preprocess failed", e);
        }
    }

}
