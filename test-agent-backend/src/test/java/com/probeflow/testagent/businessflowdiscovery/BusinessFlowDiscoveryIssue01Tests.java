package com.probeflow.testagent.businessflowdiscovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessFlowDiscoveryIssue01Tests {

    @Test
    void deterministicSmokePathReturnsCandidateContractWithoutExternalDependencies() {
        var result = new BusinessFlowDiscoveryService()
            .discover(BusinessFlowDiscoveryRequest.deterministic("order-suite-demo", orderApiSpecs()));

        assertThat(result.status()).isEqualTo(BusinessFlowDiscoveryStatus.COMPLETED);
        assertThat(result.schemaVersion()).isEqualTo(BusinessFlowDiscoveryResult.SCHEMA_VERSION);
        assertThat(result.fixtureId()).isEqualTo("order-suite-demo");
        assertThat(result.providerMode()).isEqualTo(BusinessFlowDiscoveryProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.usesRealLlm()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.candidates()).hasSize(1);

        var candidate = result.candidates().get(0);
        assertThat(candidate.scenarioName()).isEqualTo("Create order -> Pay order -> Query order");
        assertThat(candidate.confidence()).isGreaterThanOrEqualTo(0.70);
        assertThat(candidate.requiresHumanReview()).isFalse();
        assertThat(candidate.blockers()).isEmpty();
        assertThat(candidate.steps())
            .extracting(BusinessFlowDiscoveryStep::stepId)
            .containsExactly("create-order", "pay-order", "query-order");
        assertThat(candidate.steps())
            .extracting(BusinessFlowDiscoveryStep::order)
            .containsExactly(1, 2, 3);
        assertThat(candidate.evidence())
            .extracting(BusinessFlowDiscoveryEvidence::source)
            .contains(BusinessFlowEvidenceSource.API_SPEC_STRUCTURE);
        assertThat(candidate.sourceCoverage().apiSpecEvidenceCount()).isEqualTo(3);
        assertThat(candidate.metadata())
            .containsEntry("staysDiscoveryOnly", true)
            .containsEntry("doesNotGenerateSuiteVariables", true);
    }

    @Test
    void missingApiSpecsReturnStructuredBlocker() {
        var result = new BusinessFlowDiscoveryService()
            .discover(BusinessFlowDiscoveryRequest.deterministic("empty", List.of()));

        assertThat(result.status()).isEqualTo(BusinessFlowDiscoveryStatus.BLOCKED);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.blockers())
            .extracting(BusinessFlowDiscoveryBlocker::code)
            .containsExactly("MISSING_API_SPECS");
        assertThat(result.usesRealLlm()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
    }

    static List<ApiSpec> orderApiSpecs() {
        return List.of(
            apiSpec("api-order-create", HttpMethod.POST, "/api/orders", "Create order"),
            apiSpec("api-order-pay", HttpMethod.POST, "/api/orders/{orderId}/payments", "Pay order"),
            apiSpec("api-order-query", HttpMethod.GET, "/api/orders/{orderId}", "Query order")
        );
    }

    static ApiSpec apiSpec(String apiSpecId, HttpMethod method, String path, String summary) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setSystemName("ProbeFlow Fixture Commerce");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(method);
        apiSpec.setPath(path);
        apiSpec.setSummary(summary);
        apiSpec.setOperationId(apiSpecId.replace("api-", ""));
        apiSpec.setParameters(orderedMap("pathVariables", List.of("orderId")));
        apiSpec.setConstraints(orderedMap("fixture", true));
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

    static Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }
}
