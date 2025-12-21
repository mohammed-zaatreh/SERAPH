package com.ttu_elite.seraph.Entities;

import jakarta.persistence.*;


import java.time.Instant;

@Entity
public class ProfileAnalysis {

    @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable=false)
        private String platform; // "reddit"

        @Column(nullable=false)
        private String username;

        @Column(nullable=false)
        private Integer postCount;

        @Column(nullable=false)
        private String topCategoryOverall;

        @Column(nullable=false)
        private Double confidence;

        @Lob
        @Column(columnDefinition = "TEXT")
        private String profileTotalsJson;

        @Lob
        @Column(columnDefinition = "TEXT")
        private String profilePercentagesJson;

        @Column(nullable=false)
        private Instant createdAt = Instant.now();



    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Integer getPostCount() {
        return postCount;
    }

    public void setPostCount(Integer postCount) {
        this.postCount = postCount;
    }

    public String getTopCategoryOverall() {
        return topCategoryOverall;
    }

    public void setTopCategoryOverall(String topCategoryOverall) {
        this.topCategoryOverall = topCategoryOverall;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getProfileTotalsJson() {
        return profileTotalsJson;
    }

    public void setProfileTotalsJson(String profileTotalsJson) {
        this.profileTotalsJson = profileTotalsJson;
    }

    public String getProfilePercentagesJson() {
        return profilePercentagesJson;
    }

    public void setProfilePercentagesJson(String profilePercentagesJson) {
        this.profilePercentagesJson = profilePercentagesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    }

