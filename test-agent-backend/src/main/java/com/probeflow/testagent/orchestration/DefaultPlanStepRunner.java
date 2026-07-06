package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.Task;
import org.springframework.stereotype.Component;

@Component
public class DefaultPlanStepRunner implements PlanStepRunner {

    @Override
    public StepOutcome run(Task task, PlanStep step) {
        return StepOutcome.succeeded();
    }
}
