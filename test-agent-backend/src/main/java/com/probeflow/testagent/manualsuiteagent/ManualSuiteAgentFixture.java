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
        return new ManualSuiteAgentFixture(
            "order-suite-demo",
            "2026.07.order.v1",
            "Order payment suite demo",
            "Deterministic order create, pay and query fixture for the V3 manual suite harness.",
            List.of("v3-1", "suite", "order", "fake-http"),
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
