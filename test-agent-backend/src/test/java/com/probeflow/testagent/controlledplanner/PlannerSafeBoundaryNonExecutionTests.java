package com.probeflow.testagent.controlledplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolView;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlannerSafeBoundaryNonExecutionTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PlannerInputFactory inputFactory = new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService));

    @Test
    void plannerInputOnlyContainsPlannerSafeToolViewsAndNoExecutionContracts() {
        var input = inputFactory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-boundary", "API_TEST", "ANALYZING", "step-current", List.of("RETRIEVE_KNOWLEDGE")),
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING),
            null,
            ContextBundleSummary.of("Use cited context only.", List.of("wiki/auth"), 1, 0, 300),
            List.of(PlannerConstraint.of("SUGGEST_ONLY", "Planner must only suggest a decision."))
        ));

        assertThat(input.availableTools()).isNotEmpty();
        assertThat(input.availableTools()).allSatisfy(tool -> assertThat(tool).isInstanceOf(PlannerSafeToolView.class));
        assertThat(input.availableTools().stream().map(Object::getClass).distinct())
            .containsExactly(PlannerSafeToolView.class);
        assertThat(List.of(PlannerInput.class.getRecordComponents()))
            .extracting(component -> component.getType().getName())
            .doesNotContain(
                "com.probeflow.testagent.agentpolicy.ToolContract",
                "com.probeflow.testagent.agentpolicy.ToolContractRegistry"
            );
    }

    @Test
    void plannerInputDoesNotExposeServiceRepositoryDatabaseHttpClientOrSpringBeanDetails() {
        var input = inputFactory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-safe-input", "API_TEST", "PENDING", null, List.of()),
            AgentPolicy.v2Phase2Default(),
            null,
            ContextBundleSummary.empty(),
            List.of()
        ));

        assertThat(input.toString())
            .doesNotContain("ApplicationService")
            .doesNotContain("Repository")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain("HttpClient")
            .doesNotContain("RestTemplate")
            .doesNotContain("Spring")
            .doesNotContain("Bean")
            .doesNotContain("com.probeflow");
    }

    @Test
    void controlledPlannerServiceOnlyDelegatesAndDoesNotExecuteOrMutateTaskState() throws Exception {
        var source = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/controlledplanner/ControlledPlannerService.java"
        ));

        assertThat(source).contains("planner.plan(input)");
        assertThat(source)
            .doesNotContain("TaskRepository")
            .doesNotContain("PlanStepRepository")
            .doesNotContain("ToolRouter")
            .doesNotContain("PlanStepRunner")
            .doesNotContain("save(")
            .doesNotContain("delete(")
            .doesNotContain("insert")
            .doesNotContain("reorder")
            .doesNotContain("Memory")
            .doesNotContain("Observation")
            .doesNotContain("ExecutionRecord")
            .doesNotContain("Report")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain("HttpClient")
            .doesNotContain("RestTemplate");
    }

    @Test
    void controlledPlannerPackageDoesNotDependOnStateWritingRepositoriesOrExecutionRunners() throws Exception {
        var sourceText = controlledPlannerSourceText();

        assertThat(sourceText)
            .doesNotContain("TaskRepository")
            .doesNotContain("PlanStepRepository")
            .doesNotContain("TaskMemoryItemRepository")
            .doesNotContain("LongTermMemoryRepository")
            .doesNotContain("ObservationRepository")
            .doesNotContain("ExecutionRecordRepository")
            .doesNotContain("ReportRepository")
            .doesNotContain("ToolRouter")
            .doesNotContain("PlanStepRunner")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain("RestTemplate")
            .doesNotContain("java.net.http.HttpClient");
    }

    @Test
    void realLlmProviderIsStillNotRequiredInTestProfile() throws Exception {
        var testProfile = Files.readString(PROJECT_ROOT.resolve("src/test/resources/application-test.yml"));

        assertThat(testProfile)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake");
    }

    private String controlledPlannerSourceText() throws Exception {
        try (var stream = Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/controlledplanner"))) {
            var files = stream
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
            var builder = new StringBuilder();
            for (var file : files) {
                builder.append(Files.readString(file)).append('\n');
            }
            return builder.toString();
        }
    }
}
