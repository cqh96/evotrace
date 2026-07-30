package io.evotrace.server.change;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChangeEventRepository extends JpaRepository<ChangeEvent, Long> {

    Optional<ChangeEvent> findByEventId(String eventId);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
