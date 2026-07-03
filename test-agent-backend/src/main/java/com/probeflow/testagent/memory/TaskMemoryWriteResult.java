package com.probeflow.testagent.memory;

public record TaskMemoryWriteResult(
    String memoryId,
    boolean created,
    boolean duplicateSuppressed
) {
}
