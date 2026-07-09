package com.probeflow.testagent.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryEvidenceExpansionIssue06Tests {

    private final MemoryEvidenceExpander service = new MemoryEvidenceExpander();

    @Test
    void expandsFailurePatternWithSanitizedFullContentEvidenceAndIdentityHints() {
        var candidate = memoryCandidate(
            "mem-pay-401",
            "Tenant bootstrap prevents PAY_401",
            "Authorization: Bearer secret-token PAY_401 was fixed by tenant bootstrap.",
            Map.ofEntries(
                Map.entry("factFingerprint", "fact-pay-401-bootstrap"),
                Map.entry("sanitizedFullContent", "PAY_401 failure resolved after tenant bootstrap. api_key=super-secret"),
                Map.entry("evidenceSummaries", List.of(
                    "suite run 42 reproduced PAY_401 before bootstrap",
                    "suite run 43 passed after bootstrap"
                )),
                Map.entry("sourceRefs", List.of("suite-runs/42", "suite-runs/43")),
                Map.entry("mergedSourceRefs", List.of("merged/payment-auth-bootstrap")),
                Map.entry("evidenceCount", 2),
                Map.entry("systemName", "order-platform"),
                Map.entry("moduleName", "payment"),
                Map.entry("apiPath", "/api/orders/{orderId}/pay"),
                Map.entry("errorCode", "PAY_401"),
                Map.entry("businessEntity", "Order")
            ),
            new RerankFeatureLedger(
                0.8d,
                0.7d,
                0.4d,
                0.9d,
                1.0d,
                0.95d,
                null,
                0.8d,
                0.91d,
                0.84d,
                0.76d,
                null,
                null,
                false,
                List.of(),
                80,
                List.of()
            )
        );

        var result = service.expand(new MemoryEvidenceExpansionRequest(List.of(outputItem(candidate)), 400));

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.anchor().anchorType()).isEqualTo(MemoryEvidenceAnchorType.FACT_FINGERPRINT);
        assertThat(item.anchor().factFingerprint()).isEqualTo("fact-pay-401-bootstrap");
        assertThat(item.fullContent()).contains("PAY_401 failure resolved");
        assertThat(item.fullContent()).doesNotContain("super-secret");
        assertThat(item.fullContent()).contains("api_key=[REDACTED]");
        assertThat(item.evidenceSummaries()).containsExactly(
            "suite run 42 reproduced PAY_401 before bootstrap",
            "suite run 43 passed after bootstrap"
        );
        assertThat(item.sourceRefs()).contains("suite-runs/42", "suite-runs/43");
        assertThat(item.mergedSourceRefs()).containsExactly("merged/payment-auth-bootstrap");
        assertThat(item.evidenceCount()).isEqualTo(2);
        assertThat(item.identityHints().asMap())
            .containsEntry("system", "order-platform")
            .containsEntry("module", "payment")
            .containsEntry("apiPath", "/api/orders/{orderId}/pay")
            .containsEntry("errorCode", "PAY_401")
            .containsEntry("businessEntity", "Order");
        assertThat(item.positiveRecommendationEligible()).isTrue();
        assertThat(item.citations()).extracting(MemoryEvidenceCitation::role)
            .contains(MemoryEvidenceRole.SUPPORTING);
    }

    @Test
    void expandsSuiteVariableAndPolicyLearningIdentityHints() {
        var suiteVariable = memoryCandidate(
            "mem-suite-token",
            "Suite token variable comes from login",
            "Use {{authToken}} from login response before payment.",
            Map.of(
                "factFingerprint", "fact-suite-auth-token",
                "evidenceSummary", "login step exports authToken for downstream payment",
                "sourceRef", "suite/order-checkout",
                "identityHints", Map.of(
                    "suiteId", "checkout-regression",
                    "variableKey", "authToken",
                    "apiPath", "/api/login"
                )
            ),
            ledger(0.88d, false, List.of(), 52)
        );
        var policy = memoryCandidate(
            "mem-policy-prod-write",
            "Planner requires approval for production writes",
            "Production write tools require human approval.",
            Map.of(
                "factFingerprint", "fact-policy-prod-write",
                "evidenceSummary", "planner decision history blocked unsafe production write",
                "sourceRef", "policy/planner-safe-tools",
                "policyReason", "PRODUCTION_WRITE_APPROVAL",
                "system", "order-platform"
            ),
            ledger(0.92d, false, List.of(), 48)
        );

        var result = service.expand(new MemoryEvidenceExpansionRequest(
            List.of(outputItem(suiteVariable), outputItem(policy)),
            500
        ));

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().identityHints().asMap())
            .containsEntry("suiteId", "checkout-regression")
            .containsEntry("variableKey", "authToken")
            .containsEntry("apiPath", "/api/login");
        assertThat(result.items().get(1).identityHints().asMap())
            .containsEntry("policyReason", "PRODUCTION_WRITE_APPROVAL")
            .containsEntry("system", "order-platform");
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.anchor().factFingerprint()).startsWith("fact-");
            assertThat(item.positiveRecommendationEligible()).isTrue();
        });
    }

    @Test
    void expandsGraphRelationAndKeepsConflictAuditOutOfPositiveRecommendations() {
        var candidate = memoryCandidate(
            "mem-graph-pay-401",
            "PAY_401 relates to tenant bootstrap",
            "Graph-expanded memory summary.",
            Map.of(
                "factFingerprint", "fact-pay-401-graph",
                "graphRelationPath", List.of("ERROR_CODE:PAY_401", "API_PATH:/api/orders/{orderId}/pay"),
                "graphRelationConfidence", 0.87d,
                "graphSourceMemoryIds", List.of("mem-pay-401", "mem-tenant-bootstrap"),
                "graphSourceRefs", List.of("ltm/pay-401", "ltm/tenant-bootstrap"),
                "graphFactFingerprints", List.of("fact-pay-401-graph", "fact-bootstrap"),
                "graphEvidenceSummaries", List.of("PAY_401 and tenant bootstrap co-occurred across two suites"),
                "conflictAudit", List.of(Map.of(
                    "summary", "old runbook suggested retry without bootstrap",
                    "role", "audit",
                    "sourceRefs", List.of("runbook/old-pay-401")
                )),
                "conflictSignals", List.of("contradicts-old-runbook")
            ),
            ledger(0.86d, false, List.of("contradicts-old-runbook"), 96)
        );

        var result = service.expand(new MemoryEvidenceExpansionRequest(List.of(outputItem(candidate)), 500));

        var item = result.items().getFirst();
        assertThat(item.anchor().anchorType()).isEqualTo(MemoryEvidenceAnchorType.GRAPH_RELATION);
        assertThat(item.graphRelation()).isNotNull();
        assertThat(item.graphRelation().relationPath()).containsExactly(
            "ERROR_CODE:PAY_401",
            "API_PATH:/api/orders/{orderId}/pay"
        );
        assertThat(item.graphRelation().relationConfidence()).isEqualTo(0.87d);
        assertThat(item.graphRelation().sourceMemoryIds())
            .containsExactly("mem-pay-401", "mem-tenant-bootstrap");
        assertThat(item.graphRelation().graphEvidenceSummary())
            .isEqualTo("PAY_401 and tenant bootstrap co-occurred across two suites");
        assertThat(item.conflictAudit()).extracting(MemoryConflictAuditEvidence::role)
            .contains(MemoryEvidenceRole.AUDIT, MemoryEvidenceRole.CONFLICT);
        assertThat(item.positiveRecommendationEligible()).isFalse();
        assertThat(item.citations()).extracting(MemoryEvidenceCitation::role)
            .contains(MemoryEvidenceRole.GRAPH_RELATION, MemoryEvidenceRole.AUDIT, MemoryEvidenceRole.CONFLICT);
    }

    @Test
    void marksLowConfidenceAndPrunesEvidenceWithinBudget() {
        var candidate = memoryCandidate(
            "mem-low-confidence",
            "Weak historical hint",
            "Long content that should be trimmed when budget is tiny. ".repeat(20),
            Map.of(
                "factFingerprint", "fact-low-confidence",
                "evidenceSummaries", List.of(
                    "first evidence is most important and should survive",
                    "second evidence may be pruned"
                ),
                "sourceRefs", List.of("ltm/weak-hint"),
                "lowConfidenceReason", "only one weak historical observation"
            ),
            ledger(0.42d, true, List.of(), 700)
        );

        var result = service.expand(new MemoryEvidenceExpansionRequest(List.of(outputItem(candidate)), 60));

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.lowConfidence()).isTrue();
        assertThat(item.lowConfidenceReason()).isEqualTo("only one weak historical observation");
        assertThat(item.positiveRecommendationEligible()).isFalse();
        assertThat(item.estimatedTokens()).isLessThanOrEqualTo(60);
        assertThat(item.pruningReasons()).anySatisfy(reason ->
            assertThat(reason).contains("token-budget-exceeded")
        );
        assertThat(result.pruned()).isTrue();
        assertThat(result.totalEstimatedTokens()).isLessThanOrEqualTo(60);
    }

    private RerankFeatureLedger ledger(
        Double memoryConfidence,
        boolean lowConfidence,
        List<String> conflictSignals,
        int tokenCost
    ) {
        return new RerankFeatureLedger(
            0.72d,
            0.68d,
            0.52d,
            0.84d,
            1.0d,
            0.86d,
            null,
            0.74d,
            memoryConfidence,
            0.78d,
            0.70d,
            0.82d,
            2,
            lowConfidence,
            conflictSignals,
            tokenCost,
            List.of()
        );
    }

    private RerankCandidate memoryCandidate(
        String id,
        String title,
        String content,
        Map<String, Object> metadata,
        RerankFeatureLedger ledger
    ) {
        return new RerankCandidate(
            RerankCorpusType.MEMORY,
            new RerankCandidateIdentity(RerankCorpusType.MEMORY, id),
            new RerankSourceIdentity("FAILURE_PATTERN", "ltm/" + id, Map.of(
                "memoryId", id,
                "factFingerprint", metadata.getOrDefault("factFingerprint", "fact-" + id).toString()
            )),
            title,
            content,
            1,
            0.86d,
            RerankRouteEvidence.empty(),
            ledger,
            metadata,
            ledger.tokenCost(),
            List.of()
        );
    }

    private RerankOutputItem outputItem(RerankCandidate candidate) {
        return new RerankOutputItem(
            candidate,
            candidate.beforeRank(),
            1,
            0.91d,
            "fixture",
            List.of("fixture"),
            List.of()
        );
    }
}
