package com.probeflow.testagent.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.DocumentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSmallToBigExpansionIssue05Tests {

    private final KnowledgeContextExpander service = new KnowledgeContextExpander();

    @Test
    void expandsErrorCodeGuideToSameErrorCodeEntryWithoutStaleSources() {
        var anchor = source(
            "err-pay-401-cause",
            "doc-payment-errors",
            "rev-errors-v3",
            DocumentType.ERROR_CODE_GUIDE,
            "PAY_401 cause",
            "PAY_401 is raised when tenant bootstrap did not prepare payment auth.",
            "wiki/payment-errors.md#pay-401-cause",
            "error_code:PAY_401",
            "Payment error codes",
            "PAY_401",
            "Payment",
            null,
            10,
            42
        );
        var action = source(
            "err-pay-401-action",
            "doc-payment-errors",
            "rev-errors-v3",
            DocumentType.ERROR_CODE_GUIDE,
            "PAY_401 handling",
            "Restore tenant bootstrap before retrying payment authorization.",
            "wiki/payment-errors.md#pay-401-action",
            "error_code:PAY_401",
            "Payment error codes",
            "PAY_401",
            "Payment",
            null,
            11,
            38
        );
        var unrelated = source(
            "err-pay-402-action",
            "doc-payment-errors",
            "rev-errors-v3",
            DocumentType.ERROR_CODE_GUIDE,
            "PAY_402 handling",
            "PAY_402 belongs to settlement verification and must not enter PAY_401 context.",
            "wiki/payment-errors.md#pay-402-action",
            "error_code:PAY_402",
            "Payment error codes",
            "PAY_402",
            "Payment",
            null,
            12,
            36
        );
        var oldRevision = source(
            "err-pay-401-old",
            "doc-payment-errors",
            "rev-errors-v2",
            DocumentType.ERROR_CODE_GUIDE,
            "PAY_401 old handling",
            "Old PAY_401 remediation from a superseded revision.",
            "wiki/payment-errors-v2.md#pay-401",
            "error_code:PAY_401",
            "Payment error codes",
            "PAY_401",
            "Payment",
            null,
            9,
            30,
            KnowledgeExpansionSourceStatus.SUPERSEDED,
            false
        );

        var result = service.expand(new KnowledgeExpansionRequest(
            List.of(rerankedKnowledge(anchor, 1)),
            List.of(anchor, action, unrelated, oldRevision),
            120
        ));

        assertThat(result.contexts()).hasSize(1);
        var context = result.contexts().getFirst();
        assertThat(context.anchorChunkId()).isEqualTo("err-pay-401-cause");
        assertThat(context.parentIdentity()).isEqualTo("error_code:PAY_401");
        assertThat(context.expansionReason()).isEqualTo(KnowledgeExpansionReason.ERROR_CODE_ENTRY);
        assertThat(context.sourceRevision()).isEqualTo("rev-errors-v3");
        assertThat(context.tokenCost()).isLessThanOrEqualTo(120);
        assertThat(context.sources()).extracting(KnowledgeExpansionSource::chunkId)
            .containsExactly("err-pay-401-cause", "err-pay-401-action");
        assertThat(context.sources()).extracting(KnowledgeExpansionSource::chunkId)
            .doesNotContain("err-pay-402-action", "err-pay-401-old");
        assertThat(context.citations()).extracting(KnowledgeExpansionCitation::role)
            .contains(KnowledgeExpansionCitationRole.ANCHOR, KnowledgeExpansionCitationRole.EXPANDED_CONTEXT);
        assertThat(context.citations()).extracting(KnowledgeExpansionCitation::sourceChunkId)
            .contains("err-pay-401-cause", "err-pay-401-action");
    }

    @Test
    void expandsTestSpecToRuleGroupAndRecordsTokenBudgetPruning() {
        var anchor = source(
            "spec-page-size-max",
            "doc-test-spec",
            "rev-test-spec-v5",
            DocumentType.TEST_SPEC,
            "Page size maximum",
            "When pageSize is above 200 the API must reject the request.",
            "wiki/test-spec.md#page-size-max",
            "test_spec:pagination-boundary",
            "Pagination boundary rules",
            "pagination-boundary",
            "OrderSearch",
            null,
            20,
            35
        );
        var lowerBound = source(
            "spec-page-size-min",
            "doc-test-spec",
            "rev-test-spec-v5",
            DocumentType.TEST_SPEC,
            "Page size minimum",
            "pageSize below 1 is invalid and must return a validation error.",
            "wiki/test-spec.md#page-size-min",
            "test_spec:pagination-boundary",
            "Pagination boundary rules",
            "pagination-boundary",
            "OrderSearch",
            null,
            21,
            45
        );
        var overBudget = source(
            "spec-page-size-sort",
            "doc-test-spec",
            "rev-test-spec-v5",
            DocumentType.TEST_SPEC,
            "Sort boundary",
            "Sort direction boundaries share the same rule group but are too large for this budget.",
            "wiki/test-spec.md#sort-boundary",
            "test_spec:pagination-boundary",
            "Pagination boundary rules",
            "pagination-boundary",
            "OrderSearch",
            null,
            22,
            80
        );

        var result = service.expand(new KnowledgeExpansionRequest(
            List.of(rerankedKnowledge(anchor, 1)),
            List.of(anchor, lowerBound, overBudget),
            90
        ));

        var context = result.contexts().getFirst();
        assertThat(context.expansionReason()).isEqualTo(KnowledgeExpansionReason.TEST_SPEC_RULE_GROUP);
        assertThat(context.sources()).extracting(KnowledgeExpansionSource::chunkId)
            .containsExactly("spec-page-size-max", "spec-page-size-min");
        assertThat(context.tokenCost()).isEqualTo(80);
        assertThat(context.pruningReasons()).singleElement()
            .satisfies(pruning -> {
                assertThat(pruning.sourceChunkId()).isEqualTo("spec-page-size-sort");
                assertThat(pruning.reason()).isEqualTo(KnowledgeExpansionPruningReason.TOKEN_BUDGET_EXCEEDED);
            });
    }

    @Test
    void expandsBusinessFlowToAdjacentStepsForCurrentEntityOnly() {
        var reserveInventory = source(
            "flow-checkout-01-reserve",
            "doc-checkout-flow",
            "rev-checkout-v2",
            DocumentType.BUSINESS_FLOW,
            "Reserve inventory",
            "Reserve inventory before payment authorization.",
            "wiki/checkout-flow.md#reserve",
            "business_flow:checkout",
            "Checkout flow",
            "checkout",
            "Order",
            "checkout",
            1,
            30
        );
        var authorizePayment = source(
            "flow-checkout-02-pay",
            "doc-checkout-flow",
            "rev-checkout-v2",
            DocumentType.BUSINESS_FLOW,
            "Authorize payment",
            "Authorize payment after inventory is reserved.",
            "wiki/checkout-flow.md#pay",
            "business_flow:checkout",
            "Checkout flow",
            "checkout",
            "Order",
            "checkout",
            2,
            34
        );
        var createShipment = source(
            "flow-checkout-03-ship",
            "doc-checkout-flow",
            "rev-checkout-v2",
            DocumentType.BUSINESS_FLOW,
            "Create shipment",
            "Create shipment only after payment authorization succeeds.",
            "wiki/checkout-flow.md#ship",
            "business_flow:checkout",
            "Checkout flow",
            "checkout",
            "Order",
            "checkout",
            3,
            36
        );
        var farAwayStep = source(
            "flow-checkout-04-notify",
            "doc-checkout-flow",
            "rev-checkout-v2",
            DocumentType.BUSINESS_FLOW,
            "Notify customer",
            "Notification is outside the immediate neighboring flow window.",
            "wiki/checkout-flow.md#notify",
            "business_flow:checkout",
            "Checkout flow",
            "checkout",
            "Order",
            "checkout",
            4,
            28
        );
        var inactiveNeighbor = source(
            "flow-checkout-02b-old-pay",
            "doc-checkout-flow",
            "rev-checkout-v2",
            DocumentType.BUSINESS_FLOW,
            "Old authorize payment",
            "Inactive payment step must not be recalled.",
            "wiki/checkout-flow.md#old-pay",
            "business_flow:checkout",
            "Checkout flow",
            "checkout",
            "Order",
            "checkout",
            2,
            20,
            KnowledgeExpansionSourceStatus.INACTIVE,
            true
        );

        var result = service.expand(new KnowledgeExpansionRequest(
            List.of(rerankedKnowledge(authorizePayment, 1)),
            List.of(reserveInventory, authorizePayment, createShipment, farAwayStep, inactiveNeighbor),
            130
        ));

        var context = result.contexts().getFirst();
        assertThat(context.expansionReason()).isEqualTo(KnowledgeExpansionReason.BUSINESS_FLOW_NEIGHBOR_STEPS);
        assertThat(context.parentIdentity()).isEqualTo("business_flow:checkout");
        assertThat(context.sources()).extracting(KnowledgeExpansionSource::chunkId)
            .containsExactly("flow-checkout-01-reserve", "flow-checkout-02-pay", "flow-checkout-03-ship");
        assertThat(context.sources()).extracting(KnowledgeExpansionSource::chunkId)
            .doesNotContain("flow-checkout-04-notify", "flow-checkout-02b-old-pay");
    }

    private RerankOutputItem rerankedKnowledge(KnowledgeExpansionSource source, int afterRank) {
        var candidate = new RerankCandidate(
            RerankCorpusType.KNOWLEDGE,
            new RerankCandidateIdentity(RerankCorpusType.KNOWLEDGE, source.chunkId()),
            new RerankSourceIdentity(source.documentType().name(), source.sourceRef(), Map.of(
                "chunkId", source.chunkId(),
                "documentId", source.documentId(),
                "documentRevisionId", source.documentRevisionId()
            )),
            source.title(),
            source.content(),
            afterRank,
            0.91d,
            RerankRouteEvidence.empty(),
            new RerankFeatureLedger(
                0.82d,
                0.77d,
                0.61d,
                0.91d,
                1.0d,
                0.94d,
                1.0d,
                0.88d,
                null,
                null,
                null,
                null,
                null,
                false,
                List.of(),
                source.tokenCost(),
                List.of()
            ),
            source.metadata(),
            source.tokenCost(),
            List.of()
        );
        return new RerankOutputItem(
            candidate,
            afterRank,
            afterRank,
            0.91d,
            "fixture",
            List.of("fixture"),
            List.of()
        );
    }

    private KnowledgeExpansionSource source(
        String chunkId,
        String documentId,
        String documentRevisionId,
        DocumentType documentType,
        String title,
        String content,
        String sourceRef,
        String parentIdentity,
        String heading,
        String entryKey,
        String businessEntity,
        String flowId,
        int chunkOrder,
        int tokenCost
    ) {
        return source(
            chunkId,
            documentId,
            documentRevisionId,
            documentType,
            title,
            content,
            sourceRef,
            parentIdentity,
            heading,
            entryKey,
            businessEntity,
            flowId,
            chunkOrder,
            tokenCost,
            KnowledgeExpansionSourceStatus.ACTIVE,
            true
        );
    }

    private KnowledgeExpansionSource source(
        String chunkId,
        String documentId,
        String documentRevisionId,
        DocumentType documentType,
        String title,
        String content,
        String sourceRef,
        String parentIdentity,
        String heading,
        String entryKey,
        String businessEntity,
        String flowId,
        int chunkOrder,
        int tokenCost,
        KnowledgeExpansionSourceStatus status,
        boolean latest
    ) {
        return new KnowledgeExpansionSource(
            chunkId,
            documentId,
            documentRevisionId,
            documentType,
            title,
            content,
            sourceRef,
            parentIdentity,
            heading,
            entryKey,
            businessEntity,
            flowId,
            chunkOrder,
            tokenCost,
            status,
            latest,
            Map.of(
                "parentIdentity", parentIdentity,
                "heading", heading,
                "entryKey", entryKey,
                "businessEntity", businessEntity
            )
        );
    }
}
