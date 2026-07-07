package com.probeflow.testagent.businessflowdiscovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessFlowDiscoveryIssue04Tests {

    @Test
    void memoryEvidenceIsAttributedAndContributesToCoverage() {
        var memoryHit = new LongTermMemoryRetrievalHit(
            "mem-order-flow",
            MemoryScopeType.TESTING_PATTERN,
            "Order payment flows should query final order status after payment.",
            "Create order before pay order, then query order by orderId.",
            "Create order before pay order, then query order by orderId.",
            List.of("order", "payment", "business-flow"),
            MemorySourceType.USER_FEEDBACK,
            "task-v2-order-review",
            0.88f,
            0.70f,
            0.65f,
            4,
            Instant.parse("2026-07-01T00:00:00Z"),
            Map.of("module", "order"),
            42,
            0.91,
            Map.of("tag", 0.5),
            List.of("same order module", "mentions final query"),
            false
        );
        var request = new BusinessFlowDiscoveryRequest(
            "order-suite-demo",
            BusinessFlowDiscoveryIssue01Tests.orderApiSpecs(),
            List.of(),
            List.of(memoryHit),
            List.of(),
            BusinessFlowDiscoveryProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(),
            "local-fake",
            Map.of()
        );

        var result = new BusinessFlowDiscoveryService().discover(request);
        var candidate = result.candidates().get(0);

        assertThat(candidate.sourceCoverage().memoryEvidenceCount()).isEqualTo(1);
        assertThat(result.metadata().get("basedOnMemoryRefs").toString()).contains("mem-order-flow");
        var evidence = candidate.evidence().stream()
            .filter(item -> item.source() == BusinessFlowEvidenceSource.MEMORY)
            .findFirst()
            .orElseThrow();
        assertThat(evidence.refs()).containsExactly("mem-order-flow");
        assertThat(evidence.metadata())
            .containsEntry("sourceType", "USER_FEEDBACK")
            .containsEntry("sourceRef", "task-v2-order-review");
        assertThat(evidence.metadata().get("tags").toString()).contains("business-flow");
    }

    @Test
    void userSelectedApiSpecOrderIsPreservedAsEvidence() {
        var request = new BusinessFlowDiscoveryRequest(
            "order-suite-demo",
            BusinessFlowDiscoveryIssue01Tests.orderApiSpecs(),
            List.of(),
            List.of(),
            List.of("api-order-create", "api-order-query", "api-order-pay"),
            BusinessFlowDiscoveryProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(),
            "local-fake",
            Map.of()
        );

        var result = new BusinessFlowDiscoveryService().discover(request);
        var candidate = result.candidates().get(0);

        assertThat(candidate.steps())
            .extracting(BusinessFlowDiscoveryStep::apiSpecId)
            .containsExactly("api-order-create", "api-order-query", "api-order-pay");
        assertThat(candidate.sourceCoverage().userSelectionEvidenceCount()).isEqualTo(1);
        assertThat(candidate.evidence())
            .filteredOn(item -> item.source() == BusinessFlowEvidenceSource.USER_SELECTION)
            .singleElement()
            .satisfies(evidence -> assertThat(evidence.refs())
                .containsExactly("api-order-create", "api-order-query", "api-order-pay"));
    }
}
