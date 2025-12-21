package com.ttu_elite.seraph.Repositories;

import com.ttu_elite.seraph.Entities.RedditPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RedditPostRepository extends JpaRepository<RedditPost, Long> {
    boolean existsByRedditPostId(String redditPostId);

}
