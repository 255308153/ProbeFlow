package com.probeflow.testagent.taskcaseexecution;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCaseExecutionRepository extends JpaRepository<TaskCaseExecution, String> {

    Optional<TaskCaseExecution> findFirstByTaskIdAndCaseId(String taskId, String caseId);
}
