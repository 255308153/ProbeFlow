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
        var extractionFailure = ManualSuiteAgentFixture.orderSuiteFailure(
            "order-suite-variable-extraction-failure",
            "Order suite variable extraction failure demo",
            "variable-extraction-failure"
        );
        var resolutionFailure = ManualSuiteAgentFixture.orderSuiteFailure(
            "order-suite-variable-resolution-failure",
            "Order suite variable resolution failure demo",
            "variable-resolution-failure"
        );
        var prerequisiteFailure = ManualSuiteAgentFixture.orderSuiteFailure(
            "order-suite-prerequisite-failure",
            "Order suite prerequisite failure demo",
            "prerequisite-step-failure"
        );
        var downstreamFailure = ManualSuiteAgentFixture.orderSuiteFailure(
            "order-suite-downstream-api-failure",
            "Order suite downstream API failure demo",
            "downstream-api-failure"
        );
        var invalidFixture = ManualSuiteAgentFixture.invalidFixture();
        fixtures.put(smoke.fixtureId(), smoke);
        fixtures.put(orderSuiteDemo.fixtureId(), orderSuiteDemo);
        fixtures.put(extractionFailure.fixtureId(), extractionFailure);
        fixtures.put(resolutionFailure.fixtureId(), resolutionFailure);
        fixtures.put(prerequisiteFailure.fixtureId(), prerequisiteFailure);
        fixtures.put(downstreamFailure.fixtureId(), downstreamFailure);
        fixtures.put(invalidFixture.fixtureId(), invalidFixture);
        return new ManualSuiteAgentFixtureRegistry(fixtures);
    }

    public Optional<ManualSuiteAgentFixture> findById(String fixtureId) {
        return Optional.ofNullable(fixtures.get(fixtureId));
    }
}
