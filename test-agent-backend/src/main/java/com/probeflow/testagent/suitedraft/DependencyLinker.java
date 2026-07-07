package com.probeflow.testagent.suitedraft;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryStep;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowEvidenceSource;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowOperationKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class DependencyLinker {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    public List<SuiteVariableDependency> link(SuiteDraftGenerationRequest request) {
        var dependencies = new ArrayList<SuiteVariableDependency>();
        dependencies.addAll(orderIdDependencies(request));
        dependencies.addAll(hintedDependencies(request));
        return dependencies;
    }

    private List<SuiteVariableDependency> hintedDependencies(SuiteDraftGenerationRequest request) {
        var result = new ArrayList<SuiteVariableDependency>();
        var stepsById = new LinkedHashMap<String, BusinessFlowDiscoveryStep>();
        for (var step : request.candidate().steps()) {
            stepsById.put(step.stepId(), step);
        }
        for (var hint : request.dependencyHints()) {
            var producer = stepsById.get(hint.producerStepId());
            var consumer = stepsById.get(hint.consumerStepId());
            if (producer == null || consumer == null) {
                continue;
            }
            var targetKey = blankToDefault(hint.targetKey(), hint.variableName());
            var targetScope = hint.targetScope() == null ? request.options().defaultTargetScope() : hint.targetScope();
            result.add(dependency(
                producer,
                consumer,
                blankToDefault(hint.variableName(), targetKey),
                hint.sourceType(),
                hint.sourcePath(),
                hint.consumerLocation(),
                blankToDefault(hint.consumerField(), targetKey),
                targetScope,
                targetScope == SuiteVariableScope.STEP || request.options().preferStepScopedReferences(),
                hint.confidence() <= 0 ? 0.85 : hint.confidence(),
                hint.evidenceRefs(),
                hint.conflict(),
                hint.required(),
                highRisk(consumer) ? "HIGH" : "MEDIUM",
                orderedMap(
                    "hintId", hint.hintId(),
                    "inference", "dependency-hint",
                    "hintMetadata", hint.metadata()
                )
            ));
        }
        return result;
    }

    private List<SuiteVariableDependency> orderIdDependencies(SuiteDraftGenerationRequest request) {
        var producer = request.candidate().steps().stream()
            .filter(step -> step.operationKind() == BusinessFlowOperationKind.CREATE)
            .findFirst();
        if (producer.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<SuiteVariableDependency>();
        for (var consumer : request.candidate().steps()) {
            if (consumer.order() <= producer.get().order() || !consumesVariable(consumer, "orderId")) {
                continue;
            }
            result.add(dependency(
                producer.get(),
                consumer,
                "orderId",
                SuiteDependencySourceType.BODY_JSON,
                "$.data.orderId",
                SuiteConsumerLocation.PATH,
                "orderId",
                request.options().defaultTargetScope(),
                request.options().preferStepScopedReferences(),
                0.90,
                evidenceRefs(request, producer.get(), consumer),
                false,
                true,
                highRisk(consumer) ? "HIGH" : "MEDIUM",
                Map.of("inference", "path-variable-name-match")
            ));
        }
        return result;
    }

    private SuiteVariableDependency dependency(
        BusinessFlowDiscoveryStep producer,
        BusinessFlowDiscoveryStep consumer,
        String variableName,
        SuiteDependencySourceType sourceType,
        String sourcePath,
        SuiteConsumerLocation consumerLocation,
        String consumerField,
        SuiteVariableScope targetScope,
        boolean preferStepScopedReferences,
        double confidence,
        List<String> evidenceRefs,
        boolean conflict,
        boolean required,
        String riskLevel,
        Map<String, Object> metadata
    ) {
        var effectiveScope = preferStepScopedReferences ? SuiteVariableScope.STEP : targetScope;
        var targetKey = variableName;
        var dependencyId = "dep-" + producer.stepId() + "-to-" + consumer.stepId() + "-" + targetKey;
        return new SuiteVariableDependency(
            dependencyId,
            producer.stepId(),
            consumer.stepId(),
            variableName,
            sourceType,
            sourcePath,
            consumerLocation,
            consumerField,
            effectiveScope,
            targetKey,
            referenceExpression(effectiveScope, producer.stepId(), targetKey),
            confidence,
            evidenceRefs,
            conflict,
            required,
            riskLevel,
            metadata
        );
    }

    private boolean consumesVariable(BusinessFlowDiscoveryStep step, String variableName) {
        var lowerVariable = normalize(variableName);
        if (PATH_VARIABLE.matcher(step.path()).results()
            .map(match -> normalize(match.group(1)))
            .anyMatch(lowerVariable::equals)) {
            return true;
        }
        var pathVariables = step.metadata().get("pathVariables");
        if (pathVariables instanceof List<?> values) {
            return values.stream()
                .map(String::valueOf)
                .map(this::normalize)
                .anyMatch(lowerVariable::equals);
        }
        return false;
    }

    private boolean highRisk(BusinessFlowDiscoveryStep step) {
        return step.operationKind() == BusinessFlowOperationKind.PAY
            || step.operationKind() == BusinessFlowOperationKind.CANCEL;
    }

    private List<String> evidenceRefs(
        SuiteDraftGenerationRequest request,
        BusinessFlowDiscoveryStep producer,
        BusinessFlowDiscoveryStep consumer
    ) {
        var refs = new ArrayList<String>();
        refs.addAll(producer.sourceRefs());
        refs.addAll(consumer.sourceRefs());
        request.candidate().evidence().stream()
            .filter(evidence -> evidence.source() == BusinessFlowEvidenceSource.API_SPEC_STRUCTURE)
            .map(evidence -> evidence.evidenceId())
            .forEach(refs::add);
        return refs.stream().distinct().toList();
    }

    private String referenceExpression(SuiteVariableScope scope, String producerStepId, String targetKey) {
        if (scope == SuiteVariableScope.STEP) {
            return "${step." + producerStepId + "." + targetKey + "}";
        }
        return "${suite." + targetKey + "}";
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }

    Map<String, ApiSpec> apiSpecsById(List<ApiSpec> apiSpecs) {
        var map = new LinkedHashMap<String, ApiSpec>();
        for (var apiSpec : apiSpecs) {
            map.put(apiSpec.getApiSpecId(), apiSpec);
        }
        return map;
    }
}
