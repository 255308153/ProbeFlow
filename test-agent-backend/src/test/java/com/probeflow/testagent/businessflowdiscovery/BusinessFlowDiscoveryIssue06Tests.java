package com.probeflow.testagent.businessflowdiscovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessFlowDiscoveryIssue06Tests {

    @Test
    void manualRealLlmModeIsPolicyBlockedUnlessExplicitlyAllowed() {
        var request = new BusinessFlowDiscoveryRequest(
            "order-suite-demo",
            BusinessFlowDiscoveryIssue01Tests.orderApiSpecs(),
            List.of(),
            List.of(),
            List.of(),
            BusinessFlowDiscoveryProviderMode.MANUAL_REAL_LLM,
            false,
            List.of(),
            "local-fake",
            Map.of()
        );

        var result = new BusinessFlowDiscoveryService().discover(request);

        assertThat(result.status()).isEqualTo(BusinessFlowDiscoveryStatus.BLOCKED);
        assertThat(result.usesRealLlm()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.blockers())
            .extracting(BusinessFlowDiscoveryBlocker::code)
            .containsExactly("LLM_PROVIDER_BLOCKED");
    }

    @Test
    void explicitLlmSuggestionEvidenceIsAttributedWithoutGeneratingSuiteVariables() {
        var llmEvidence = new BusinessFlowDiscoveryEvidence(
            "e-llm-order-flow",
            BusinessFlowEvidenceSource.LLM_SUGGESTION,
            "LLM suggestion agrees that create order should precede payment and final query.",
            0.10,
            List.of("manual-llm-eval-001"),
            Map.of("provider", "manual-real-llm", "policy", "explicitly-allowed")
        );
        var request = new BusinessFlowDiscoveryRequest(
            "order-suite-demo",
            BusinessFlowDiscoveryIssue01Tests.orderApiSpecs(),
            List.of(),
            List.of(),
            List.of(),
            BusinessFlowDiscoveryProviderMode.MANUAL_REAL_LLM,
            true,
            List.of(llmEvidence),
            "manual-eval",
            Map.of()
        );

        var result = new BusinessFlowDiscoveryService().discover(request);
        var candidate = result.candidates().get(0);

        assertThat(result.status()).isEqualTo(BusinessFlowDiscoveryStatus.COMPLETED);
        assertThat(result.usesRealLlm()).isTrue();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(candidate.sourceCoverage().llmSuggestionEvidenceCount()).isEqualTo(1);
        assertThat(candidate.evidence())
            .filteredOn(item -> item.source() == BusinessFlowEvidenceSource.LLM_SUGGESTION)
            .singleElement()
            .satisfies(evidence -> assertThat(evidence.refs()).containsExactly("manual-llm-eval-001"));
        assertThat(candidate.metadata())
            .containsEntry("staysDiscoveryOnly", true)
            .containsEntry("doesNotGenerateSuiteVariables", true);
    }
}
