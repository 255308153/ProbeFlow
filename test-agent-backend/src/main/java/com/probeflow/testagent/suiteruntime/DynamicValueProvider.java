package com.probeflow.testagent.suiteruntime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DynamicValueProvider {

    private static final String FIXED_UUID = "00000000-0000-4000-8000-000000000001";
    private static final Instant FIXED_NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final String FIXED_EMAIL = "probe.user@example.test";
    private static final String FIXED_PHONE = "15500000000";

    public DynamicValueResult provide(String scope, String functionName, String rawArguments) {
        return switch (scope + "." + functionName) {
            case "fn.uuid" -> noArgs(functionName, rawArguments, FIXED_UUID);
            case "fn.now" -> noArgs(functionName, rawArguments, FIXED_NOW.toString());
            case "data.randomEmail" -> noArgs(functionName, rawArguments, FIXED_EMAIL);
            case "data.randomPhone" -> noArgs(functionName, rawArguments, FIXED_PHONE);
            case "data.randomFrom" -> randomFrom(rawArguments);
            default -> DynamicValueResult.failure(
                "UNSUPPORTED_DYNAMIC_FUNCTION",
                "Unsupported dynamic function: " + scope + "." + functionName + "()"
            );
        };
    }

    private DynamicValueResult noArgs(String functionName, String rawArguments, Object value) {
        if (rawArguments != null && !rawArguments.isBlank()) {
            return DynamicValueResult.failure(
                "INVALID_DYNAMIC_FUNCTION_ARGUMENTS",
                "Dynamic function " + functionName + " does not accept arguments"
            );
        }
        return DynamicValueResult.success(value);
    }

    private DynamicValueResult randomFrom(String rawArguments) {
        var candidates = splitArguments(rawArguments);
        if (candidates.isEmpty()) {
            return DynamicValueResult.failure(
                "INVALID_DYNAMIC_FUNCTION_ARGUMENTS",
                "data.randomFrom requires at least one candidate"
            );
        }
        return DynamicValueResult.success(candidates.getFirst());
    }

    private List<String> splitArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        var current = new StringBuilder();
        var quote = '\0';
        for (var index = 0; index < rawArguments.length(); index++) {
            var character = rawArguments.charAt(index);
            if ((character == '\'' || character == '"') && (index == 0 || rawArguments.charAt(index - 1) != '\\')) {
                if (quote == '\0') {
                    quote = character;
                } else if (quote == character) {
                    quote = '\0';
                } else {
                    current.append(character);
                }
                continue;
            }
            if (character == ',' && quote == '\0') {
                addArgument(values, current);
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        addArgument(values, current);
        return values;
    }

    private void addArgument(List<String> values, StringBuilder current) {
        var value = current.toString().trim();
        if (!value.isEmpty()) {
            values.add(value);
        }
    }
}
