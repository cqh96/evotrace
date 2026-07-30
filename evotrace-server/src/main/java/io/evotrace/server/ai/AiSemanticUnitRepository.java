package io.evotrace.server.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiSemanticUnitRepository extends JpaRepository<AiSemanticUnit, Long> {

    List<AiSemanticUnit> findByTargetTypeAndTargetId(String targetType, String targetId);

    Optional<AiSemanticUnit> findByTargetTypeAndTargetIdAndKind(String targetType, String targetId, String kind);
}
