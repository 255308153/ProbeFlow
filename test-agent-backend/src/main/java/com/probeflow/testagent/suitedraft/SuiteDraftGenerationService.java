package com.probeflow.testagent.suitedraft;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowCandidate;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SuiteDraftGenerationService {

    private final DependencyLinker dependencyLinker = new DependencyLinker();
    private final SuiteReadinessValidator readinessValidator = new SuiteReadinessValidator();

    public SuiteDraftGenerationResult generate(SuiteDraftGenerationRequest request) {
        if (request.providerMode() == SuiteDraftProviderMode.MANUAL_REAL_LLM && !request.allowManualProvider()) {
            var diagnostic = SuiteReadinessDiagnostic.error(
                "PROVIDER_BLOCKED",
                List.of(),
                null,
                "Manual real LLM suggestions require explicit approval before suite draft generation.",
                "Use deterministic fake mode or explicitly allow manual provider suggestions.",
                Map.of("providerMode", request.providerMode().name())
            );
            return blocked(request, List.of(diagnostic));
        }
        if (request.candidate() == null) {
            var diagnostic = SuiteReadinessDiagnostic.error(
                "MISSING_FLOW_CANDIDATE",
                List.of(),
                null,
                "Suite draft generation requires a BusinessFlowCandidate.",
                "Run Business Flow Discovery first or pass an explicit candidate.",
                Map.of("fixtureId", request.fixtureId())
            );
            return blocked(request, List.of(diagnostic));
        }
        if (request.apiSpecs().isEmpty()) {
            var diagnostic = SuiteReadinessDiagnostic.error(
                "MISSING_API_SPECS",
                List.of(),
                null,
                "Suite draft generation requires ApiSpec inputs.",
                "Pass the ApiSpecs referenced by the candidate steps.",
                Map.of("flowId", request.candidate().candidateId())
            );
            return blocked(request, List.of(diagnostic));
        }

        var dependencies = dependencyLinker.link(request);
        var dependencyDiagnostics = dependencyDiagnostics(dependencies);
        var artifacts = dependencyArtifacts(dependencies);
        var steps = draftSteps(request, dependencies, artifacts);
        var validation = readinessValidator.validate(request, steps, dependencies, dependencyDiagnostics);
        var validatedSteps = stepsWithReadiness(steps, validation);
        var draft = new SuiteDraft(
            request.candidate().candidateId(),
            request.candidate().scenarioName(),
            validatedSteps,
            draftMetadata(request, dependencies, validation.status(), validation.diagnostics())
        );
        return new SuiteDraftGenerationResult(
            SuiteDraftGenerationResult.SCHEMA_VERSION,
            SuiteDraftGenerationStatus.COMPLETED,
            validation.status(),
            request.fixtureId(),
            request.providerMode(),
            request.providerMode() == SuiteDraftProviderMode.MANUAL_REAL_LLM && request.allowManualProvider(),
            false,
            draft,
            dependencies,
            validation.diagnostics(),
            validation.blockers(),
            resultMetadata(request, dependencies)
        );
    }

    private SuiteDraftGenerationResult blocked(
        SuiteDraftGenerationRequest request,
        List<SuiteReadinessDiagnostic> blockers
    ) {
        return new SuiteDraftGenerationResult(
            SuiteDraftGenerationResult.SCHEMA_VERSION,
            SuiteDraftGenerationStatus.BLOCKED,
            SuiteReadinessStatus.BLOCKED,
            request.fixtureId(),
            request.providerMode(),
            false,
            false,
            null,
            List.of(),
            blockers,
            blockers,
            Map.of("runProfile", request.runProfile())
        );
    }

    private List<SuiteDraftStep> stepsWithReadiness(
        List<SuiteDraftStep> steps,
        SuiteReadinessValidationResult validation
    ) {
        if (validation.status() == SuiteReadinessStatus.READY) {
            return steps;
        }
        var statuses = new LinkedHashMap<String, SuiteReadinessStatus>();
        for (var step : steps) {
            statuses.put(step.stepId(), SuiteReadinessStatus.READY);
        }
        for (var diagnostic : validation.diagnostics()) {
            var affectedStatus = "ERROR".equals(diagnostic.severity())
                ? SuiteReadinessStatus.BLOCKED
                : SuiteReadinessStatus.REVIEW_REQUIRED;
            var affectedSteps = diagnostic.affectedSteps().stream()
                .filter(stepId -> stepId != null && !stepId.isBlank())
                .toList();
            if (affectedSteps.isEmpty()) {
                for (var step : steps) {
                    statuses.put(step.stepId(), worst(statuses.get(step.stepId()), affectedStatus));
                }
                continue;
            }
            for (var stepId : affectedSteps) {
                if (statuses.containsKey(stepId)) {
                    statuses.put(stepId, worst(statuses.get(stepId), affectedStatus));
                }
            }
        }
        return steps.stream()
            .map(step -> new SuiteDraftStep(
                step.stepId(),
                step.stepName(),
                step.order(),
                step.apiSpecId(),
                step.critical(),
                step.requestTemplate(),
                step.expectedStatus(),
                step.assertionHints(),
                step.extractRules(),
                step.variableReferences(),
                step.sourceRefs(),
                step.dependencyRefs(),
                statuses.getOrDefault(step.stepId(), SuiteReadinessStatus.READY),
                step.metadata()
            ))
            .toList();
    }

    private SuiteReadinessStatus worst(SuiteReadinessStatus current, SuiteReadinessStatus candidate) {
        if (current == SuiteReadinessStatus.BLOCKED || candidate == SuiteReadinessStatus.BLOCKED) {
            return SuiteReadinessStatus.BLOCKED;
        }
        if (current == SuiteReadinessStatus.REVIEW_REQUIRED || candidate == SuiteReadinessStatus.REVIEW_REQUIRED) {
            return SuiteReadinessStatus.REVIEW_REQUIRED;
        }
        return SuiteReadinessStatus.READY;
    }

    private List<SuiteDraftStep> draftSteps(
        SuiteDraftGenerationRequest request,
        List<SuiteVariableDependency> dependencies,
        SuiteDependencyArtifacts artifacts
    ) {
        var apiSpecs = request.apiSpecs().stream()
            .collect(Collectors.toMap(ApiSpec::getApiSpecId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        var singleTemplates = request.singleCaseTemplates().stream()
            .collect(Collectors.toMap(
                SuiteSingleCaseTemplate::apiSpecId,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
            ));
        return request.candidate().steps().stream()
            .sorted(Comparator.comparingInt(BusinessFlowDiscoveryStep::order))
            .map(step -> draftStep(
                step,
                apiSpecs.get(step.apiSpecId()),
                singleTemplates.get(step.apiSpecId()),
                dependencies,
                artifacts
            ))
            .toList();
    }

    private SuiteDraftStep draftStep(
        BusinessFlowDiscoveryStep step,
        ApiSpec apiSpec,
        SuiteSingleCaseTemplate singleTemplate,
        List<SuiteVariableDependency> dependencies,
        SuiteDependencyArtifacts artifacts
    ) {
        var dependencyRefs = dependencies.stream()
            .filter(dependency -> dependency.producerStepId().equals(step.stepId())
                || dependency.consumerStepId().equals(step.stepId()))
            .map(SuiteVariableDependency::dependencyId)
            .toList();
        var variableReferences = artifacts.referencesByConsumer().getOrDefault(step.stepId(), List.of());
        return new SuiteDraftStep(
            step.stepId(),
            step.stepName(),
            step.order(),
            step.apiSpecId(),
            step.critical(),
            requestTemplate(apiSpec, step, singleTemplate, variableReferences),
            expectedStatus(step),
            assertionHints(singleTemplate),
            artifacts.extractRulesByProducer().getOrDefault(step.stepId(), List.of()),
            variableReferences,
            step.sourceRefs(),
            dependencyRefs,
            SuiteReadinessStatus.READY,
            orderedMap(
                "snapshotType", "SUITE_STEP_DRAFT",
                "sourceOperationKind", step.operationKind().name(),
                "sourcePath", step.path(),
                "requestTemplateSource", singleTemplate == null ? "API_SPEC_FALLBACK" : "SINGLE_CASE_TEMPLATE",
                "singleTemplateId", singleTemplate == null ? null : singleTemplate.templateId(),
                "snapshotFrozen", true
            )
        );
    }

    private SuiteDependencyArtifacts dependencyArtifacts(List<SuiteVariableDependency> dependencies) {
        var grouped = new LinkedHashMap<String, List<SuiteVariableDependency>>();
        for (var dependency : dependencies) {
            var key = String.join("|",
                dependency.producerStepId(),
                dependency.sourceType().name(),
                nullToBlank(dependency.sourcePath()),
                dependency.targetScope().name(),
                dependency.targetKey()
            );
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(dependency);
        }

        var extractRulesByProducer = new LinkedHashMap<String, List<SuiteExtractRule>>();
        var referencesByConsumer = new LinkedHashMap<String, List<SuiteVariableReference>>();
        for (var dependenciesForRule : grouped.values()) {
            var first = dependenciesForRule.get(0);
            if (first.sourceType() == SuiteDependencySourceType.UNSUPPORTED) {
                continue;
            }
            var rule = new SuiteExtractRule(
                "rule-" + first.producerStepId() + "-" + first.targetKey(),
                first.producerStepId(),
                first.sourceType(),
                first.sourcePath(),
                first.targetScope(),
                first.targetKey(),
                first.required(),
                first.required() ? SuiteExtractFailureStrategy.FAIL_FAST : SuiteExtractFailureStrategy.WRITE_NULL,
                null,
                "Extract " + first.targetKey() + " for "
                    + dependenciesForRule.stream()
                    .map(SuiteVariableDependency::consumerStepId)
                    .filter(consumerStepId -> consumerStepId != null && !consumerStepId.isBlank())
                    .distinct()
                    .toList(),
                dependenciesForRule.stream().mapToDouble(SuiteVariableDependency::confidence).min().orElse(0.0),
                dependenciesForRule.stream().flatMap(dependency -> dependency.evidenceRefs().stream()).distinct().toList(),
                dependenciesForRule.stream()
                    .map(SuiteVariableDependency::consumerStepId)
                    .filter(consumerStepId -> consumerStepId != null && !consumerStepId.isBlank())
                    .distinct()
                    .toList(),
                dependenciesForRule.stream().map(SuiteVariableDependency::dependencyId).toList()
            );
            extractRulesByProducer.computeIfAbsent(first.producerStepId(), ignored -> new ArrayList<>()).add(rule);
            for (var dependency : dependenciesForRule) {
                if (dependency.consumerLocation() == SuiteConsumerLocation.NONE
                    || dependency.consumerStepId() == null
                    || dependency.consumerStepId().isBlank()) {
                    continue;
                }
                var reference = new SuiteVariableReference(
                    dependency.consumerStepId(),
                    dependency.consumerLocation(),
                    dependency.consumerField(),
                    dependency.targetScope(),
                    dependency.targetKey(),
                    dependency.referenceExpression(),
                    dependency.dependencyId(),
                    dependency.evidenceRefs()
                );
                referencesByConsumer.computeIfAbsent(dependency.consumerStepId(), ignored -> new ArrayList<>())
                    .add(reference);
            }
        }
        return new SuiteDependencyArtifacts(
            copyListMap(extractRulesByProducer),
            copyListMap(referencesByConsumer)
        );
    }

    private List<SuiteReadinessDiagnostic> dependencyDiagnostics(List<SuiteVariableDependency> dependencies) {
        return dependencies.stream()
            .filter(dependency -> dependency.sourceType() == SuiteDependencySourceType.UNSUPPORTED)
            .map(dependency -> SuiteReadinessDiagnostic.warn(
                "UNSUPPORTED_DEPENDENCY_SOURCE",
                List.of(dependency.producerStepId(), dependency.consumerStepId()),
                dependency.dependencyId(),
                "Unsupported dependency source type cannot produce a V3-3 extractRule.",
                "Keep the dependency as review evidence or model it with BODY_JSON, HEADER or STATUS_CODE.",
                orderedMap(
                    "sourceType", dependency.sourceType().name(),
                    "sourcePath", dependency.sourcePath(),
                    "targetKey", dependency.targetKey(),
                    "metadata", dependency.metadata()
                )
            ))
            .toList();
    }

    private Map<String, Object> requestTemplate(
        ApiSpec apiSpec,
        BusinessFlowDiscoveryStep step,
        SuiteSingleCaseTemplate singleTemplate,
        List<SuiteVariableReference> variableReferences
    ) {
        var template = singleTemplate == null || singleTemplate.requestTemplate().isEmpty()
            ? fallbackRequestTemplate(apiSpec, step)
            : deepCopyMap(singleTemplate.requestTemplate());
        template.putIfAbsent("method", step.httpMethod().name());
        template.putIfAbsent("path", step.path());
        template.putIfAbsent("templateSource", singleTemplate == null ? "API_SPEC_FALLBACK" : "SINGLE_CASE_TEMPLATE");
        template.putIfAbsent("headers", new LinkedHashMap<String, Object>());
        template.putIfAbsent("query", new LinkedHashMap<String, Object>());
        template.putIfAbsent("body", new LinkedHashMap<String, Object>());
        for (var reference : variableReferences) {
            rewriteTemplate(template, reference);
        }
        if (apiSpec != null) {
            template.putIfAbsent("apiSpecVersion", apiSpec.getVersion());
        }
        return template;
    }

    private Map<String, Object> fallbackRequestTemplate(ApiSpec apiSpec, BusinessFlowDiscoveryStep step) {
        var template = new LinkedHashMap<String, Object>();
        template.put("method", step.httpMethod().name());
        template.put("path", step.path());
        template.put("headers", orderedMap("Authorization", "Bearer {{orderAuthToken}}"));
        template.put("query", new LinkedHashMap<String, Object>());
        template.put("body", new LinkedHashMap<String, Object>());
        template.put("templateSource", "API_SPEC_FALLBACK");
        if (apiSpec != null) {
            template.put("apiSpecVersion", apiSpec.getVersion());
        }
        return template;
    }

    private List<Map<String, Object>> assertionHints(SuiteSingleCaseTemplate singleTemplate) {
        if (singleTemplate == null || singleTemplate.assertionHints().isEmpty()) {
            return List.of(orderedMap("source", "api-spec", "summary", "status code should match expectedStatus"));
        }
        return List.of(singleTemplate.assertionHints());
    }

    @SuppressWarnings("unchecked")
    private void rewriteTemplate(Map<String, Object> template, SuiteVariableReference reference) {
        var field = reference.consumerField() == null || reference.consumerField().isBlank()
            ? reference.targetKey()
            : reference.consumerField();
        switch (reference.consumerLocation()) {
            case PATH -> {
                var path = String.valueOf(template.getOrDefault("path", ""));
                template.put("path", path.replace("{" + field + "}", reference.referenceExpression()));
            }
            case QUERY -> mutableSection(template, "query").put(field, reference.referenceExpression());
            case BODY -> mutableSection(template, "body").put(field, reference.referenceExpression());
            case HEADER -> mutableSection(template, "headers").put(field, reference.referenceExpression());
            case NONE -> {
                // A dependency may be generated only for downstream runtime analysis metadata.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableSection(Map<String, Object> template, String key) {
        var value = template.get(key);
        if (value instanceof Map<?, ?> map) {
            var typed = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            template.put(key, typed);
            return typed;
        }
        var section = new LinkedHashMap<String, Object>();
        template.put(key, section);
        return section;
    }

    private Integer expectedStatus(BusinessFlowDiscoveryStep step) {
        return switch (step.operationKind()) {
            case CREATE -> 201;
            default -> 200;
        };
    }

    private Map<String, Object> draftMetadata(
        SuiteDraftGenerationRequest request,
        List<SuiteVariableDependency> dependencies,
        SuiteReadinessStatus readinessStatus,
        List<SuiteReadinessDiagnostic> diagnostics
    ) {
        var candidate = request.candidate();
        return orderedMap(
            "basedOnApiSpecIds", candidate.steps().stream().map(BusinessFlowDiscoveryStep::apiSpecId).toList(),
            "basedOnApiSpecVersions", apiSpecVersions(request.apiSpecs()),
            "basedOnKnowledgeRefs", sourceRefs(candidate, "KNOWLEDGE"),
            "basedOnMemoryRefs", sourceRefs(candidate, "MEMORY"),
            "basedOnFlowCandidateId", candidate.candidateId(),
            "candidateConfidence", candidate.confidence(),
            "candidateRequiresHumanReview", candidate.requiresHumanReview(),
            "candidateEvidenceRefs", candidate.evidence().stream().map(evidence -> evidence.evidenceId()).toList(),
            "dependencyGraphSummary", dependencyGraphSummary(dependencies),
            "readinessValidation", orderedMap(
                "status", readinessStatus.name(),
                "diagnosticCount", diagnostics.size(),
                "diagnostics", diagnostics
            ),
            "requiresHumanReview", readinessStatus != SuiteReadinessStatus.READY,
            "generationMode", request.options().generationMode(),
            "policyGate", orderedMap(
                "type", "GENERATION_SIDE_SAFETY_GATE",
                "linker", "DependencyLinker",
                "validator", "SuiteReadinessValidator",
                "policyValidatorBoundary", "No PlannerDecision is accepted and no plan mutation is performed in V3-3.",
                "plannerDecisionValidated", false
            )
        );
    }

    private Map<String, Object> resultMetadata(
        SuiteDraftGenerationRequest request,
        List<SuiteVariableDependency> dependencies
    ) {
        return orderedMap(
            "runProfile", request.runProfile(),
            "dependencyCount", dependencies.size(),
            "stepCount", request.candidate().steps().size(),
            "defaultProviderIsFake", request.providerMode() == SuiteDraftProviderMode.DETERMINISTIC_FAKE,
            "usesExternalHttp", false
        );
    }

    private Map<String, Object> dependencyGraphSummary(List<SuiteVariableDependency> dependencies) {
        return orderedMap(
            "dependencyCount", dependencies.size(),
            "edges", dependencies.stream()
                .map(dependency -> orderedMap(
                    "dependencyId", dependency.dependencyId(),
                    "producerStepId", dependency.producerStepId(),
                    "consumerStepId", dependency.consumerStepId(),
                    "targetKey", dependency.targetKey(),
                    "referenceExpression", dependency.referenceExpression()
                ))
                .toList()
        );
    }

    private Map<String, Object> apiSpecVersions(List<ApiSpec> apiSpecs) {
        var versions = new LinkedHashMap<String, Object>();
        for (var apiSpec : apiSpecs) {
            versions.put(apiSpec.getApiSpecId(), apiSpec.getVersion());
        }
        return versions;
    }

    private List<String> sourceRefs(BusinessFlowCandidate candidate, String sourceName) {
        return candidate.evidence().stream()
            .filter(evidence -> evidence.source().name().equals(sourceName))
            .flatMap(evidence -> evidence.refs().stream())
            .distinct()
            .toList();
    }

    private Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private <T> Map<String, List<T>> copyListMap(Map<String, List<T>> source) {
        var copy = new LinkedHashMap<String, List<T>>();
        for (var entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> map) {
                var nested = new LinkedHashMap<String, Object>();
                for (var nestedEntry : map.entrySet()) {
                    nested.put(String.valueOf(nestedEntry.getKey()), nestedEntry.getValue());
                }
                copy.put(entry.getKey(), nested);
            } else if (entry.getValue() instanceof List<?> list) {
                copy.put(entry.getKey(), List.copyOf(list));
            } else {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private record SuiteDependencyArtifacts(
        Map<String, List<SuiteExtractRule>> extractRulesByProducer,
        Map<String, List<SuiteVariableReference>> referencesByConsumer
    ) {
    }
}
