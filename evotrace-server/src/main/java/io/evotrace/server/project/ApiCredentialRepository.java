package io.evotrace.server.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {

    Optional<ApiCredential> findByApiKey(String apiKey);

    List<ApiCredential> findByProjectIdAndStatus(Long projectId, String status);
}
