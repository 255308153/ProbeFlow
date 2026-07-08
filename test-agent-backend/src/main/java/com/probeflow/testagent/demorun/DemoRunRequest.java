package com.probeflow.testagent.demorun;

import java.nio.file.Path;
import java.util.List;

public record DemoRunRequest(
    String fixtureId,
    DemoRunProviderMode providerMode,
    String runProfile,
    Path outputDirectory,
    boolean comparisonEnabled,
    boolean allowMemoryWrite,
    List<String> outputFormats
) {

    private static final String DEFAULT_FIXTURE_ID = "order-suite-demo";
    private static final String DEFAULT_RUN_PROFILE = "local-demo";
    private static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("target", "v4-demo-run");
    private static final List<String> DEFAULT_OUTPUT_FORMATS = List.of("JSON_REPORT", "MARKDOWN_REPORT");

    public DemoRunRequest(String fixtureId, DemoRunProviderMode providerMode, String runProfile, Path outputDirectory) {
        this(fixtureId, providerMode, runProfile, outputDirectory, false, false, DEFAULT_OUTPUT_FORMATS);
    }

    public DemoRunRequest {
        fixtureId = fixtureId == null || fixtureId.isBlank() ? DEFAULT_FIXTURE_ID : fixtureId;
        providerMode = providerMode == null ? DemoRunProviderMode.FAKE : providerMode;
        runProfile = runProfile == null || runProfile.isBlank() ? DEFAULT_RUN_PROFILE : runProfile;
        outputDirectory = outputDirectory == null ? DEFAULT_OUTPUT_DIRECTORY : outputDirectory;
        outputFormats = outputFormats == null || outputFormats.isEmpty()
            ? DEFAULT_OUTPUT_FORMATS
            : List.copyOf(outputFormats);
    }

    public static DemoRunRequest fakeBaseline(String fixtureId, Path outputDirectory) {
        return new DemoRunRequest(fixtureId, DemoRunProviderMode.FAKE, DEFAULT_RUN_PROFILE, outputDirectory);
    }
}
