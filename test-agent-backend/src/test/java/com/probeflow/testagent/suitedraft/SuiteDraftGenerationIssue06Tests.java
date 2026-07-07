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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteDraftGenerationIssue06Tests {

    @Test
    void blockedConfidenceDependencyCannotBecomeReady() {
        var result = generate(SuiteDraftProviderMode.DETERMINISTIC_FAKE, false, candidate(false, List.of(), false), List.of(
            dependencyHint("hint-blocked-confidence", 0.20, false, Map.of())
        ));

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(result.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .contains("LOW_CONFIDENCE_DEPENDENCY");
    }

    @Test
    void conflictingDependencyEvidenceRequiresReview() {
        var result = generate(SuiteDraftProviderMode.DETERMINISTIC_FAKE, false, candidate(false, List.of(), false), List.of(
            dependencyHint("hint-conflict", 0.88, true, Map.of("source", "field-and-doc-disagree"))
        ));

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.REVIEW_REQUIRED);
        assertThat(result.blockers()).isEmpty();
        assertThat(result.diagnostics()).extracting(SuiteReadinessDiagnostic::code)
            .contains("CONFLICTING_DEPENDENCY_EVIDENCE");
    }

    @Test
    void candidateHumanReviewAndBlockerArePreserved() {
        var reviewRequired = generate(
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            candidate(true, List.of(), false),
            List.of(dependencyHint("hint-valid", 0.88, false, Map.of()))
        );
        assertThat(reviewRequired.readinessStatus()).isEqualTo(SuiteReadinessStatus.REVIEW_REQUIRED);
        assertThat(reviewRequired.diagnostics()).extracting(SuiteReadinessDiagnostic::code)
            .contains("CANDIDATE_REQUIRES_HUMAN_REVIEW");

        var blocked = generate(
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            candidate(false, List.of(new BusinessFlowDiscoveryBlocker(
                "MISSING_AUTH_PRECONDITION",
                "ERROR",
                "Auth prerequisite was not proven.",
                Map.of()
            )), false),
            List.of(dependencyHint("hint-valid", 0.88, false, Map.of()))
        );
        assertThat(blocked.readinessStatus()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(blocked.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .contains("CANDIDATE_BLOCKER");
    }

    @Test
    void highRiskStepWithIncompleteDependenciesIsBlocked() {
        var result = generate(
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            candidate(false, List.of(), false),
            List.of()
        );

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(result.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .contains("HIGH_RISK_STEP_DEPENDENCY_INCOMPLETE");
        assertThat(SuiteDraftGenerationIssue01Tests.step(result, "pay-order").readinessStatus())
            .isEqualTo(SuiteReadinessStatus.BLOCKED);
    }

    @Test
    void manualRealLlmModeIsBlockedUnlessExplicitlyAllowed() {
        var result = generate(
            SuiteDraftProviderMode.MANUAL_REAL_LLM,
            false,
            candidate(false, List.of(), true),
            List.of(dependencyHint("hint-valid", 0.88, false, Map.of()))
        );

        assertThat(result.status()).isEqualTo(SuiteDraftGenerationStatus.BLOCKED);
        assertThat(result.usesManualProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.blockers()).extracting(SuiteReadinessDiagnostic::code)
            .containsExactly("PROVIDER_BLOCKED");
    }

    @Test
    void explicitManualLlmSuggestionsStayEvidenceAndRequireReview() {
        var result = generate(
            SuiteDraftProviderMode.MANUAL_REAL_LLM,
            true,
            candidate(false, List.of(), true),
            List.of(dependencyHint("hint-llm-suggestion", 0.88, false, Map.of(
                "suggestionSource", "LLM_SUGGESTION",
                "provider", "manual-real-llm"
            )))
        );

        assertThat(result.status()).isEqualTo(SuiteDraftGenerationStatus.COMPLETED);
        assertThat(result.usesManualProvider()).isTrue();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.REVIEW_REQUIRED);
        assertThat(result.dependencyLinks())
            .singleElement()
            .satisfies(dependency -> {
                assertThat(dependency.evidenceRefs()).contains("e-llm-suite-dep");
                assertThat(dependency.metadata().toString()).contains("LLM_SUGGESTION");
            });
        assertThat(result.diagnostics()).extracting(SuiteReadinessDiagnostic::code)
            .contains("MANUAL_LLM_SUGGESTION_REQUIRES_REVIEW");
    }

    @Test
    void defaultFakeProviderAndPolicyGateRemainGenerationSideOnly() {
        var result = generate(
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            candidate(false, List.of(), false),
            List.of(dependencyHint("hint-valid", 0.88, false, Map.of()))
        );

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.READY);
        assertThat(result.providerMode()).isEqualTo(SuiteDraftProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.usesManualProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.metadata())
            .containsEntry("defaultProviderIsFake", true)
            .containsEntry("usesExternalHttp", false);
        assertThat(result.draft().metadata().get("policyGate").toString())
            .contains("DependencyLinker")
            .contains("SuiteReadinessValidator")
            .contains("No PlannerDecision");
    }

    private SuiteDraftGenerationResult generate(
        SuiteDraftProviderMode providerMode,
        boolean allowManualProvider,
        BusinessFlowCandidate candidate,
        List<SuiteDependencyHint> hints
    ) {
        return new SuiteDraftGenerationService().generate(new SuiteDraftGenerationRequest(
            "risk-gate-demo",
            candidate,
            apiSpecs(),
            List.of(),
            providerMode,
            allowManualProvider,
            hints,
            SuiteDraftGenerationOptions.defaults(),
            providerMode == SuiteDraftProviderMode.MANUAL_REAL_LLM ? "manual-eval" : "local-fake",
            Map.of()
        ));
    }

    private SuiteDependencyHint dependencyHint(
        String hintId,
        double confidence,
        boolean conflict,
        Map<String, Object> metadata
    ) {
        return new SuiteDependencyHint(
            hintId,
            "externalOrderId",
            "create-order",
            "pay-order",
            SuiteDependencySourceType.BODY_JSON,
            "$.data.externalOrderId",
            SuiteConsumerLocation.PATH,
            "externalOrderId",
            SuiteVariableScope.SUITE,
            "externalOrderId",
            true,
            SuiteExtractFailureStrategy.FAIL_FAST,
            null,
            confidence,
            List.of("e-issue-06", "e-llm-suite-dep"),
            conflict,
            metadata
        );
    }

    private BusinessFlowCandidate candidate(
        boolean requiresHumanReview,
        List<BusinessFlowDiscoveryBlocker> blockers,
        boolean includeLlmEvidence
    ) {
        var steps = List.of(
            step("create-order", 1, "api-order-create", BusinessFlowOperationKind.CREATE, HttpMethod.POST, "/api/orders", true),
            step("pay-order", 2, "api-order-pay", BusinessFlowOperationKind.PAY, HttpMethod.POST, "/api/orders/{externalOrderId}/payments", true)
        );
        var evidence = includeLlmEvidence
            ? List.of(
                evidence("e-issue-06", BusinessFlowEvidenceSource.API_SPEC_STRUCTURE, "ApiSpec fields suggest payment dependency."),
                evidence("e-llm-suite-dep", BusinessFlowEvidenceSource.LLM_SUGGESTION, "Manual LLM suggested the same payment dependency.")
            )
            : List.of(evidence("e-issue-06", BusinessFlowEvidenceSource.API_SPEC_STRUCTURE, "ApiSpec fields suggest payment dependency."));
        return new BusinessFlowCandidate(
            "flow-risk-gate-issue-06",
            "Create order -> Pay order risk gate",
            true,
            0.90,
            requiresHumanReview,
            steps,
            evidence,
            blockers,
            new BusinessFlowSourceCoverage(1, 0, 0, 0, includeLlmEvidence ? 1 : 0, Map.of()),
            List.of("v3-3", "risk-gate"),
            Map.of()
        );
    }

    private BusinessFlowDiscoveryEvidence evidence(
        String evidenceId,
        BusinessFlowEvidenceSource source,
        String summary
    ) {
        return new BusinessFlowDiscoveryEvidence(
            evidenceId,
            source,
            summary,
            source == BusinessFlowEvidenceSource.LLM_SUGGESTION ? 0.08 : 0.24,
            List.of(evidenceId + "-ref"),
            Map.of("phase", "v3-3")
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
                path.contains("{externalOrderId}") ? List.of("externalOrderId") : List.of(),
                "requestFields",
                List.of("externalOrderId"),
                "responseFields",
                List.of("$.data.externalOrderId")
            )
        );
    }

    private List<ApiSpec> apiSpecs() {
        return List.of(
            apiSpec("api-order-create", HttpMethod.POST, "/api/orders", "Create order"),
            apiSpec("api-order-pay", HttpMethod.POST, "/api/orders/{externalOrderId}/payments", "Pay order")
        );
    }

    private ApiSpec apiSpec(String apiSpecId, HttpMethod method, String path, String summary) {
        return SuiteDraftGenerationIssue01Tests.apiSpec(
            apiSpecId,
            method,
            path,
            summary,
            Map.of("requestFields", List.of("externalOrderId"))
        );
    }
}
