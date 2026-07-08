package com.probeflow.testagent.manualsuiteagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ManualSuiteAgentFixture(
    String fixtureId,
    String fixtureVersion,
    String displayName,
    String description,
    List<String> capabilityTags,
    Map<String, Object> metadata
) {

    public ManualSuiteAgentFixture {
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        metadata = ordered(metadata);
    }

    static ManualSuiteAgentFixture smoke() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("fixtureType", "smoke");
        metadata.put("containsTaskModel", false);
        metadata.put("containsApiSpecModel", false);
        metadata.put("containsTestCaseModel", false);
        metadata.put("usesFixtureProvider", true);
        return new ManualSuiteAgentFixture(
            "v3-smoke",
            "2026.07.v1",
            "V3 harness smoke fixture",
            "Minimal deterministic fixture for Manual Suite Agent Harness smoke runs.",
            List.of("v3-1", "smoke", "manual-suite-agent"),
            metadata
        );
    }

    static ManualSuiteAgentFixture orderSuiteDemo() {
        return orderSuiteFixture(
            "order-suite-demo",
            "Order payment suite demo",
            "Deterministic order create, pay and query fixture for the V3 manual suite harness.",
            null
        );
    }

    static ManualSuiteAgentFixture orderSuiteFailure(String fixtureId, String displayName, String failureScenario) {
        return orderSuiteFixture(
            fixtureId,
            displayName,
            "Deterministic order suite failure fixture for V3-5 failure-analysis harness demos.",
            failureScenario
        );
    }

    private static ManualSuiteAgentFixture orderSuiteFixture(
        String fixtureId,
        String displayName,
        String description,
        String failureScenario
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("fixtureType", "order-suite-demo");
        metadata.put("baseUrl", "https://fixture.local");
        metadata.put("tenant", "tenant-demo");
        metadata.put("auth", Map.of(
            "Authorization", "Bearer order-demo-token",
            "Cookie", "session=session-cookie-secret"
        ));
        metadata.put("businessSteps", List.of("create-order", "pay-order", "query-order"));
        metadata.put("usesFixtureProvider", true);
        metadata.put("usesFakeHttpGateway", true);
        if (failureScenario != null && !failureScenario.isBlank()) {
            metadata.put("failureScenario", failureScenario);
        }
        return new ManualSuiteAgentFixture(
            fixtureId,
            "2026.07.order.v1",
            displayName,
            description,
            failureScenario == null || failureScenario.isBlank()
                ? List.of("v3-1", "suite", "order", "fake-http")
                : List.of("v3-5", "suite", "order", "fake-http", "failure-analysis", failureScenario),
            metadata
        );
    }

    static ManualSuiteAgentFixture invalidFixture() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("fixtureType", "invalid");
        metadata.put("token", "invalid-fixture-token");
        metadata.put("headers", Map.of(
            "Authorization", "Bearer invalid-fixture-token",
            "Cookie", "secret-cookie"
        ));
        metadata.put("body", Map.of(
            "password", "invalid-password",
            "apiKey", "api-key-123"
        ));
        return new ManualSuiteAgentFixture(
            "v3-invalid-fixture",
            "",
            "",
            "Invalid fixture used to exercise structured diagnostics.",
            List.of("invalid"),
            metadata
        );
    }

    ManualSuiteAgentFixtureSummary summary() {
        return new ManualSuiteAgentFixtureSummary(
            fixtureId,
            fixtureVersion,
            displayName,
            description,
            capabilityTags,
            metadata
        );
    }

    private static Map<String, Object> ordered(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(source);
    }
}
