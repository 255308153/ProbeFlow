package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TaskTemplateRegistry {

    public TaskTemplate templateFor(TaskType taskType, TaskSourceType sourceType, PromotionMode promotionMode) {
        if (taskType == TaskType.API_TEST) {
            return apiTestTemplate(promotionMode);
        }
        if (taskType == TaskType.REGRESSION) {
            return regressionTemplate(promotionMode);
        }
        throw new IllegalArgumentException("Unsupported Phase 9 task type: " + taskType);
    }

    private TaskTemplate apiTestTemplate(PromotionMode promotionMode) {
        var mode = normalizePromotionMode(promotionMode);
        return new TaskTemplate(
            "API_TEST_" + mode.name() + "_V1",
            TaskType.API_TEST,
            mode,
            List.of(
                new TaskPlanStepTemplate(1, PlanStepType.ANALYZE_CODE_API, "Analyze source material into API specs"),
                new TaskPlanStepTemplate(2, PlanStepType.RETRIEVE_KNOWLEDGE, "Prepare knowledge and task context"),
                new TaskPlanStepTemplate(3, PlanStepType.GENERATE_CASES, "Generate API test case drafts"),
                new TaskPlanStepTemplate(4, PlanStepType.EXECUTE_BATCH, "Execute selected HTTP API cases"),
                new TaskPlanStepTemplate(5, PlanStepType.ANALYZE_FAILURE, "Analyze execution failures where needed"),
                new TaskPlanStepTemplate(6, PlanStepType.GENERATE_REPORT, "Generate final task report")
            )
        );
    }

    private TaskTemplate regressionTemplate(PromotionMode promotionMode) {
        var mode = normalizePromotionMode(promotionMode);
        return new TaskTemplate(
            "REGRESSION_" + mode.name() + "_V1",
            TaskType.REGRESSION,
            mode,
            List.of(
                new TaskPlanStepTemplate(1, PlanStepType.EXECUTE_BATCH, "Execute selected regression cases"),
                new TaskPlanStepTemplate(2, PlanStepType.ANALYZE_FAILURE, "Analyze regression failures where needed"),
                new TaskPlanStepTemplate(3, PlanStepType.GENERATE_REPORT, "Generate regression task report")
            )
        );
    }

    private PromotionMode normalizePromotionMode(PromotionMode promotionMode) {
        return promotionMode == null ? PromotionMode.AUTO : promotionMode;
    }
}
