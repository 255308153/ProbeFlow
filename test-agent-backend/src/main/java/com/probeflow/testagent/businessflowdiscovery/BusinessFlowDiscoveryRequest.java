package com.probeflow.testagent.businessflowdiscovery;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BusinessFlowDiscoveryRequest(
    String fixtureId,
    List<ApiSpec> apiSpecs,
    List<KnowledgeContextEntry> knowledgeEntries,
    List<LongTermMemoryRetrievalHit> memoryHits,
    List<String> selectedApiSpecIds,
    BusinessFlowDiscoveryProviderMode providerMode,
    boolean allowManualRealLlm,
    List<BusinessFlowDiscoveryEvidence> llmSuggestionEvidence,
    String runProfile,
    Map<String, Object> metadata
) {

    public BusinessFlowDiscoveryRequest {
        apiSpecs = apiSpecs == null ? List.of() : List.copyOf(apiSpecs);
        knowledgeEntries = knowledgeEntries == null ? List.of() : List.copyOf(knowledgeEntries);
        memoryHits = memoryHits == null ? List.of() : List.copyOf(memoryHits);
        selectedApiSpecIds = selectedApiSpecIds == null ? List.of() : List.copyOf(selectedApiSpecIds);
        providerMode = providerMode == null ? BusinessFlowDiscoveryProviderMode.DETERMINISTIC_FAKE : providerMode;
        llmSuggestionEvidence = llmSuggestionEvidence == null ? List.of() : List.copyOf(llmSuggestionEvidence);
        runProfile = runProfile == null || runProfile.isBlank() ? "local-fake" : runProfile;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    public static BusinessFlowDiscoveryRequest deterministic(String fixtureId, List<ApiSpec> apiSpecs) {
        return new BusinessFlowDiscoveryRequest(
            fixtureId,
            apiSpecs,
            List.of(),
            List.of(),
            List.of(),
            BusinessFlowDiscoveryProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(),
            "local-fake",
            Map.of()
        );
    }
}
