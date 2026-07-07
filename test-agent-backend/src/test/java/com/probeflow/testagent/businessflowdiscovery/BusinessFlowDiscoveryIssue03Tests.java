package com.probeflow.testagent.businessflowdiscovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessFlowDiscoveryIssue03Tests {

    @Test
    void knowledgeBusinessFlowEvidenceIsAttributedWithChunkDocumentAndRevisionRefs() {
        var knowledge = new KnowledgeContextEntry(
            "chunk-order-flow-001",
            "doc-order-flow",
            "rev-order-flow-v1",
            "Business flow: create order, pay order, query order",
            0.92,
            "business_flow",
            "fixture://order-suite-demo/business-flow.md",
            Map.of("docType", "business_flow", "module", "order"),
            List.of("explicit step order", "mentions create/pay/query"),
            false
        );
        var request = new BusinessFlowDiscoveryRequest(
            "order-suite-demo",
            BusinessFlowDiscoveryIssue01Tests.orderApiSpecs(),
            List.of(knowledge),
            List.of(),
            List.of(),
            BusinessFlowDiscoveryProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(),
            "local-fake",
            Map.of()
        );

        var result = new BusinessFlowDiscoveryService().discover(request);

        var candidate = result.candidates().get(0);
        assertThat(candidate.sourceCoverage().knowledgeEvidenceCount()).isEqualTo(1);
        assertThat(result.sourceCoverage().knowledgeEvidenceCount()).isEqualTo(1);
        assertThat(result.metadata().get("basedOnKnowledgeRefs").toString())
            .contains("chunk-order-flow-001", "doc-order-flow");

        var evidence = candidate.evidence().stream()
            .filter(item -> item.source() == BusinessFlowEvidenceSource.KNOWLEDGE)
            .findFirst()
            .orElseThrow();
        assertThat(evidence.refs()).contains("chunk-order-flow-001", "doc-order-flow");
        assertThat(evidence.metadata())
            .containsEntry("documentId", "doc-order-flow")
            .containsEntry("documentRevisionId", "rev-order-flow-v1")
            .containsEntry("evidenceType", "business_flow")
            .containsEntry("sourceRef", "fixture://order-suite-demo/business-flow.md");
        assertThat(evidence.metadata().get("matchReasons").toString())
            .contains("explicit step order", "mentions create/pay/query");
        assertThat(candidate.confidence()).isGreaterThan(0.80);
    }

    @Test
    void conflictingKnowledgeEvidenceCreatesReviewBlocker() {
        var conflictingKnowledge = new KnowledgeContextEntry(
            "chunk-conflict",
            "doc-conflict",
            "rev-conflict",
            "Business flow conflict: query before create",
            0.80,
            "business_flow",
            "fixture://conflict.md",
            Map.of("conflict", true),
            List.of("conflicting order"),
            false
        );
        var request = new BusinessFlowDiscoveryRequest(
            "order-suite-demo",
            BusinessFlowDiscoveryIssue01Tests.orderApiSpecs(),
            List.of(conflictingKnowledge),
            List.of(),
            List.of(),
            BusinessFlowDiscoveryProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(),
            "local-fake",
            Map.of()
        );

        var result = new BusinessFlowDiscoveryService().discover(request);

        assertThat(result.status()).isEqualTo(BusinessFlowDiscoveryStatus.BLOCKED);
        assertThat(result.candidates().get(0).requiresHumanReview()).isTrue();
        assertThat(result.blockers())
            .extracting(BusinessFlowDiscoveryBlocker::code)
            .contains("CONFLICTING_EVIDENCE");
    }
}
