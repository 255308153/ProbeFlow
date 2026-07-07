package com.probeflow.testagent.suitedraft;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowCandidate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SuiteDraftGenerationRequest(
    String fixtureId,
    BusinessFlowCandidate candidate,
    List<ApiSpec> apiSpecs,
    List<SuiteSingleCaseTemplate> singleCaseTemplates,
    SuiteDraftProviderMode providerMode,
    boolean allowManualProvider,
    List<SuiteDependencyHint> dependencyHints,
    SuiteDraftGenerationOptions options,
    String runProfile,
    Map<String, Object> metadata
) {

    public SuiteDraftGenerationRequest {
        apiSpecs = apiSpecs == null ? List.of() : List.copyOf(apiSpecs);
        singleCaseTemplates = singleCaseTemplates == null ? List.of() : List.copyOf(singleCaseTemplates);
        providerMode = providerMode == null ? SuiteDraftProviderMode.DETERMINISTIC_FAKE : providerMode;
        dependencyHints = dependencyHints == null ? List.of() : List.copyOf(dependencyHints);
        options = options == null ? SuiteDraftGenerationOptions.defaults() : options;
        runProfile = runProfile == null || runProfile.isBlank() ? "local-fake" : runProfile;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    public static SuiteDraftGenerationRequest deterministic(
        String fixtureId,
        BusinessFlowCandidate candidate,
        List<ApiSpec> apiSpecs
    ) {
        return new SuiteDraftGenerationRequest(
            fixtureId,
            candidate,
            apiSpecs,
            List.of(),
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(),
            SuiteDraftGenerationOptions.defaults(),
            "local-fake",
            Map.of()
        );
    }
}
