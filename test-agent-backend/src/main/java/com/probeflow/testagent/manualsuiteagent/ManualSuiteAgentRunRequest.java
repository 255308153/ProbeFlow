package com.probeflow.testagent.manualsuiteagent;

import java.nio.file.Path;

public record ManualSuiteAgentRunRequest(
    String fixtureId,
    ManualSuiteAgentProviderMode providerMode,
    Path outputDirectory,
    boolean allowManualRealLlm,
    boolean allowExternalHttp,
    String runProfile
) {

    private static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("target", "v3-manual-suite-agent");

    public ManualSuiteAgentRunRequest {
        fixtureId = blankToDefault(fixtureId, "v3-smoke");
        providerMode = providerMode == null ? ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE : providerMode;
        outputDirectory = outputDirectory == null ? DEFAULT_OUTPUT_DIRECTORY : outputDirectory;
        runProfile = blankToDefault(runProfile, "local-demo");
    }

    public static ManualSuiteAgentRunRequest fake(String fixtureId, Path outputDirectory) {
        return new ManualSuiteAgentRunRequest(
            fixtureId,
            ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE,
            outputDirectory,
            false,
            false,
            "local-demo"
        );
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
