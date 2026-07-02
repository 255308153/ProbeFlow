package com.probeflow.testagent.task;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanStepRepository extends JpaRepository<PlanStep, String> {
}
