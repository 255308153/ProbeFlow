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

class SuiteDraftGenerationIssue04Tests {

    @Test
    void supportsTokenHeaderStatusAndBodyJsonDependenciesInOneDraft() {
        var result = generate(List.of(
            hint("hint-auth-token", "authToken", "authenticate-user", "create-order",
                SuiteDependencySourceType.BODY_JSON, "$.token", SuiteConsumerLocation.HEADER, "Authorization", true),
            hint("hint-trace-header", "traceId", "create-order", "query-order",
                SuiteDependencySourceType.HEADER, "headers.X-Trace-Id", SuiteConsumerLocation.HEADER, "X-Trace-Id", true),
            new SuiteDependencyHint(
                "hint-create-status",
                "createStatusCode",
                "create-order",
                "",
                SuiteDependencySourceType.STATUS_CODE,
                "statusCode",
                SuiteConsumerLocation.NONE,
                "",
                SuiteVariableScope.SUITE,
                "createStatusCode",
                false,
                SuiteExtractFailureStrategy.WRITE_NULL,
                null,
                0.86,
                List.of("e-auth-order-flow"),
                false,
                Map.of("purpose", "downstream failure-analysis hint")
            )
        ));

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.READY);
        assertThat(result.diagnostics()).isEmpty();

        var authRules = SuiteDraftGenerationIssue01Tests.step(result, "authenticate-user").extractRules();
        assertThat(authRules)
            .extracting(SuiteExtractRule::sourceType)
            .containsExactly(SuiteDependencySourceType.BODY_JSON);
        assertThat(authRules.get(0).targetKey()).isEqualTo("authToken");
        assertThat(SuiteDraftGenerationIssue01Tests.step(result, "create-order").variableReferences())
            .extracting(SuiteVariableReference::referenceExpression)
            .contains("${suite.authToken}");
        assertThat(SuiteDraftGenerationIssue01Tests.step(result, "create-order").requestTemplate().get("headers").toString())
            .contains("Authorization=${suite.authToken}");

        var createRules = SuiteDraftGenerationIssue01Tests.step(result, "create-order").extractRules();
        assertThat(createRules)
            .extracting(SuiteExtractRule::sourceType)
            .contains(SuiteDependencySourceType.BODY_JSON, SuiteDependencySourceType.HEADER, SuiteDependencySourceType.STATUS_CODE);
        assertThat(createRules)
            .filteredOn(rule -> rule.sourceType() == SuiteDependencySourceType.HEADER)
            .singleElement()
            .satisfies(rule -> {
                assertThat(rule.sourcePath()).isEqualTo("headers.X-Trace-Id");
                assertThat(rule.targetKey()).isEqualTo("traceId");
                assertThat(rule.consumerStepIds()).containsExactly("query-order");
            });
        assertThat(createRules)
            .filteredOn(rule -> rule.sourceType() == SuiteDependencySourceType.STATUS_CODE)
            .singleElement()
            .satisfies(rule -> {
                assertThat(rule.sourcePath()).isEqualTo("statusCode");
                assertThat(rule.targetKey()).isEqualTo("createStatusCode");
                assertThat(rule.description()).contains("createStatusCode");
                assertThat(rule.required()).isFalse();
            });

        var query = SuiteDraftGenerationIssue01Tests.step(result, "query-order");
        assertThat(query.variableReferences())
            .extracting(SuiteVariableReference::targetKey)
            .contains("orderId", "traceId");
        assertThat(query.requestTemplate().get("headers").toString())
            .contains("X-Trace-Id=${suite.traceId}");
        assertThat(query.requestTemplate().get("path").toString()).contains("${suite.orderId}");
    }

    @Test
    void unsupportedDependencySourcesReturnDiagnosticWithoutFakeExtractRule() {
        var result = generate(List.of(new SuiteDependencyHint(
            "hint-xml-source",
            "xmlOrderId",
            "create-order",
            "query-order",
            SuiteDependencySourceType.UNSUPPORTED,
            "//order/id",
            SuiteConsumerLocation.BODY,
            "orderId",
            SuiteVariableScope.SUITE,
            "xmlOrderId",
            true,
            SuiteExtractFailureStrategy.FAIL_FAST,
            null,
            0.80,
            List.of("e-auth-order-flow"),
            false,
            Map.of("requestedSourceType", "XML")
        )));

        assertThat(result.readinessStatus()).isEqualTo(SuiteReadinessStatus.REVIEW_REQUIRED);
        assertThat(result.diagnostics())
            .extracting(SuiteReadinessDiagnostic::code)
            .contains("UNSUPPORTED_DEPENDENCY_SOURCE");
        assertThat(result.draft().steps().stream()
            .flatMap(step -> step.extractRules().stream())
            .map(SuiteExtractRule::sourceType)
            .toList())
            .doesNotContain(SuiteDependencySourceType.UNSUPPORTED);
    }

    private SuiteDraftGenerationResult generate(List<SuiteDependencyHint> hints) {
        return new SuiteDraftGenerationService().generate(new SuiteDraftGenerationRequest(
            "auth-order-demo",
            candidate(),
            apiSpecs(),
            List.of(),
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            hints,
            SuiteDraftGenerationOptions.defaults(),
            "local-fake",
            Map.of()
        ));
    }

    private SuiteDependencyHint hint(
        String hintId,
        String variableName,
        String producerStepId,
        String consumerStepId,
        SuiteDependencySourceType sourceType,
        String sourcePath,
        SuiteConsumerLocation consumerLocation,
        String consumerField,
        boolean required
    ) {
        return new SuiteDependencyHint(
            hintId,
            variableName,
            producerStepId,
            consumerStepId,
            sourceType,
            sourcePath,
            consumerLocation,
            consumerField,
            SuiteVariableScope.SUITE,
            variableName,
            required,
            required ? SuiteExtractFailureStrategy.FAIL_FAST : SuiteExtractFailureStrategy.WRITE_NULL,
            null,
            0.88,
            List.of("e-auth-order-flow"),
            false,
            Map.of("source", "issue-04")
        );
    }

    private BusinessFlowCandidate candidate() {
        var steps = List.of(
            step("authenticate-user", 1, "api-auth-login", "Login user", BusinessFlowOperationKind.AUTHENTICATE, HttpMethod.POST, "/api/auth/login", true),
            step("create-order", 2, "api-order-create", "Create order", BusinessFlowOperationKind.CREATE, HttpMethod.POST, "/api/orders", true),
            step("query-order", 3, "api-order-query", "Query order", BusinessFlowOperationKind.QUERY, HttpMethod.GET, "/api/orders/{orderId}", false)
        );
        return new BusinessFlowCandidate(
            "flow-auth-order",
            "Login -> Create order -> Query order",
            true,
            0.90,
            false,
            steps,
            List.of(new BusinessFlowDiscoveryEvidence(
                "e-auth-order-flow",
                BusinessFlowEvidenceSource.API_SPEC_STRUCTURE,
                "Auth token, order id and trace header dependency evidence.",
                0.25,
                List.of("api-auth-login", "api-order-create", "api-order-query"),
                Map.of()
            )),
            List.<BusinessFlowDiscoveryBlocker>of(),
            new BusinessFlowSourceCoverage(1, 0, 0, 0, 0, Map.of()),
            List.of("v3-3", "dependency-variants"),
            Map.of()
        );
    }

    private BusinessFlowDiscoveryStep step(
        String stepId,
        int order,
        String apiSpecId,
        String stepName,
        BusinessFlowOperationKind kind,
        HttpMethod method,
        String path,
        boolean critical
    ) {
        return new BusinessFlowDiscoveryStep(
            stepId,
            order,
            apiSpecId,
            stepName,
            kind,
            method,
            path,
            critical,
            List.of(apiSpecId),
            SuiteDraftGenerationIssue01Tests.orderedMap(
                "pathVariables",
                path.contains("{orderId}") ? List.of("orderId") : List.of(),
                "requestFields",
                List.of("orderId", "Authorization", "X-Trace-Id"),
                "responseFields",
                stepId.equals("authenticate-user")
                    ? List.of("$.token")
                    : List.of("$.data.orderId", "headers.X-Trace-Id", "statusCode")
            )
        );
    }

    private List<ApiSpec> apiSpecs() {
        return List.of(
            SuiteDraftGenerationIssue01Tests.apiSpec("api-auth-login", HttpMethod.POST, "/api/auth/login", "Login user", Map.of()),
            SuiteDraftGenerationIssue01Tests.apiSpec("api-order-create", HttpMethod.POST, "/api/orders", "Create order", Map.of()),
            SuiteDraftGenerationIssue01Tests.apiSpec("api-order-query", HttpMethod.GET, "/api/orders/{orderId}", "Query order", Map.of())
        );
    }
}
