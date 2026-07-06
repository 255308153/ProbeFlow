package com.probeflow.testagent.agentpolicy;

import java.util.Objects;
import java.util.regex.Pattern;

public record ToolName(String value) {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+");

    public ToolName {
        value = clean(value);
        if (!TOOL_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("tool name must use lowercase dot-separated protocol");
        }
    }

    public static ToolName of(String value) {
        return new ToolName(value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        return Objects.requireNonNull(value).trim();
    }
}
