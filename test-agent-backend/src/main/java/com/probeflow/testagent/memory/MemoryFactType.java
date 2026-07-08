package com.probeflow.testagent.memory;

import java.util.Locale;

public enum MemoryFactType {
    FAILURE_PATTERN,
    TESTING_PATTERN,
    PROJECT_KNOWLEDGE,
    PREFERENCE,
    POLICY_LEARNING,
    SUITE_DEPENDENCY_FACT,
    VARIABLE_EXTRACTION_FACT,
    BUSINESS_PRECONDITION_FACT;

    public String metadataValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
