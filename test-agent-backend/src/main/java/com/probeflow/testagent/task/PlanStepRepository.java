package com.probeflow.testagent.task;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanStepRepository extends JpaRepository<PlanStep, String> {

    List<PlanStep> findByTaskIdOrderByStepOrderAsc(String taskId);
}
