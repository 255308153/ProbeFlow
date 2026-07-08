package com.probeflow.testagent.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LongTermMemoryRepository extends JpaRepository<LongTermMemory, String>, LongTermMemoryVectorRepository {

    List<LongTermMemory> findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus status);
}
