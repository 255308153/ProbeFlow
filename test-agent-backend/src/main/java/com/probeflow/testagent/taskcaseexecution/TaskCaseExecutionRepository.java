package com.probeflow.testagent.taskcaseexecution;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCaseExecutionRepository extends JpaRepository<TaskCaseExecution, String> {

    Optional<TaskCaseExecution> findFirstByTaskIdAndCaseId(String taskId, String caseId);

    List<TaskCaseExecution> findAllByTaskIdOrderByCaseIdAscIdAsc(String taskId);
}
