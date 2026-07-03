package com.probeflow.testagent.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LongTermMemoryRepository extends JpaRepository<LongTermMemory, String> {

    List<LongTermMemory> findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus status);
}
