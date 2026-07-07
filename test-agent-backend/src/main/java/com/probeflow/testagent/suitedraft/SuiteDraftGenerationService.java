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
        var steps = draftSteps(request, dependencies);
        var readinessStatus = request.candidate().requiresHumanReview()
            ? SuiteReadinessStatus.REVIEW_REQUIRED
            : SuiteReadinessStatus.READY;
        var draft = new SuiteDraft(
            request.candidate().candidateId(),
            request.candidate().scenarioName(),
            steps,
            draftMetadata(request, dependencies, readinessStatus, List.of())
        );
        return new SuiteDraftGenerationResult(
            SuiteDraftGenerationResult.SCHEMA_VERSION,
            SuiteDraftGenerationStatus.COMPLETED,
            readinessStatus,
            request.fixtureId(),
            request.providerMode(),
            request.providerMode() == SuiteDraftProviderMode.MANUAL_REAL_LLM && request.allowManualProvider(),
            false,
            draft,
            dependencies,
            List.of(),
            List.of(),
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

    private List<SuiteDraftStep> draftSteps(
        SuiteDraftGenerationRequest request,
        List<SuiteVariableDependency> dependencies
    ) {
        var apiSpecs = request.apiSpecs().stream()
            .collect(Collectors.toMap(ApiSpec::getApiSpecId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        return request.candidate().steps().stream()
            .sorted(Comparator.comparingInt(BusinessFlowDiscoveryStep::order))
            .map(step -> draftStep(step, apiSpecs.get(step.apiSpecId()), dependencies))
            .toList();
    }

    private SuiteDraftStep draftStep(
        BusinessFlowDiscoveryStep step,
        ApiSpec apiSpec,
        List<SuiteVariableDependency> dependencies
    ) {
        var dependencyRefs = dependencies.stream()
            .filter(dependency -> dependency.producerStepId().equals(step.stepId())
                || dependency.consumerStepId().equals(step.stepId()))
            .map(SuiteVariableDependency::dependencyId)
            .toList();
        return new SuiteDraftStep(
            step.stepId(),
            step.stepName(),
            step.order(),
            step.apiSpecId(),
            step.critical(),
            fallbackRequestTemplate(apiSpec, step),
            expectedStatus(step),
            List.of(orderedMap("source", "api-spec", "summary", "status code should match expectedStatus")),
            List.of(),
            List.of(),
            step.sourceRefs(),
            dependencyRefs,
            SuiteReadinessStatus.READY,
            orderedMap(
                "snapshotType", "SUITE_STEP_DRAFT",
                "sourceOperationKind", step.operationKind().name(),
                "sourcePath", step.path()
            )
        );
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
            "policyGate", "generation-side Java DependencyLinker result; no PlannerDecision or plan mutation is performed"
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
}
