package com.ttu_elite.seraph.Repositories;


import com.ttu_elite.seraph.Entities.ProfileAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProfileAnalysisRepository extends JpaRepository<ProfileAnalysis, Long> {
    Optional<ProfileAnalysis> findTopByUsernameOrderByCreatedAtDesc(String username);
    Optional<ProfileAnalysis> findByUsername(String username);
void deleteByUsername(String username);
    boolean existsByUsername(String username);
    // Fetch all snapshots for a user, newest first
    List<ProfileAnalysis> findAllByUsernameOrderByCreatedAtDesc(String username);

    @Query("SELECT p FROM ProfileAnalysis p WHERE p.createdAt IN " +
            "(SELECT MAX(p2.createdAt) FROM ProfileAnalysis p2 GROUP BY p2.username) " +
            "ORDER BY p.createdAt DESC")
    List<ProfileAnalysis> findLatestProfiles();

    // Fetch all history for a specific user (for the Timeline View)

}