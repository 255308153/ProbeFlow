package com.probeflow.testagent.executionrecord;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRecordRepository extends JpaRepository<ExecutionRecord, String> {

    List<ExecutionRecord> findAllByTaskIdOrderByCreatedAtAscExecutionIdAsc(String taskId);

    List<ExecutionRecord> findAllByTaskIdAndExecutionIdInOrderByCreatedAtAscExecutionIdAsc(
        String taskId,
        Collection<String> executionIds
    );
}
