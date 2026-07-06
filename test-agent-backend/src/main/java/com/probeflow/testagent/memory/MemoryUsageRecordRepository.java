package com.probeflow.testagent.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryUsageRecordRepository extends JpaRepository<MemoryUsageRecord, String> {

    List<MemoryUsageRecord> findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(String taskId);

    List<MemoryUsageRecord> findAllByMemoryIdOrderByCreatedAtAscUsageIdAsc(String memoryId);
}
