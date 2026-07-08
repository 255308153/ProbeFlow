package com.probeflow.testagent.demorun;

import java.nio.file.Path;

public record DemoRunRequest(
    String fixtureId,
    DemoRunProviderMode providerMode,
    String runProfile,
    Path outputDirectory
) {

    private static final String DEFAULT_FIXTURE_ID = "order-suite-demo";
    private static final String DEFAULT_RUN_PROFILE = "local-demo";
    private static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("target", "v4-demo-run");

    public DemoRunRequest {
        fixtureId = fixtureId == null || fixtureId.isBlank() ? DEFAULT_FIXTURE_ID : fixtureId;
        providerMode = providerMode == null ? DemoRunProviderMode.FAKE : providerMode;
        runProfile = runProfile == null || runProfile.isBlank() ? DEFAULT_RUN_PROFILE : runProfile;
        outputDirectory = outputDirectory == null ? DEFAULT_OUTPUT_DIRECTORY : outputDirectory;
    }

    public static DemoRunRequest fakeBaseline(String fixtureId, Path outputDirectory) {
        return new DemoRunRequest(fixtureId, DemoRunProviderMode.FAKE, DEFAULT_RUN_PROFILE, outputDirectory);
    }
}
