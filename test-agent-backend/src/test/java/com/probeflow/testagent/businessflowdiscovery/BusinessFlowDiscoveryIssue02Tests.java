package com.probeflow.testagent.businessflowdiscovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessFlowDiscoveryIssue02Tests {

    @Test
    void apiSpecStructuralEvidenceIncludesMethodPathFieldsAndAuthReadiness() {
        var apiSpecs = BusinessFlowDiscoveryIssue01Tests.orderApiSpecs();
        apiSpecs.get(0).setParameters(orderedMap(
            "requestFields", List.of("skuId", "quantity", "tenant"),
            "pathVariables", List.of()
        ));
        apiSpecs.get(0).setConstraints(orderedMap("responseFields", List.of("orderId", "status")));
        apiSpecs.get(1).setParameters(orderedMap(
            "requestFields", List.of("orderId", "paymentMethod"),
            "pathVariables", List.of("orderId")
        ));
        apiSpecs.get(1).setConstraints(orderedMap("responseFields", List.of("paymentId", "orderId", "paymentStatus")));

        var result = new BusinessFlowDiscoveryService()
            .discover(BusinessFlowDiscoveryRequest.deterministic("order-suite-demo", apiSpecs));

        var candidate = result.candidates().get(0);
        assertThat(candidate.steps())
            .extracting(BusinessFlowDiscoveryStep::apiSpecId)
            .containsExactly("api-order-create", "api-order-pay", "api-order-query");
        assertThat(candidate.steps())
            .extracting(BusinessFlowDiscoveryStep::operationKind)
            .containsExactly(
                BusinessFlowOperationKind.CREATE,
                BusinessFlowOperationKind.PAY,
                BusinessFlowOperationKind.QUERY
            );

        var createEvidence = evidence(candidate, "e-api-api-order-create");
        assertThat(createEvidence.metadata())
            .containsEntry("httpMethod", "POST")
            .containsEntry("path", "/api/orders")
            .containsEntry("authReady", true);
        assertThat(createEvidence.metadata().get("requestFields").toString()).contains("skuId", "quantity");
        assertThat(createEvidence.metadata().get("responseFields").toString()).contains("orderId", "status");

        var payEvidence = evidence(candidate, "e-api-api-order-pay");
        assertThat(payEvidence.metadata().get("pathVariables").toString()).contains("orderId");
        assertThat(payEvidence.summary()).contains("POST /api/orders/{orderId}/payments");
        assertThat(candidate.sourceCoverage().apiSpecEvidenceCount()).isEqualTo(3);
    }

    @Test
    void missingAuthReadinessAddsBlockerAndHumanReview() {
        var apiSpecs = BusinessFlowDiscoveryIssue01Tests.orderApiSpecs();
        apiSpecs.forEach(apiSpec -> {
            apiSpec.setAuth(new LinkedHashMap<>());
            apiSpec.setAuthReady(false);
        });

        var result = new BusinessFlowDiscoveryService()
            .discover(BusinessFlowDiscoveryRequest.deterministic("order-suite-demo", apiSpecs));

        var candidate = result.candidates().get(0);
        assertThat(result.status()).isEqualTo(BusinessFlowDiscoveryStatus.BLOCKED);
        assertThat(candidate.requiresHumanReview()).isTrue();
        assertThat(candidate.blockers())
            .extracting(BusinessFlowDiscoveryBlocker::code)
            .contains("MISSING_AUTH_PRECONDITION");
    }

    private BusinessFlowDiscoveryEvidence evidence(BusinessFlowCandidate candidate, String evidenceId) {
        return candidate.evidence().stream()
            .filter(item -> item.evidenceId().equals(evidenceId))
            .findFirst()
            .orElseThrow();
    }

    private java.util.Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }
}
