package com.ttu_elite.seraph.Repositories;


import com.ttu_elite.seraph.Entities.ProfileAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProfileAnalysisRepository extends JpaRepository<ProfileAnalysis, Long> {
    Optional<ProfileAnalysis> findTopByPlatformAndUsernameOrderByCreatedAtDesc(String platform, String username);
    Optional<ProfileAnalysis> findByUsername(String username);
void deleteByUsername(String username);
    boolean existsByUsername(String username);

}