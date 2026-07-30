package io.evotrace.server.iteration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IterationRepository extends JpaRepository<Iteration, Long> {

    List<Iteration> findByProjectId(Long projectId);

    Optional<Iteration> findByProjectIdAndSourceAndExternalKey(Long projectId, String source, String externalKey);
}
