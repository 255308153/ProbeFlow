package com.probeflow.testagent.suitedraft;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryRequest;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteDraftGenerationIssue02Tests {

    @Test
    void sameDependencyLinkGeneratesProducerExtractRuleAndConsumerReferences() {
        var result = generate(SuiteDraftGenerationOptions.defaults());

        var create = SuiteDraftGenerationIssue01Tests.step(result, "create-order");
        assertThat(create.extractRules()).hasSize(1);
        var rule = create.extractRules().get(0);
        assertThat(rule.ruleId()).isEqualTo("rule-create-order-orderId");
        assertThat(rule.producerStepId()).isEqualTo("create-order");
        assertThat(rule.sourceType()).isEqualTo(SuiteDependencySourceType.BODY_JSON);
        assertThat(rule.sourcePath()).isEqualTo("$.data.orderId");
        assertThat(rule.targetScope()).isEqualTo(SuiteVariableScope.SUITE);
        assertThat(rule.targetKey()).isEqualTo("orderId");
        assertThat(rule.required()).isTrue();
        assertThat(rule.failureStrategy()).isEqualTo(SuiteExtractFailureStrategy.FAIL_FAST);
        assertThat(rule.defaultValue()).isNull();
        assertThat(rule.description()).contains("pay-order", "query-order");
        assertThat(rule.confidence()).isGreaterThanOrEqualTo(0.75);
        assertThat(rule.evidenceRefs()).contains("api-order-create", "e-api-api-order-create");
        assertThat(rule.consumerStepIds()).containsExactly("pay-order", "query-order");
        assertThat(rule.sourceDependencyIds())
            .containsExactly("dep-create-order-to-pay-order-orderId", "dep-create-order-to-query-order-orderId");

        var payReference = SuiteDraftGenerationIssue01Tests.step(result, "pay-order").variableReferences().get(0);
        var queryReference = SuiteDraftGenerationIssue01Tests.step(result, "query-order").variableReferences().get(0);
        assertReferencePairsWithRule(payReference, rule);
        assertReferencePairsWithRule(queryReference, rule);
        assertThat(payReference.consumerLocation()).isEqualTo(SuiteConsumerLocation.PATH);
        assertThat(queryReference.consumerLocation()).isEqualTo(SuiteConsumerLocation.PATH);
        assertThat(payReference.referenceExpression()).isEqualTo("${suite.orderId}");
        assertThat(queryReference.referenceExpression()).isEqualTo("${suite.orderId}");

        assertThat(result.draft().steps().stream()
            .flatMap(step -> step.variableReferences().stream())
            .map(SuiteVariableReference::sourceDependencyId)
            .toList())
            .allSatisfy(dependencyId -> assertThat(rule.sourceDependencyIds()).contains(dependencyId));
        assertThat(create.extractRules())
            .allSatisfy(extractRule -> {
                assertThat(extractRule.required()).isTrue();
                assertThat(extractRule.consumerStepIds()).isNotEmpty();
            });
    }

    @Test
    void stepScopedReferencesUseProducerStepIdAndStillPairWithExtractRule() {
        var options = new SuiteDraftGenerationOptions(
            SuiteVariableScope.SUITE,
            0.75,
            0.40,
            true,
            "DETERMINISTIC_GENERATION"
        );

        var result = generate(options);
        var rule = SuiteDraftGenerationIssue01Tests.step(result, "create-order").extractRules().get(0);
        var payReference = SuiteDraftGenerationIssue01Tests.step(result, "pay-order").variableReferences().get(0);

        assertThat(rule.targetScope()).isEqualTo(SuiteVariableScope.STEP);
        assertThat(payReference.targetScope()).isEqualTo(SuiteVariableScope.STEP);
        assertThat(payReference.referenceExpression()).isEqualTo("${step.create-order.orderId}");
        assertReferencePairsWithRule(payReference, rule);
    }

    @Test
    void requiredRulesAndReferencesDoNotDriftToMismatchedTargetKeys() {
        var result = generate(SuiteDraftGenerationOptions.defaults());
        var ruleByDependencyId = result.draft().steps().stream()
            .flatMap(step -> step.extractRules().stream())
            .flatMap(rule -> rule.sourceDependencyIds().stream().map(dependencyId -> Map.entry(dependencyId, rule)))
            .toList();

        for (var step : result.draft().steps()) {
            for (var reference : step.variableReferences()) {
                var rule = ruleByDependencyId.stream()
                    .filter(entry -> entry.getKey().equals(reference.sourceDependencyId()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow();
                assertThat(reference.targetKey()).isEqualTo(rule.targetKey());
                assertThat(reference.referenceExpression()).contains(rule.targetKey());
            }
        }
    }

    private SuiteDraftGenerationResult generate(SuiteDraftGenerationOptions options) {
        var apiSpecs = SuiteDraftGenerationIssue01Tests.orderApiSpecs();
        var discovery = new BusinessFlowDiscoveryService()
            .discover(BusinessFlowDiscoveryRequest.deterministic("order-suite-demo", apiSpecs));
        return new SuiteDraftGenerationService().generate(new SuiteDraftGenerationRequest(
            "order-suite-demo",
            discovery.candidates().get(0),
            apiSpecs,
            List.of(),
            SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(),
            options,
            "local-fake",
            Map.of()
        ));
    }

    private void assertReferencePairsWithRule(SuiteVariableReference reference, SuiteExtractRule rule) {
        assertThat(reference.sourceDependencyId()).isIn(rule.sourceDependencyIds());
        assertThat(reference.targetScope()).isEqualTo(rule.targetScope());
        assertThat(reference.targetKey()).isEqualTo(rule.targetKey());
        assertThat(reference.evidenceRefs()).containsAnyElementsOf(rule.evidenceRefs());
    }
}
