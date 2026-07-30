package io.evotrace.server.change;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeFileRepository extends JpaRepository<ChangeFile, Long> {

    List<ChangeFile> findByEventId(String eventId);
}
