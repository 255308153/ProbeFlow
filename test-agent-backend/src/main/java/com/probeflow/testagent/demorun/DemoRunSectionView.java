package com.probeflow.testagent.demorun;

import java.util.LinkedHashMap;
import java.util.Map;

public record DemoRunSectionView(
    String sectionId,
    String title,
    DemoRunSectionSource source,
    String status,
    Map<String, Object> summary
) {

    public DemoRunSectionView {
        source = source == null ? DemoRunSectionSource.APPLICATION : source;
        status = status == null || status.isBlank() ? "READY" : status;
        summary = summary == null ? Map.of() : new LinkedHashMap<>(summary);
    }

    public static DemoRunSectionView notRun(String sectionId, String title) {
        return new DemoRunSectionView(sectionId, title, DemoRunSectionSource.NOT_RUN, "NOT_RUN", Map.of());
    }
}
