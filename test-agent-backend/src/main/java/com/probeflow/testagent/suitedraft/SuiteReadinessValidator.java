package com.probeflow.testagent.suitedraft;

import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryBlocker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SuiteReadinessValidator {

    private static final Pattern SUITE_REFERENCE = Pattern.compile("^\\$\\{suite\\.[A-Za-z][A-Za-z0-9_.-]*}$");
    private static final Pattern STEP_REFERENCE = Pattern.compile("^\\$\\{step\\.[A-Za-z0-9_-]+\\.[A-Za-z][A-Za-z0-9_.-]*}$");

    public SuiteReadinessValidationResult validate(
        SuiteDraftGenerationRequest request,
        List<SuiteDraftStep> steps,
        List<SuiteVariableDependency> dependencies,
        List<SuiteReadinessDiagnostic> existingDiagnostics
    ) {
        var diagnostics = new ArrayList<SuiteReadinessDiagnostic>();
        diagnostics.addAll(existingDiagnostics == null ? List.of() : existingDiagnostics);

        var stepOrders = validateStepOrder(steps, diagnostics);
        var rules = steps.stream().flatMap(step -> step.extractRules().stream()).toList();
        validateCandidateRisk(request, steps, diagnostics);
        validateDependencies(request, stepOrders, dependencies, diagnostics);
        validateExtractRules(rules, dependencies, diagnostics);
        validateVariableReferences(steps, rules, diagnostics);
        validateDuplicateTargetKeys(rules, diagnostics);

        var blockers = diagnostics.stream()
            .filter(diagnostic -> "ERROR".equals(diagnostic.severity()))
            .toList();
        var status = !blockers.isEmpty()
            ? SuiteReadinessStatus.BLOCKED
            : diagnostics.isEmpty() ? SuiteReadinessStatus.READY : SuiteReadinessStatus.REVIEW_REQUIRED;
        return new SuiteReadinessValidationResult(status, diagnostics, blockers);
    }

    private Map<String, Integer> validateStepOrder(
        List<SuiteDraftStep> steps,
        List<SuiteReadinessDiagnostic> diagnostics
    ) {
        var stepOrders = new LinkedHashMap<String, Integer>();
        var orders = new LinkedHashMap<Integer, String>();
        for (var step : steps) {
            if (blank(step.stepId())) {
                diagnostics.add(error(
                    "MISSING_STEP_ID",
                    List.of(),
                    null,
                    "SUITE draft step is missing a stable stepId.",
                    "Keep step ids stable and non-empty before publishing the draft.",
                    Map.of("order", step.order())
                ));
                continue;
            }
            if (stepOrders.containsKey(step.stepId())) {
                diagnostics.add(error(
                    "DUPLICATE_STEP_ID",
                    List.of(step.stepId()),
                    null,
                    "SUITE draft contains duplicate stepId values.",
                    "Regenerate the candidate with unique step ids before suite draft generation.",
                    Map.of("stepId", step.stepId())
                ));
            }
            if (step.order() <= 0) {
                diagnostics.add(error(
                    "INVALID_STEP_ORDER",
                    List.of(step.stepId()),
                    null,
                    "SUITE draft step order must be positive.",
                    "Use the BusinessFlowCandidate order as a positive stable sequence.",
                    Map.of("order", step.order())
                ));
            }
            var previousStepId = orders.putIfAbsent(step.order(), step.stepId());
            if (previousStepId != null) {
                diagnostics.add(error(
                    "DUPLICATE_STEP_ORDER",
                    List.of(previousStepId, step.stepId()),
                    null,
                    "SUITE draft contains duplicate step order values.",
                    "Resolve the BusinessFlowCandidate order before generating dependencies.",
                    Map.of("order", step.order())
                ));
            }
            stepOrders.put(step.stepId(), step.order());
        }

        var sortedOrders = steps.stream()
            .map(SuiteDraftStep::order)
            .sorted()
            .toList();
        for (int index = 0; index < sortedOrders.size(); index++) {
            var expected = index + 1;
            if (sortedOrders.get(index) != expected) {
                diagnostics.add(warn(
                    "NON_CONTIGUOUS_STEP_ORDER",
                    steps.stream().map(SuiteDraftStep::stepId).toList(),
                    null,
                    "SUITE draft step order is stable but not contiguous.",
                    "Prefer contiguous one-based order values for reviewable suite drafts.",
                    Map.of("orders", sortedOrders)
                ));
                break;
            }
        }
        return stepOrders;
    }

    private void validateCandidateRisk(
        SuiteDraftGenerationRequest request,
        List<SuiteDraftStep> steps,
        List<SuiteReadinessDiagnostic> diagnostics
    ) {
        if (request.candidate().requiresHumanReview()) {
            diagnostics.add(warn(
                "CANDIDATE_REQUIRES_HUMAN_REVIEW",
                steps.stream().map(SuiteDraftStep::stepId).toList(),
                null,
                "BusinessFlowCandidate already requires human review.",
                "Keep the generated SUITE draft review-gated until the candidate is confirmed.",
                Map.of("flowId", request.candidate().candidateId())
            ));
        }
        for (var blocker : request.candidate().blockers()) {
            diagnostics.add(candidateBlockerDiagnostic(blocker, steps));
        }
    }

    private SuiteReadinessDiagnostic candidateBlockerDiagnostic(
        BusinessFlowDiscoveryBlocker blocker,
        List<SuiteDraftStep> steps
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("candidateBlockerCode", blocker.code());
        metadata.put("candidateBlockerSeverity", blocker.severity());
        metadata.put("candidateBlockerMetadata", blocker.metadata());
        var affectedSteps = steps.stream().map(SuiteDraftStep::stepId).toList();
        if ("ERROR".equals(blocker.severity())) {
            return error(
                "CANDIDATE_BLOCKER",
                affectedSteps,
                null,
                "BusinessFlowCandidate blocker prevents READY suite draft: " + blocker.message(),
                "Resolve the upstream business-flow blocker or keep the draft blocked.",
                metadata
            );
        }
        return warn(
            "CANDIDATE_BLOCKER",
            affectedSteps,
            null,
            "BusinessFlowCandidate blocker requires review: " + blocker.message(),
            "Review the upstream business-flow blocker before using the generated draft.",
            metadata
        );
    }

    private void validateDependencies(
        SuiteDraftGenerationRequest request,
        Map<String, Integer> stepOrders,
        List<SuiteVariableDependency> dependencies,
        List<SuiteReadinessDiagnostic> diagnostics
    ) {
        for (var dependency : dependencies) {
            var affectedSteps = affectedSteps(dependency);
            var producerOrder = stepOrders.get(dependency.producerStepId());
            if (producerOrder == null) {
                diagnostics.add(error(
                    "UNKNOWN_PRODUCER_STEP",
                    affectedSteps,
                    dependency.dependencyId(),
                    "Dependency producer step does not exist in the SUITE draft.",
                    "Regenerate the dependency graph from candidate steps.",
                    Map.of("producerStepId", dependency.producerStepId())
                ));
            }
            if (dependency.consumerLocation() != SuiteConsumerLocation.NONE) {
                var consumerOrder = stepOrders.get(dependency.consumerStepId());
                if (consumerOrder == null) {
                    diagnostics.add(error(
                        "UNKNOWN_CONSUMER_STEP",
                        affectedSteps,
                        dependency.dependencyId(),
                        "Dependency consumer step does not exist in the SUITE draft.",
                        "Regenerate the dependency graph from candidate steps.",
                        Map.of("consumerStepId", dependency.consumerStepId())
                    ));
                } else if (producerOrder != null && producerOrder >= consumerOrder) {
                    diagnostics.add(error(
                        "PRODUCER_NOT_BEFORE_CONSUMER",
                        affectedSteps,
                        dependency.dependencyId(),
                        "Dependency producer must appear before its consumer.",
                        "Keep BusinessFlowCandidate order fixed and send the draft to review instead of reordering silently.",
                        orderedMap("producerOrder", producerOrder, "consumerOrder", consumerOrder)
                    ));
                }
            }

            if (dependency.conflict()) {
                diagnostics.add(warn(
                    "CONFLICTING_DEPENDENCY_EVIDENCE",
                    affectedSteps,
                    dependency.dependencyId(),
                    "Dependency evidence contains conflicting producer or consumer claims.",
                    "Require human review before marking the SUITE draft ready.",
                    Map.of("evidenceRefs", dependency.evidenceRefs())
                ));
            }
            if (dependency.confidence() < request.options().blockedConfidenceThreshold()) {
                diagnostics.add(error(
                    "LOW_CONFIDENCE_DEPENDENCY",
                    affectedSteps,
                    dependency.dependencyId(),
                    "Dependency confidence is below the blocked threshold.",
                    "Add stronger ApiSpec, knowledge or memory evidence before using this dependency.",
                    orderedMap(
                        "confidence", dependency.confidence(),
                        "blockedThreshold", request.options().blockedConfidenceThreshold()
                    )
                ));
            } else if (dependency.confidence() < request.options().autoReadyConfidenceThreshold()) {
                diagnostics.add(warn(
                    "LOW_CONFIDENCE_DEPENDENCY",
                    affectedSteps,
                    dependency.dependencyId(),
                    "Dependency confidence is below the automatic READY threshold.",
                    "Send the SUITE draft to human review before execution.",
                    orderedMap(
                        "confidence", dependency.confidence(),
                        "autoReadyThreshold", request.options().autoReadyConfidenceThreshold()
                    )
                ));
            }
        }
    }

    private void validateExtractRules(
        List<SuiteExtractRule> rules,
        List<SuiteVariableDependency> dependencies,
        List<SuiteReadinessDiagnostic> diagnostics
    ) {
        var dependenciesById = dependencies.stream()
            .collect(Collectors.toMap(
                SuiteVariableDependency::dependencyId,
                dependency -> dependency,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        for (var rule : rules) {
            if (blank(rule.ruleId())) {
                diagnostics.add(error(
                    "MISSING_EXTRACT_RULE_ID",
                    List.of(rule.producerStepId()),
                    null,
                    "ExtractRule is missing ruleId.",
                    "Regenerate paired extractRules from dependency links.",
                    Map.of()
                ));
            }
            if (blank(rule.producerStepId())) {
                diagnostics.add(error(
                    "MISSING_EXTRACT_RULE_PRODUCER",
                    List.of(),
                    null,
                    "ExtractRule is missing producerStepId.",
                    "Regenerate paired extractRules from dependency links.",
                    Map.of("ruleId", rule.ruleId())
                ));
            }
            if (rule.sourceType() == SuiteDependencySourceType.UNSUPPORTED) {
                diagnostics.add(error(
                    "UNSUPPORTED_EXTRACT_RULE_SOURCE",
                    List.of(rule.producerStepId()),
                    null,
                    "Unsupported dependency source type cannot produce an extractRule.",
                    "Model the source as BODY_JSON, HEADER or STATUS_CODE, or keep it as review-only evidence.",
                    Map.of("ruleId", rule.ruleId())
                ));
            }
            if (blank(rule.sourcePath())) {
                diagnostics.add(error(
                    "MISSING_EXTRACT_RULE_SOURCE_PATH",
                    List.of(rule.producerStepId()),
                    null,
                    "ExtractRule is missing sourcePath.",
                    "Provide the response body path, header path or status-code source before marking the draft ready.",
                    Map.of("ruleId", rule.ruleId())
                ));
            }
            if (rule.targetScope() == null) {
                diagnostics.add(error(
                    "MISSING_EXTRACT_RULE_TARGET_SCOPE",
                    List.of(rule.producerStepId()),
                    null,
                    "ExtractRule is missing targetScope.",
                    "Set the target scope to suite or step before publishing the draft.",
                    Map.of("ruleId", rule.ruleId())
                ));
            }
            if (blank(rule.targetKey())) {
                diagnostics.add(error(
                    "MISSING_EXTRACT_RULE_TARGET_KEY",
                    List.of(rule.producerStepId()),
                    null,
                    "ExtractRule is missing targetKey.",
                    "Provide a stable variable key before marking the draft ready.",
                    Map.of("ruleId", rule.ruleId())
                ));
            }
            if (rule.failureStrategy() == null) {
                diagnostics.add(error(
                    "MISSING_EXTRACT_RULE_FAILURE_STRATEGY",
                    List.of(rule.producerStepId()),
                    null,
                    "ExtractRule is missing failureStrategy.",
                    "Set FAIL_FAST, WRITE_NULL or WRITE_DEFAULT in the generated rule.",
                    Map.of("ruleId", rule.ruleId())
                ));
            }
            if (rule.required() && rule.consumerStepIds().isEmpty()) {
                var hasReviewPurpose = rule.sourceDependencyIds().stream()
                    .map(dependenciesById::get)
                    .anyMatch(dependency -> dependency != null && !dependency.metadata().isEmpty());
                if (!hasReviewPurpose) {
                    diagnostics.add(error(
                        "REQUIRED_EXTRACT_RULE_WITHOUT_CONSUMER",
                        List.of(rule.producerStepId()),
                        null,
                        "Required extractRule has no consumer or review purpose.",
                        "Make the rule non-required or attach it to a consumer dependency.",
                        Map.of("ruleId", rule.ruleId())
                    ));
                }
            }
        }
    }

    private void validateVariableReferences(
        List<SuiteDraftStep> steps,
        List<SuiteExtractRule> rules,
        List<SuiteReadinessDiagnostic> diagnostics
    ) {
        var rulesByDependencyId = new LinkedHashMap<String, SuiteExtractRule>();
        for (var rule : rules) {
            for (var dependencyId : rule.sourceDependencyIds()) {
                rulesByDependencyId.putIfAbsent(dependencyId, rule);
            }
        }
        for (var step : steps) {
            for (var reference : step.variableReferences()) {
                if (!validReferenceExpression(reference.referenceExpression())) {
                    diagnostics.add(error(
                        "INVALID_VARIABLE_REFERENCE",
                        List.of(step.stepId()),
                        reference.sourceDependencyId(),
                        "Variable reference must use an explicit supported scope.",
                        "Use ${suite.key} or ${step.producerStepId.key}; bare ${key} references are not allowed.",
                        orderedMap(
                            "referenceExpression", reference.referenceExpression(),
                            "targetKey", reference.targetKey()
                        )
                    ));
                }
                var rule = rulesByDependencyId.get(reference.sourceDependencyId());
                if (rule == null) {
                    diagnostics.add(error(
                        "UNPAIRED_VARIABLE_REFERENCE",
                        List.of(step.stepId()),
                        reference.sourceDependencyId(),
                        "Consumer variable reference cannot find its producer extractRule.",
                        "Regenerate extractRules and variable references from the same dependency link.",
                        Map.of("referenceExpression", reference.referenceExpression())
                    ));
                    continue;
                }
                if (rule.targetScope() != reference.targetScope() || !safeEquals(rule.targetKey(), reference.targetKey())) {
                    diagnostics.add(error(
                        "MISMATCHED_VARIABLE_REFERENCE",
                        List.of(step.stepId(), rule.producerStepId()),
                        reference.sourceDependencyId(),
                        "Consumer variable reference does not match its producer extractRule.",
                        "Regenerate the paired dependency artifacts from one SuiteVariableDependency.",
                        orderedMap(
                            "ruleTargetScope", rule.targetScope(),
                            "referenceTargetScope", reference.targetScope(),
                            "ruleTargetKey", rule.targetKey(),
                            "referenceTargetKey", reference.targetKey()
                        )
                    ));
                }
            }
        }
    }

    private void validateDuplicateTargetKeys(
        List<SuiteExtractRule> rules,
        List<SuiteReadinessDiagnostic> diagnostics
    ) {
        var grouped = rules.stream()
            .filter(rule -> rule.targetScope() == SuiteVariableScope.SUITE && !blank(rule.targetKey()))
            .collect(Collectors.groupingBy(
                SuiteExtractRule::targetKey,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        for (var entry : grouped.entrySet()) {
            var distinctSources = entry.getValue().stream()
                .map(rule -> rule.producerStepId() + "|" + rule.sourceType() + "|" + rule.sourcePath())
                .distinct()
                .toList();
            if (distinctSources.size() <= 1) {
                continue;
            }
            diagnostics.add(warn(
                "DUPLICATE_SUITE_TARGET_KEY",
                entry.getValue().stream().map(SuiteExtractRule::producerStepId).distinct().toList(),
                null,
                "Multiple producer extractRules write the same suite-scoped targetKey.",
                "Require human review to avoid accidentally overwriting a suite variable.",
                orderedMap(
                    "targetKey", entry.getKey(),
                    "ruleIds", entry.getValue().stream()
                        .sorted(Comparator.comparing(SuiteExtractRule::ruleId))
                        .map(SuiteExtractRule::ruleId)
                        .toList()
                )
            ));
        }
    }

    private boolean validReferenceExpression(String referenceExpression) {
        if (blank(referenceExpression)) {
            return false;
        }
        return SUITE_REFERENCE.matcher(referenceExpression).matches()
            || STEP_REFERENCE.matcher(referenceExpression).matches();
    }

    private List<String> affectedSteps(SuiteVariableDependency dependency) {
        var steps = new ArrayList<String>();
        if (!blank(dependency.producerStepId())) {
            steps.add(dependency.producerStepId());
        }
        if (!blank(dependency.consumerStepId())) {
            steps.add(dependency.consumerStepId());
        }
        return steps;
    }

    private SuiteReadinessDiagnostic error(
        String code,
        List<String> affectedSteps,
        String affectedDependencyId,
        String reason,
        String recommendedAction,
        Map<String, Object> metadata
    ) {
        return SuiteReadinessDiagnostic.error(code, affectedSteps, affectedDependencyId, reason, recommendedAction, metadata);
    }

    private SuiteReadinessDiagnostic warn(
        String code,
        List<String> affectedSteps,
        String affectedDependencyId,
        String reason,
        String recommendedAction,
        Map<String, Object> metadata
    ) {
        return SuiteReadinessDiagnostic.warn(code, affectedSteps, affectedDependencyId, reason, recommendedAction, metadata);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }
}
