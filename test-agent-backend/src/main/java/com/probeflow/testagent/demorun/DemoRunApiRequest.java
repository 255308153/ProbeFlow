package com.probeflow.testagent.demorun;

import java.util.List;

public record DemoRunApiRequest(
    String fixtureId,
    String providerMode,
    String runProfile,
    Boolean comparison,
    Boolean allowMemoryWrite,
    List<String> outputFormats,
    String outputDirectory
) {
}
