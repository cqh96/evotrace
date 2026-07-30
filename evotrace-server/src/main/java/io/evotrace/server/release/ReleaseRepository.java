package io.evotrace.server.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReleaseRepository extends JpaRepository<Release, Long> {

    List<Release> findByProjectIdAndAppId(Long projectId, Long appId);

    Optional<Release> findByProjectIdAndAppIdAndVersion(Long projectId, Long appId, String version);

    Optional<Release> findByProjectIdAndVersion(Long projectId, String version);
}
