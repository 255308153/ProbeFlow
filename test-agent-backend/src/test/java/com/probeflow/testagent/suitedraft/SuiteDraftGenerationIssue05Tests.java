package com.probeflow.testagent.suitedraft;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowCandidate;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryBlocker;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryEvidence;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryStep;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowEvidenceSource;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowOperationKind;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowSourceCoverage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteDraftGenerationIssue05Tests {

    @Test
    void producerAfterConsumerBlocksReadyDraft() {
        var result = generate(
            candidate(List.of(
                step("query-order", 1, "api-order-query", BusinessFlowOperationKind.QUERY, HttpMethod.GET, "/api/orders/{orderId}", false),
                step("create-order", 2, "api-order-create", BusinessFlowOperationKind.CREATE, HttpMethod.POST, "/api/orders", true)
            ), false, List.of()),
            List.of(hint("hint-future-order", "orderId", "create-order", "query-order", "$.data.orderId", "orderId", 0.88, Map.of()))
        );

        assertThat(result.status()).isEqualTo(SuiteDraftGenerationStatus.COMPLETED);
        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(result.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .contains("PRODUCER_NOT_BEFORE_CONSUMER");
        assertThat(result.diagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.affectedSteps()).contains("create-order", "query-order");
                assertThat(diagnostic.affectedDependencyId()).isEqualTo("dep-create-order-to-query-order-orderId");
                assertThat(diagnostic.severity()).isEqualTo("ERROR");
                assertThat(diagnostic.recommendedAction()).isNotBlank();
            });
        assertThat(SuiteDraftGenerationIssue01Tests.step(result, "query-order").readinessStatus())
            .isEqualTo(SuiteReadinessStatus.BLOCKED);
    }

    @Test
    void missingExtractRuleFieldsBlockDraft() {
        var result = generate(
            normalCandidate(false, List.of()),
            List.of(
                hint("hint-missing-source", "externalOrderId", "create-order", "query-order", "", "externalOrderId", 0.88, Map.of()),
                hint("hint-missing-target", "", "create-order", "query-order", "$.data.externalOrderId", "", 0.88, Map.of())
            )
        );

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(result.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .contains("MISSING_EXTRACT_RULE_SOURCE_PATH", "MISSING_EXTRACT_RULE_TARGET_KEY");
        assertThat(result.diagnostics())
            .allSatisfy(diagnostic -> {
                assertThat(diagnostic.reason()).isNotBlank();
                assertThat(diagnostic.recommendedAction()).isNotBlank();
            });
    }

    @Test
    void invalidScopedReferenceBlocksReadyDraft() {
        var result = generate(
            normalCandidate(false, List.of()),
            List.of(hint(
                "hint-invalid-reference",
                "externalOrderId",
                "create-order",
                "query-order",
                "$.data.externalOrderId",
                "externalOrderId",
                0.88,
                Map.of("referenceExpressionOverride", "${externalOrderId}")
            ))
        );

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(result.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .contains("INVALID_VARIABLE_REFERENCE");
        assertThat(SuiteDraftGenerationIssue01Tests.step(result, "query-order").requestTemplate().toString())
            .contains("${externalOrderId}");
    }

    @Test
    void unpairedVariableReferenceIsBlockedByReadinessValidator() {
        var request = request(
            normalCandidate(false, List.of()),
            List.of(hint("hint-valid", "externalOrderId", "create-order", "query-order", "$.data.externalOrderId", "externalOrderId", 0.88, Map.of()))
        );
        var result = new SuiteDraftGenerationService().generate(request);
        var mutatedSteps = result.draft().steps().stream()
            .map(step -> step.stepId().equals("query-order") ? withUnpairedReference(step) : step)
            .toList();

        var validation = new SuiteReadinessValidator()
            .validate(request, mutatedSteps, result.dependencyLinks(), List.of());

        assertThat(validation.status()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(validation.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .contains("UNPAIRED_VARIABLE_REFERENCE");
    }

    @Test
    void duplicateSuiteTargetKeyRequiresReview() {
        var result = generate(
            candidate(List.of(
                step("authenticate-user", 1, "api-auth-login", BusinessFlowOperationKind.AUTHENTICATE, HttpMethod.POST, "/api/auth/login", true),
                step("create-order", 2, "api-order-create", BusinessFlowOperationKind.CREATE, HttpMethod.POST, "/api/orders", true),
                step("query-order", 3, "api-order-query", BusinessFlowOperationKind.QUERY, HttpMethod.GET, "/api/orders/search", false)
            ), false, List.of()),
            List.of(
                hint("hint-auth-shared", "sharedId", "authenticate-user", "query-order", "$.session.sharedId", "sharedId", 0.90, Map.of()),
                hint("hint-create-shared", "sharedId", "create-order", "query-order", "$.data.sharedId", "sharedId", 0.90, Map.of())
            )
        );

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.REVIEW_REQUIRED);
        assertThat(result.blockers()).isEmpty();
        assertThat(result.diagnostics()).extracting(SuiteReadinessDiagnostic::code)
            .contains("DUPLICATE_SUITE_TARGET_KEY");
    }

    @Test
    void lowConfidenceDependencyCannotBecomeReady() {
        var result = generate(
            normalCandidate(false, List.of()),
            List.of(hint("hint-low-confidence", "externalOrderId", "create-order", "query-order", "$.data.externalOrderId", "externalOrderId", 0.55, Map.of()))
        );

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.REVIEW_REQUIRED);
        assertThat(result.blockers()).isEmpty();
        assertThat(result.diagnostics()).extracting(SuiteReadinessDiagnostic::code)
            .contains("LOW_CONFIDENCE_DEPENDENCY");
    }

    @Test
    void candidateBlockersAreInheritedAsReadinessDiagnostics() {
        var result = generate(
            normalCandidate(false, List.of(new BusinessFlowDiscoveryBlocker(
                "CONFLICTING_EVIDENCE",
                "ERROR",
                "Business-flow evidence conflicts on order prerequisite.",
                Map.of("source", "issue-05")
            ))),
            List.of(hint("hint-valid", "externalOrderId", "create-order", "query-order", "$.data.externalOrderId", "externalOrderId", 0.88, Map.of()))
        );

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(result.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .contains("CANDIDATE_BLOCKER");
        assertThat(result.diagnostics().toString()).contains("CONFLICTING_EVIDENCE");
    }

    private SuiteDraftGenerationResult generate(
        BusinessFlowCandidate candidate,
        List<SuiteDependencyHint> hints
    ) {
        return new SuiteDraftGenerationService().generate(request(candidate, hints));
    }

    private SuiteDraftGenerationRequest request(
        BusinessFlowCandidate candidate,
        List<SuiteDependencyHint> hints
    ) {
        return new SuiteDraftGenerationRequest(
            "readiness-demo",
            candidate,
            apiSpecs(),
            List.of(),
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            hints,
            SuiteDraftGenerationOptions.defaults(),
            "local-fake",
            Map.of()
        );
    }

    private SuiteDraftStep withUnpairedReference(SuiteDraftStep step) {
        var references = new ArrayList<>(step.variableReferences());
        references.add(new SuiteVariableReference(
            step.stepId(),
            SuiteConsumerLocation.QUERY,
            "externalOrderId",
            SuiteVariableScope.SUITE,
            "externalOrderId",
            "${suite.externalOrderId}",
            "dep-missing-reference",
            List.of("e-issue-05")
        ));
        return new SuiteDraftStep(
            step.stepId(),
            step.stepName(),
            step.order(),
            step.apiSpecId(),
            step.critical(),
            step.requestTemplate(),
            step.expectedStatus(),
            step.assertionHints(),
            step.extractRules(),
            references,
            step.sourceRefs(),
            step.dependencyRefs(),
            step.readinessStatus(),
            step.metadata()
        );
    }

    private SuiteDependencyHint hint(
        String hintId,
        String variableName,
        String producerStepId,
        String consumerStepId,
        String sourcePath,
        String targetKey,
        double confidence,
        Map<String, Object> metadata
    ) {
        return new SuiteDependencyHint(
            hintId,
            variableName,
            producerStepId,
            consumerStepId,
            SuiteDependencySourceType.BODY_JSON,
            sourcePath,
            SuiteConsumerLocation.QUERY,
            targetKey,
            SuiteVariableScope.SUITE,
            targetKey,
            true,
            SuiteExtractFailureStrategy.FAIL_FAST,
            null,
            confidence,
            List.of("e-issue-05"),
            false,
            metadata
        );
    }

    private BusinessFlowCandidate normalCandidate(
        boolean requiresHumanReview,
        List<BusinessFlowDiscoveryBlocker> blockers
    ) {
        return candidate(List.of(
            step("create-order", 1, "api-order-create", BusinessFlowOperationKind.CREATE, HttpMethod.POST, "/api/orders", true),
            step("query-order", 2, "api-order-query", BusinessFlowOperationKind.QUERY, HttpMethod.GET, "/api/orders/search", false)
        ), requiresHumanReview, blockers);
    }

    private BusinessFlowCandidate candidate(
        List<BusinessFlowDiscoveryStep> steps,
        boolean requiresHumanReview,
        List<BusinessFlowDiscoveryBlocker> blockers
    ) {
        return new BusinessFlowCandidate(
            "flow-readiness-issue-05",
            "Readiness validation fixture",
            true,
            0.91,
            requiresHumanReview,
            steps,
            List.of(new BusinessFlowDiscoveryEvidence(
                "e-issue-05",
                BusinessFlowEvidenceSource.API_SPEC_STRUCTURE,
                "Readiness validation fixture evidence.",
                0.24,
                steps.stream().map(BusinessFlowDiscoveryStep::apiSpecId).toList(),
                Map.of()
            )),
            blockers,
            new BusinessFlowSourceCoverage(1, 0, 0, 0, 0, Map.of()),
            List.of("v3-3", "readiness"),
            Map.of()
        );
    }

    private BusinessFlowDiscoveryStep step(
        String stepId,
        int order,
        String apiSpecId,
        BusinessFlowOperationKind kind,
        HttpMethod method,
        String path,
        boolean critical
    ) {
        return new BusinessFlowDiscoveryStep(
            stepId,
            order,
            apiSpecId,
            stepId,
            kind,
            method,
            path,
            critical,
            List.of(apiSpecId),
            SuiteDraftGenerationIssue01Tests.orderedMap(
                "pathVariables",
                path.contains("{orderId}") ? List.of("orderId") : List.of(),
                "requestFields",
                List.of("externalOrderId", "sharedId", "orderId"),
                "responseFields",
                List.of("$.data.externalOrderId", "$.data.sharedId", "$.data.orderId")
            )
        );
    }

    private List<ApiSpec> apiSpecs() {
        return List.of(
            apiSpec("api-auth-login", HttpMethod.POST, "/api/auth/login", "Login"),
            apiSpec("api-order-create", HttpMethod.POST, "/api/orders", "Create order"),
            apiSpec("api-order-query", HttpMethod.GET, "/api/orders/search", "Query order")
        );
    }

    private ApiSpec apiSpec(String apiSpecId, HttpMethod method, String path, String summary) {
        return SuiteDraftGenerationIssue01Tests.apiSpec(
            apiSpecId,
            method,
            path,
            summary,
            Map.of("requestFields", List.of("externalOrderId", "sharedId", "orderId"))
        );
    }
}
