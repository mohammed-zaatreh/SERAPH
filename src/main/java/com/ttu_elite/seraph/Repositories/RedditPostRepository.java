package com.ttu_elite.seraph.Repositories;

import com.ttu_elite.seraph.Entities.RedditPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RedditPostRepository extends JpaRepository<RedditPost, Long> {
    boolean existsByRedditPostId(String redditPostId);
    List<RedditPost> findAllByUsernameOrderByCreatedUtcDesc(String username);
    List<RedditPost> findAllByAnalysisId(Long analysisId);
    

}
