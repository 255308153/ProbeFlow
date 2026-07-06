package com.probeflow.testagent.agentpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerSafeToolCatalogServiceTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PlannerSafeToolCatalogService catalog = new PlannerSafeToolCatalogService(registry, policyService);

    @Test
    void plannerSafeViewExportsOnlyToolMetadataAndPolicyStatus() {
        var view = catalog.listForPlanner(AgentPolicy.v2Phase2Default()).stream()
            .filter(tool -> tool.name().equals("testcase.generate-drafts"))
            .findFirst()
            .orElseThrow();

        assertThat(view.name()).isEqualTo("testcase.generate-drafts");
        assertThat(view.capabilityGroup()).isEqualTo("TEST_CASE");
        assertThat(view.description()).contains("drafts");
        assertThat(view.inputSchema())
            .extracting(PlannerToolSchemaFieldView::name)
            .contains("taskId", "apiSpecId", "generationMode");
        assertThat(view.outputSummary()).contains("Review Gate");
        assertThat(view.riskLevel()).isEqualTo("MEDIUM");
        assertThat(view.executionMode()).isEqualTo("AUTO_ALLOWED");
        assertThat(view.preconditions()).contains("TASK_EXISTS", "API_SPEC_AVAILABLE");
        assertThat(view.policyStatus()).isEqualTo(ToolPolicyStatus.ALLOWED);
    }

    @Test
    void plannerSafeViewMarksAllowedHumanConfirmationAndBlockedPolicyStates() {
        var defaultViews = catalog.listForPlanner(AgentPolicy.v2Phase2Default());
        var restrictedViews = catalog.listForPlanner(AgentPolicy.v2Phase2Default()
            .withWhitelistedTools(Set.of(ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT)));

        assertThat(find(defaultViews, "knowledge.retrieve-context").policyStatus()).isEqualTo(ToolPolicyStatus.ALLOWED);
        assertThat(find(defaultViews, "http.execute-approved-case").policyStatus())
            .isEqualTo(ToolPolicyStatus.REQUIRES_HUMAN_CONFIRMATION);
        assertThat(find(restrictedViews, "report.generate-task").policyStatus()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(find(restrictedViews, "report.generate-task").policyReasonCode())
            .isEqualTo(ToolPolicyReasonCode.TOOL_NOT_WHITELISTED);
    }

    @Test
    void availablePlannerViewFiltersBlockedToolsButKeepsHumanConfirmationToolsVisible() {
        var policy = AgentPolicy.v2Phase2Default()
            .withWhitelistedTools(Set.of(
                ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT,
                ToolNames.HTTP_EXECUTE_APPROVED_CASE
            ));

        assertThat(catalog.listAvailableForPlanner(policy))
            .extracting(PlannerSafeToolView::name)
            .containsExactly("http.execute-approved-case", "knowledge.retrieve-context");
    }

    @Test
    void plannerSafeViewDoesNotExposeImplementationDetailsOrServiceReferences() {
        var rendered = catalog.listForPlanner(AgentPolicy.v2Phase2Default()).toString();

        assertThat(rendered)
            .doesNotContain("ApplicationService")
            .doesNotContain("Repository")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain("HttpClient")
            .doesNotContain("Spring")
            .doesNotContain("Bean")
            .doesNotContain("com.probeflow");
    }

    @Test
    void plannerSafeViewDoesNotExecuteAnyToolOrRequireRuntimeInputs() {
        var views = catalog.listForPlanner(AgentPolicy.v2Phase2Default());

        assertThat(views).hasSize(registry.listAll().size());
        assertThat(views)
            .extracting(PlannerSafeToolView::name)
            .contains("api.analyze-source", "report.generate-task");
    }

    private PlannerSafeToolView find(java.util.List<PlannerSafeToolView> views, String name) {
        return views.stream()
            .filter(view -> view.name().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
