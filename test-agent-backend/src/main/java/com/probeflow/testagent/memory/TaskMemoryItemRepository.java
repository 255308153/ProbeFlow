package com.probeflow.testagent.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskMemoryItemRepository extends JpaRepository<TaskMemoryItem, String> {

    List<TaskMemoryItem> findAllByTaskIdAndStatusOrderByCreatedAtAscMemoryIdAsc(String taskId, MemoryStatus status);
}
