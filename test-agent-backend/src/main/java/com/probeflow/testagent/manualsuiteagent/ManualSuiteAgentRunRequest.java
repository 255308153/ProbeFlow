package com.probeflow.testagent.manualsuiteagent;

import java.nio.file.Path;
import java.util.Locale;

public record ManualSuiteAgentRunRequest(
    String fixtureId,
    ManualSuiteAgentProviderMode providerMode,
    Path outputDirectory,
    boolean allowManualProvider,
    boolean allowExternalHttp,
    String runProfile,
    String requestedProviderMode
) {

    private static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("target", "v3-manual-suite-agent");

    public ManualSuiteAgentRunRequest {
        fixtureId = blankToDefault(fixtureId, "v3-smoke");
        providerMode = providerMode == null ? ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE : providerMode;
        outputDirectory = outputDirectory == null ? DEFAULT_OUTPUT_DIRECTORY : outputDirectory;
        runProfile = blankToDefault(runProfile, "local-demo");
        requestedProviderMode = blankToDefault(requestedProviderMode, providerMode.name());
    }

    public static ManualSuiteAgentRunRequest fake(String fixtureId, Path outputDirectory) {
        return new ManualSuiteAgentRunRequest(
            fixtureId,
            ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE,
            outputDirectory,
            false,
            false,
            "local-demo",
            ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE.name()
        );
    }

    public static ManualSuiteAgentRunRequest manualLlmProvider(
        String fixtureId,
        Path outputDirectory,
        boolean allowManualProvider
    ) {
        return new ManualSuiteAgentRunRequest(
            fixtureId,
            ManualSuiteAgentProviderMode.MANUAL_REAL_LLM,
            outputDirectory,
            allowManualProvider,
            false,
            "local-demo",
            ManualSuiteAgentProviderMode.MANUAL_REAL_LLM.name()
        );
    }

    public static ManualSuiteAgentRunRequest withProviderMode(
        String fixtureId,
        String providerModeName,
        Path outputDirectory
    ) {
        var parsed = parseProviderMode(providerModeName);
        return new ManualSuiteAgentRunRequest(
            fixtureId,
            parsed,
            outputDirectory,
            false,
            false,
            "local-demo",
            providerModeName
        );
    }

    private static ManualSuiteAgentProviderMode parseProviderMode(String providerModeName) {
        if (providerModeName == null || providerModeName.isBlank()) {
            return ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE;
        }
        try {
            return ManualSuiteAgentProviderMode.valueOf(providerModeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ManualSuiteAgentProviderMode.UNSUPPORTED;
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
