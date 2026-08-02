package io.evotrace.server.ai.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, Long> {

    Optional<AiModelConfig> findByName(String name);

    List<AiModelConfig> findByEnabledTrueOrderByIsDefaultDesc();

    Optional<AiModelConfig> findByIsDefaultTrue();

    Optional<AiModelConfig> findByEnabledTrueAndIsDefaultTrue();

    @Modifying
    @Query("UPDATE AiModelConfig c SET c.isDefault = false WHERE c.isDefault = true")
    void clearAllDefaults();
}
