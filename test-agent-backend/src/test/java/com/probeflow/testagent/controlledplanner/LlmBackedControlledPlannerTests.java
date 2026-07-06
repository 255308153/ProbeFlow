package com.probeflow.testagent.controlledplanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.llm.LlmCallLogRepository;
import com.probeflow.testagent.llm.LlmProvider;
import com.probeflow.testagent.llm.LlmRequest;
import com.probeflow.testagent.llm.LlmResponse;
import com.probeflow.testagent.llm.LlmTokenUsage;
import com.probeflow.testagent.llm.PromptTemplateRegistry;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LlmBackedControlledPlannerTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Autowired
    private LlmBackedControlledPlanner planner;

    @Autowired
    private PromptTemplateRegistry templates;

    @Autowired
    private LlmCallLogRepository logs;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private LlmProvider provider;

    @Test
    void plannerPromptTemplateIsRegisteredWithPlannerPurposeAndVersion() {
        var template = templates.findById(LlmBackedControlledPlanner.TEMPLATE_ID).orElseThrow();

        assertThat(template.purpose()).isEqualTo(LlmBackedControlledPlanner.PURPOSE);
        assertThat(template.version()).isEqualTo("v1");
        assertThat(template.requiredVariables()).contains(
            "taskState",
            "currentPhase",
            "workflowMode",
            "lastStepOutcome",
            "contextSummary",
            "availableTools",
            "constraints"
        );
    }

    @Test
    void llmBackedPlannerCallsThroughLlmApplicationServiceAndLinksAuditLog() {
        when(provider.providerName()).thenReturn("fake");
        when(provider.generate(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            return new LlmResponse(
                "fake",
                request.model(),
                "{\"action\":\"CONTINUE\",\"reasoning\":\"continue from fake llm\",\"confidence\":0.72,\"riskLevel\":\"LOW\"}",
                LlmTokenUsage.of(20, 6),
                "trace-planner",
                true,
                Map.of("purpose", request.purpose())
            );
        });

        var decision = planner.plan(input());
        entityManager.flush();
        entityManager.clear();

        assertThat(decision.action()).isEqualTo(PlannerAction.CONTINUE);
        assertThat(decision.sourceLlmCallId()).isNotBlank();
        assertThat(decision.fakeProvider()).isTrue();
        assertThat(decision.reasoning()).isEqualTo("continue from fake llm");
        assertThat(decision.confidence()).isEqualTo(0.72d);
        verify(provider).generate(any(LlmRequest.class));

        var log = logs.findById(decision.sourceLlmCallId()).orElseThrow();
        assertThat(log.getPurpose()).isEqualTo("CONTROLLED_PLANNER");
        assertThat(log.getTemplateId()).isEqualTo("v2.controlled-planner.v1");
        assertThat(log.getTemplateVersion()).isEqualTo("v1");
        assertThat(log.getTaskId()).isEqualTo("task-llm-planner");
        assertThat(log.isFakeProvider()).isTrue();
        assertThat(log.getPromptSummary())
            .contains("task-llm-planner")
            .contains("Planner-safe tools")
            .contains("knowledge.retrieve-context");
    }

    @Test
    void llmBackedPlannerReturnsSafeFailedDecisionWhenModelOutputIsFreeText() {
        when(provider.providerName()).thenReturn("fake");
        when(provider.generate(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            return new LlmResponse(
                "fake",
                request.model(),
                "Just continue the current plan.",
                LlmTokenUsage.of(18, 5),
                "trace-free-text",
                true,
                Map.of("purpose", request.purpose())
            );
        });

        var decision = planner.plan(input());

        assertThat(decision.failed()).isTrue();
        assertThat(decision.action()).isEqualTo(PlannerAction.STOP);
        assertThat(decision.blockers()).containsExactly("NON_STRUCTURED_OUTPUT");
        assertThat(decision.sourceLlmCallId()).isNotBlank();
        assertThat(decision.fakeProvider()).isTrue();
    }

    @Test
    void promptVariablesAreCompactAndOnlyContainPlannerFacingData() {
        var variables = planner.promptVariables(input());

        assertThat(variables)
            .containsKeys(
                "taskState",
                "currentPhase",
                "workflowMode",
                "lastStepOutcome",
                "contextSummary",
                "availableTools",
                "constraints"
            );
        assertThat(variables.toString())
            .contains("task-llm-planner")
            .contains("knowledge.retrieve-context")
            .doesNotContain("ApplicationService")
            .doesNotContain("Repository")
            .doesNotContain("EntityManager")
            .doesNotContain("HttpClient")
            .doesNotContain("com.probeflow");
    }

    @Test
    void llmBackedPlannerDoesNotDirectlyUseProviderSdkOrLlmProvider() throws Exception {
        var source = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/controlledplanner/LlmBackedControlledPlanner.java"
        ));

        assertThat(source)
            .contains("LlmApplicationService")
            .doesNotContain("LlmProvider")
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("ChatModel")
            .doesNotContain("java.net.http.HttpClient");
    }

    private PlannerInput input() {
        var registry = new ToolContractRegistry();
        var policyService = new AgentPolicyService(registry);
        var inputFactory = new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService));
        return inputFactory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-llm-planner", "API_TEST", "ANALYZING", "step-llm", List.of("RETRIEVE_KNOWLEDGE")),
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING),
            null,
            ContextBundleSummary.of("Use citations rather than raw documents.", List.of("wiki/auth"), 1, 1, 300),
            List.of(PlannerConstraint.of("SUGGEST_ONLY", "Planner must not execute or mutate state."))
        ));
    }
}
