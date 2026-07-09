package com.probeflow.testagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.memory.MemoryFactType;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryRewriteContractIssue01Tests {

    private final DeterministicQueryRewriteService rewriteService = new DeterministicQueryRewriteService();

    @Test
    void generatesStableStructuredVariantsAndPreservesHighPrecisionFilters() {
        var request = new QueryRewriteRequest(
            "failure_analysis",
            "why does payment charge fail with gateway timeout",
            "explain root cause and suggest regression cases",
            "billing",
            "payment",
            "/api/payments/charge",
            "post",
            "Payment",
            "GW_TIMEOUT",
            "REMOTE_TIMEOUT",
            null,
            null,
            null,
            null,
            List.of("payment", "gateway", "timeout")
        );

        var first = rewriteService.rewrite(request);
        var second = rewriteService.rewrite(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.diagnostics()).isEmpty();
        assertThat(first.variants()).isNotEmpty();
        assertThat(first.variants())
            .allSatisfy(variant -> {
                assertThat(variant.deterministicId()).startsWith("qv-");
                assertThat(variant.queryText()).isNotBlank();
                assertThat(variant.stageProfile()).isEqualTo("failure_analysis");
                assertThat(variant.priority()).isPositive();
                assertThat(variant.reason()).isNotBlank();
                assertThat(variant.filters().systemName()).isEqualTo("billing");
                assertThat(variant.filters().moduleName()).isEqualTo("payment");
                assertThat(variant.filters().apiPath()).isEqualTo("/api/payments/charge");
                assertThat(variant.filters().httpMethod()).isEqualTo("POST");
                assertThat(variant.filters().businessEntity()).isEqualTo("Payment");
                assertThat(variant.filters().errorCode()).isEqualTo("GW_TIMEOUT");
                assertThat(variant.filters().failureClassification()).isEqualTo("REMOTE_TIMEOUT");
                assertThat(variant.filters().tags()).containsExactly("payment", "gateway", "timeout");
            });
        assertThat(first.variants())
            .extracting(QueryVariant::intent)
            .contains(QueryIntent.RAW_TASK, QueryIntent.ERROR_CODE, QueryIntent.FAILURE_REASON);
        assertThat(first.variants())
            .extracting(QueryVariant::targetCorpus)
            .contains(QueryTargetCorpus.ALL, QueryTargetCorpus.KNOWLEDGE, QueryTargetCorpus.MEMORY);
    }

    @Test
    void generatesDifferentVariantsForAgentStages() {
        var apiAnalysis = rewriteService.rewrite(baseRequest("api_analysis"));
        var caseGeneration = rewriteService.rewrite(baseRequest("case_generation"));
        var failureAnalysis = rewriteService.rewrite(baseRequest("failure_analysis"));
        var suiteRecovery = rewriteService.rewrite(new QueryRewriteRequest(
            "suite_recovery",
            "repair checkout suite variable handoff",
            "recover suite variable dependency",
            "commerce",
            "checkout",
            "/api/orders/{orderId}/pay",
            "POST",
            "Order",
            null,
            null,
            "checkout-happy-path",
            "paymentToken",
            null,
            null,
            List.of("suite", "checkout")
        ));
        var policy = rewriteService.rewrite(new QueryRewriteRequest(
            "planner_policy",
            "should planner call destructive tool",
            "reuse tool policy memory",
            "commerce",
            "checkout",
            "/api/orders/{orderId}/pay",
            "POST",
            "Order",
            null,
            null,
            null,
            null,
            "requires-human-approval",
            "delete-test-data",
            List.of("policy")
        ));

        assertThat(apiAnalysis.variants())
            .extracting(QueryVariant::intent)
            .contains(QueryIntent.API_STRUCTURE, QueryIntent.BUSINESS_RULE);
        assertThat(apiAnalysis.variants())
            .anySatisfy(variant -> assertThat(variant.filters().documentTypes())
                .contains(DocumentType.API_NOTE, DocumentType.BUSINESS_FLOW));

        assertThat(caseGeneration.variants())
            .extracting(QueryVariant::intent)
            .contains(QueryIntent.TEST_STRATEGY, QueryIntent.BUSINESS_RULE);
        assertThat(caseGeneration.variants())
            .anySatisfy(variant -> assertThat(variant.filters().documentTypes())
                .contains(DocumentType.TEST_SPEC, DocumentType.DOMAIN_RULE));
        assertThat(caseGeneration.variants())
            .anySatisfy(variant -> assertThat(variant.filters().factTypes())
                .contains(MemoryFactType.TESTING_PATTERN));

        assertThat(failureAnalysis.variants())
            .extracting(QueryVariant::intent)
            .contains(QueryIntent.ERROR_CODE, QueryIntent.FAILURE_REASON);
        assertThat(failureAnalysis.variants())
            .anySatisfy(variant -> assertThat(variant.filters().documentTypes())
                .contains(DocumentType.ERROR_CODE_GUIDE, DocumentType.INCIDENT_POSTMORTEM));
        assertThat(failureAnalysis.variants())
            .anySatisfy(variant -> assertThat(variant.filters().factTypes())
                .contains(MemoryFactType.FAILURE_PATTERN));

        assertThat(suiteRecovery.variants())
            .extracting(QueryVariant::intent)
            .contains(QueryIntent.SUITE_VARIABLE, QueryIntent.BUSINESS_RULE);
        assertThat(suiteRecovery.variants())
            .anySatisfy(variant -> assertThat(variant.filters().factTypes())
                .contains(MemoryFactType.SUITE_DEPENDENCY_FACT, MemoryFactType.VARIABLE_EXTRACTION_FACT));

        assertThat(policy.variants())
            .extracting(QueryVariant::intent)
            .contains(QueryIntent.POLICY_LEARNING);
        assertThat(policy.variants())
            .anySatisfy(variant -> {
                assertThat(variant.filters().policyReason()).isEqualTo("requires-human-approval");
                assertThat(variant.filters().toolName()).isEqualTo("delete-test-data");
                assertThat(variant.filters().factTypes()).contains(MemoryFactType.POLICY_LEARNING);
            });
    }

    @Test
    void missingContextFallsBackToOriginalQueryWithDiagnostic() {
        var result = rewriteService.rewrite(new QueryRewriteRequest(
            "case_generation",
            "generate useful tests",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of()
        ));

        assertThat(result.variants()).isNotEmpty();
        assertThat(result.variants().getFirst().intent()).isEqualTo(QueryIntent.RAW_TASK);
        assertThat(result.variants().getFirst().queryText()).isEqualTo("generate useful tests");
        assertThat(result.diagnostics()).contains("low-context: only raw query is available");
    }

    private QueryRewriteRequest baseRequest(String stageProfile) {
        return new QueryRewriteRequest(
            stageProfile,
            "payment order idempotency behavior",
            "build reliable API tests",
            "commerce",
            "checkout",
            "/api/orders/{orderId}/pay",
            "POST",
            "Order",
            "PAYMENT_DECLINED",
            "BUSINESS_DECLINE",
            null,
            null,
            null,
            null,
            List.of("payment", "checkout")
        );
    }
}
