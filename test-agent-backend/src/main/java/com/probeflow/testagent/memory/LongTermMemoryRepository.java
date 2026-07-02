package com.probeflow.testagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LongTermMemoryRepository extends JpaRepository<LongTermMemory, String> {
}
