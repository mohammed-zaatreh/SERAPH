package com.ttu_elite.seraph.Entities;


import jakarta.persistence.*;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Entity
@Data                      // <--- Adds Getters, Setters, toString, etc.
@NoArgsConstructor         // <--- Required by JPA
@AllArgsConstructor        // <--- Handy for one-line creation
@Builder                   // <--- clean object creation syntax
public class RedditPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LINK TO PARENT SNAPSHOT
    @Column(nullable = false)
    private Long analysisId; // <--- ADD THIS!
    private String username;
    private String redditPostId;

    @Column(length = 500)
    private String permalink;

    @Column(length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Long createdUtc;

    @Column(columnDefinition = "TEXT")
    private String tokens;



    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRedditPostId() {
        return redditPostId;
    }

    public void setRedditPostId(String redditPostId) {
        this.redditPostId = redditPostId;
    }

    public String getPermalink() {
        return permalink;
    }

    public void setPermalink(String permalink) {
        this.permalink = permalink;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getCreatedUtc() {
        return createdUtc;
    }

    public void setCreatedUtc(Long createdUtc) {
        this.createdUtc = createdUtc;
    }

    public String getTokens() {
        return tokens;
    }

    public void setTokens(String tokens) {
        this.tokens = tokens;
    }

}
