package com.ttu_elite.seraph.dto;

import com.ttu_elite.seraph.Entities.ProfileAnalysis;
import com.ttu_elite.seraph.Entities.RedditPost;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AnalysisResult {
    private ProfileAnalysis summary;
    private List<RedditPost> posts;
}