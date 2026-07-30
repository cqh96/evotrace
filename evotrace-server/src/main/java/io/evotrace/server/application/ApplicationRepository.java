package io.evotrace.server.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByProjectId(Long projectId);

    Optional<Application> findByProjectIdAndAppKey(Long projectId, String appKey);
}
