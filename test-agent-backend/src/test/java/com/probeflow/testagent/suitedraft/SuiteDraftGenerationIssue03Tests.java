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

class SuiteDraftGenerationIssue03Tests {

    @Test
    void requestTemplateRewriteCoversPathQueryBodyAndHeaderWithoutFlatteningStructure() {
        var result = generateWithRewriteHints(List.of(singleTemplate(
            "single-update-order-template",
            "api-order-update",
            SuiteDraftGenerationIssue01Tests.orderedMap(
                "method", "PATCH",
                "path", "/api/orders",
                "headers", SuiteDraftGenerationIssue01Tests.orderedMap("X-Client", "fixture-client"),
                "query", SuiteDraftGenerationIssue01Tests.orderedMap("dryRun", false),
                "body", SuiteDraftGenerationIssue01Tests.orderedMap("orderId", "literal-order-id", "amount", 42)
            )
        )));

        var payTemplate = SuiteDraftGenerationIssue01Tests.step(result, "pay-order").requestTemplate();
        assertThat(payTemplate.get("path")).isEqualTo("/api/orders/${suite.orderId}/payments");

        var searchTemplate = SuiteDraftGenerationIssue01Tests.step(result, "search-order").requestTemplate();
        assertThat(searchTemplate.get("query"))
            .isInstanceOf(Map.class)
            .asString()
            .contains("orderId=${suite.orderId}");

        var updateTemplate = SuiteDraftGenerationIssue01Tests.step(result, "update-order").requestTemplate();
        assertThat(updateTemplate.get("templateSource")).isEqualTo("SINGLE_CASE_TEMPLATE");
        assertThat(updateTemplate.get("headers").toString()).contains("X-Client=fixture-client");
        assertThat(updateTemplate.get("body"))
            .isInstanceOf(Map.class)
            .asString()
            .contains("orderId=${suite.orderId}")
            .contains("amount=42");

        var traceTemplate = SuiteDraftGenerationIssue01Tests.step(result, "trace-order").requestTemplate();
        assertThat(traceTemplate.get("headers"))
            .isInstanceOf(Map.class)
            .asString()
            .contains("X-Order-Id=${suite.orderId}");
    }

    @Test
    void suiteStepSnapshotsIncludeMetadataAndDoNotOnlyReferenceSingleCaseIds() {
        var result = generateWithRewriteHints(List.of(singleTemplate(
            "single-pay-order-template",
            "api-order-pay",
            SuiteDraftGenerationIssue01Tests.orderedMap(
                "method", "POST",
                "path", "/api/orders/{orderId}/payments",
                "headers", SuiteDraftGenerationIssue01Tests.orderedMap("Authorization", "Bearer {{orderAuthToken}}"),
                "body", SuiteDraftGenerationIssue01Tests.orderedMap("paymentMethod", "FAKE_CARD")
            )
        )));

        var pay = SuiteDraftGenerationIssue01Tests.step(result, "pay-order");
        assertThat(pay.metadata())
            .containsEntry("snapshotType", "SUITE_STEP_DRAFT")
            .containsEntry("requestTemplateSource", "SINGLE_CASE_TEMPLATE")
            .containsEntry("singleTemplateId", "single-pay-order-template")
            .containsEntry("snapshotFrozen", true);
        assertThat(pay.requestTemplate().toString())
            .contains("${suite.orderId}")
            .contains("paymentMethod=FAKE_CARD");
        assertThat(pay.variableReferences()).isNotEmpty();
        assertThat(pay.dependencyRefs()).contains("dep-create-order-to-pay-order-orderId");
        assertThat(pay.extractRules()).isEmpty();

        assertThat(result.draft().metadata())
            .containsEntry("basedOnFlowCandidateId", "flow-suite-draft-issue-03")
            .containsEntry("generationMode", "DETERMINISTIC_GENERATION");
        assertThat(result.draft().metadata().get("basedOnApiSpecIds").toString())
            .contains("api-order-create", "api-order-pay", "api-order-search", "api-order-update", "api-order-trace");
        assertThat(result.draft().metadata().get("basedOnApiSpecVersions").toString())
            .contains("api-order-create=1", "api-order-pay=1");
        assertThat(result.draft().metadata().get("basedOnKnowledgeRefs").toString()).contains("doc-order-flow");
        assertThat(result.draft().metadata().get("basedOnMemoryRefs").toString()).contains("mem-order-template");
        assertThat(result.draft().metadata().get("dependencyGraphSummary").toString())
            .contains("dep-create-order-to-pay-order-orderId", "dep-create-order-to-search-order-orderId");
        assertThat(result.draft().metadata().get("readinessValidation").toString()).contains("READY");
    }

    private SuiteDraftGenerationResult generateWithRewriteHints(List<SuiteSingleCaseTemplate> singleTemplates) {
        var apiSpecs = apiSpecs();
        return new SuiteDraftGenerationService().generate(new SuiteDraftGenerationRequest(
            "rewrite-demo",
            candidate(),
            apiSpecs,
            singleTemplates,
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(
                hint("hint-query-order", "search-order", SuiteConsumerLocation.QUERY, "orderId"),
                hint("hint-body-order", "update-order", SuiteConsumerLocation.BODY, "orderId"),
                hint("hint-header-order", "trace-order", SuiteConsumerLocation.HEADER, "X-Order-Id")
            ),
            SuiteDraftGenerationOptions.defaults(),
            "local-fake",
            Map.of()
        ));
    }

    private SuiteDependencyHint hint(
        String hintId,
        String consumerStepId,
        SuiteConsumerLocation location,
        String consumerField
    ) {
        return new SuiteDependencyHint(
            hintId,
            "orderId",
            "create-order",
            consumerStepId,
            SuiteDependencySourceType.BODY_JSON,
            "$.data.orderId",
            location,
            consumerField,
            SuiteVariableScope.SUITE,
            "orderId",
            true,
            SuiteExtractFailureStrategy.FAIL_FAST,
            null,
            0.88,
            List.of("e-issue-03-api", "doc-order-flow"),
            false,
            Map.of("source", "test-hint")
        );
    }

    private SuiteSingleCaseTemplate singleTemplate(String templateId, String apiSpecId, Map<String, Object> request) {
        return new SuiteSingleCaseTemplate(
            templateId,
            apiSpecId,
            request,
            200,
            SuiteDraftGenerationIssue01Tests.orderedMap("assertion", "status-code"),
            Map.of("singleCaseId", templateId)
        );
    }

    private BusinessFlowCandidate candidate() {
        var steps = List.of(
            step("create-order", 1, "api-order-create", "Create order", BusinessFlowOperationKind.CREATE, HttpMethod.POST, "/api/orders", true),
            step("pay-order", 2, "api-order-pay", "Pay order", BusinessFlowOperationKind.PAY, HttpMethod.POST, "/api/orders/{orderId}/payments", true),
            step("search-order", 3, "api-order-search", "Search order", BusinessFlowOperationKind.QUERY, HttpMethod.GET, "/api/orders/search", false),
            step("update-order", 4, "api-order-update", "Update order", BusinessFlowOperationKind.UPDATE, HttpMethod.PATCH, "/api/orders", true),
            step("trace-order", 5, "api-order-trace", "Trace order", BusinessFlowOperationKind.QUERY, HttpMethod.GET, "/api/orders/trace", false)
        );
        return new BusinessFlowCandidate(
            "flow-suite-draft-issue-03",
            "Create order -> Pay order -> Search order -> Update order -> Trace order",
            true,
            0.92,
            false,
            steps,
            List.of(
                new BusinessFlowDiscoveryEvidence(
                    "e-issue-03-api",
                    BusinessFlowEvidenceSource.API_SPEC_STRUCTURE,
                    "ApiSpec fields identify orderId dependency consumers.",
                    0.20,
                    List.of("api-order-create", "api-order-pay", "api-order-search", "api-order-update", "api-order-trace"),
                    Map.of()
                ),
                new BusinessFlowDiscoveryEvidence(
                    "e-issue-03-knowledge",
                    BusinessFlowEvidenceSource.KNOWLEDGE,
                    "Order flow docs mention shared order id.",
                    0.15,
                    List.of("doc-order-flow"),
                    Map.of()
                ),
                new BusinessFlowDiscoveryEvidence(
                    "e-issue-03-memory",
                    BusinessFlowEvidenceSource.MEMORY,
                    "Previous order suite reused a single order id.",
                    0.10,
                    List.of("mem-order-template"),
                    Map.of()
                )
            ),
            List.<BusinessFlowDiscoveryBlocker>of(),
            new BusinessFlowSourceCoverage(1, 1, 1, 0, 0, Map.of()),
            List.of("v3-3", "suite-draft"),
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
                List.of("orderId"),
                "responseFields",
                stepId.equals("create-order") ? List.of("$.data.orderId") : List.of()
            )
        );
    }

    private List<ApiSpec> apiSpecs() {
        return List.of(
            SuiteDraftGenerationIssue01Tests.apiSpec("api-order-create", HttpMethod.POST, "/api/orders", "Create order", Map.of()),
            SuiteDraftGenerationIssue01Tests.apiSpec("api-order-pay", HttpMethod.POST, "/api/orders/{orderId}/payments", "Pay order", Map.of()),
            SuiteDraftGenerationIssue01Tests.apiSpec("api-order-search", HttpMethod.GET, "/api/orders/search", "Search order", Map.of()),
            SuiteDraftGenerationIssue01Tests.apiSpec("api-order-update", HttpMethod.PATCH, "/api/orders", "Update order", Map.of()),
            SuiteDraftGenerationIssue01Tests.apiSpec("api-order-trace", HttpMethod.GET, "/api/orders/trace", "Trace order", Map.of())
        );
    }
}
