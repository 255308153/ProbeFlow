package com.probeflow.testagent.manualsuiteagent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ManualSuiteAgentFixtureRegistry {

    private final Map<String, ManualSuiteAgentFixture> fixtures;

    public ManualSuiteAgentFixtureRegistry(Map<String, ManualSuiteAgentFixture> fixtures) {
        this.fixtures = new LinkedHashMap<>(fixtures);
    }

    public static ManualSuiteAgentFixtureRegistry defaults() {
        var fixtures = new LinkedHashMap<String, ManualSuiteAgentFixture>();
        var smoke = ManualSuiteAgentFixture.smoke();
        var orderSuiteDemo = ManualSuiteAgentFixture.orderSuiteDemo();
        var invalidFixture = ManualSuiteAgentFixture.invalidFixture();
        fixtures.put(smoke.fixtureId(), smoke);
        fixtures.put(orderSuiteDemo.fixtureId(), orderSuiteDemo);
        fixtures.put(invalidFixture.fixtureId(), invalidFixture);
        return new ManualSuiteAgentFixtureRegistry(fixtures);
    }

    public Optional<ManualSuiteAgentFixture> findById(String fixtureId) {
        return Optional.ofNullable(fixtures.get(fixtureId));
    }
}
