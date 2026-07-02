package com.probeflow.testagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskMemoryItemRepository extends JpaRepository<TaskMemoryItem, String> {
}
