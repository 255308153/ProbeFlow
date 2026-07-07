package com.probeflow.testagent.suitedraft;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryRequest;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryService;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteDraftGenerationIssue01Tests {

    @Test
    void minimalOrderIdPathGeneratesStableSuiteDraftWithoutExternalSideEffects() {
        var apiSpecs = orderApiSpecs();
        var discovery = new BusinessFlowDiscoveryService()
            .discover(BusinessFlowDiscoveryRequest.deterministic("order-suite-demo", apiSpecs));
        var candidate = discovery.candidates().get(0);
        var request = SuiteDraftGenerationRequest.deterministic("order-suite-demo", candidate, apiSpecs);

        var first = new SuiteDraftGenerationService().generate(request);
        var second = new SuiteDraftGenerationService().generate(request);

        assertThat(first.status()).isEqualTo(SuiteDraftGenerationStatus.COMPLETED);
        assertThat(first.readinessStatus()).isEqualTo(SuiteReadinessStatus.READY);
        assertThat(first.schemaVersion()).isEqualTo(SuiteDraftGenerationResult.SCHEMA_VERSION);
        assertThat(first.providerMode()).isEqualTo(SuiteDraftProviderMode.DETERMINISTIC_FAKE);
        assertThat(first.usesManualProvider()).isFalse();
        assertThat(first.usesExternalHttp()).isFalse();
        assertThat(first.diagnostics()).isEmpty();
        assertThat(first.blockers()).isEmpty();

        assertThat(first.draft().flowId()).isEqualTo(candidate.candidateId());
        assertThat(first.draft().scenarioName()).isEqualTo(candidate.scenarioName());
        assertThat(first.draft().metadata())
            .containsEntry("basedOnFlowCandidateId", candidate.candidateId())
            .containsEntry("candidateConfidence", candidate.confidence())
            .containsEntry("candidateRequiresHumanReview", candidate.requiresHumanReview());
        assertThat(first.draft().metadata().get("candidateEvidenceRefs").toString())
            .contains("e-api-api-order-create", "e-api-api-order-pay", "e-api-api-order-query");

        assertThat(first.draft().steps())
            .extracting(SuiteDraftStep::stepId)
            .containsExactly("create-order", "pay-order", "query-order");
        assertThat(first.draft().steps())
            .extracting(SuiteDraftStep::order)
            .containsExactly(1, 2, 3);
        assertThat(first.draft().steps())
            .allSatisfy(step -> {
                assertThat(step.stepName()).isNotBlank();
                assertThat(step.apiSpecId()).isNotBlank();
                assertThat(step.sourceRefs()).isNotEmpty();
                assertThat(step.requestTemplate()).containsKeys("method", "path", "headers");
                assertThat(step.readinessStatus()).isEqualTo(SuiteReadinessStatus.READY);
            });

        assertThat(first.dependencyLinks())
            .extracting(SuiteVariableDependency::consumerStepId)
            .containsExactly("pay-order", "query-order");
        assertThat(first.dependencyLinks())
            .allSatisfy(dependency -> {
                assertThat(dependency.producerStepId()).isEqualTo("create-order");
                assertThat(dependency.variableName()).isEqualTo("orderId");
                assertThat(dependency.sourceType()).isEqualTo(SuiteDependencySourceType.BODY_JSON);
                assertThat(dependency.sourcePath()).isEqualTo("$.data.orderId");
                assertThat(dependency.targetScope()).isEqualTo(SuiteVariableScope.SUITE);
                assertThat(dependency.targetKey()).isEqualTo("orderId");
                assertThat(dependency.referenceExpression()).isEqualTo("${suite.orderId}");
                assertThat(dependency.confidence()).isGreaterThanOrEqualTo(0.75);
                assertThat(dependency.evidenceRefs())
                    .contains("api-order-create", "e-api-api-order-create")
                    .anyMatch(ref -> ref.equals("api-order-pay") || ref.equals("api-order-query"));
            });
        assertThat(step(first, "create-order").dependencyRefs())
            .containsExactly("dep-create-order-to-pay-order-orderId", "dep-create-order-to-query-order-orderId");
        assertThat(step(first, "pay-order").dependencyRefs())
            .containsExactly("dep-create-order-to-pay-order-orderId");
        assertThat(step(first, "query-order").dependencyRefs())
            .containsExactly("dep-create-order-to-query-order-orderId");

        assertThat(second.draft()).isEqualTo(first.draft());
        assertThat(second.dependencyLinks()).isEqualTo(first.dependencyLinks());
    }

    @Test
    void missingInputsReturnStructuredBlockedResult() {
        var missingCandidate = new SuiteDraftGenerationService().generate(
            SuiteDraftGenerationRequest.deterministic("missing", null, List.of())
        );

        assertThat(missingCandidate.status()).isEqualTo(SuiteDraftGenerationStatus.BLOCKED);
        assertThat(missingCandidate.readinessStatus()).isEqualTo(SuiteReadinessStatus.BLOCKED);
        assertThat(missingCandidate.blockers())
            .extracting(SuiteReadinessDiagnostic::code)
            .containsExactly("MISSING_FLOW_CANDIDATE");
        assertThat(missingCandidate.usesExternalHttp()).isFalse();
        assertThat(missingCandidate.usesManualProvider()).isFalse();
    }

    static List<ApiSpec> orderApiSpecs() {
        return List.of(
            apiSpec("api-order-create", HttpMethod.POST, "/api/orders", "Create order",
                orderedMap("responseFields", List.of("$.data.orderId"))),
            apiSpec("api-order-pay", HttpMethod.POST, "/api/orders/{orderId}/payments", "Pay order",
                orderedMap("requestFields", List.of("orderId"))),
            apiSpec("api-order-query", HttpMethod.GET, "/api/orders/{orderId}", "Query order",
                orderedMap("requestFields", List.of("orderId")))
        );
    }

    static ApiSpec apiSpec(
        String apiSpecId,
        HttpMethod method,
        String path,
        String summary,
        Map<String, Object> constraints
    ) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setSystemName("ProbeFlow Fixture Commerce");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(method);
        apiSpec.setPath(path);
        apiSpec.setSummary(summary);
        apiSpec.setOperationId(apiSpecId.replace("api-", ""));
        apiSpec.setParameters(orderedMap("pathVariables", path.contains("{orderId}") ? List.of("orderId") : List.of()));
        apiSpec.setConstraints(constraints);
        apiSpec.setAuth(orderedMap("type", "bearer", "header", "Authorization", "tokenVariable", "orderAuthToken"));
        apiSpec.setSourceType(ApiSpecSourceType.MANUAL);
        apiSpec.setSourceRef("order-suite-demo");
        apiSpec.setSourceLocation(orderedMap("fixtureId", "order-suite-demo"));
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(true);
        return apiSpec;
    }

    static SuiteDraftStep step(SuiteDraftGenerationResult result, String stepId) {
        return result.draft().steps().stream()
            .filter(step -> step.stepId().equals(stepId))
            .findFirst()
            .orElseThrow();
    }

    static Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }
}
