package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.Task;

public interface PlanStepRunner {

    StepOutcome run(Task task, PlanStep step);
}
