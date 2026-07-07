package com.probeflow.testagent.businessflowdiscovery;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BusinessFlowDiscoveryService {

    private static final double HUMAN_REVIEW_THRESHOLD = 0.70;

    public BusinessFlowDiscoveryResult discover(BusinessFlowDiscoveryRequest request) {
        if (request.providerMode() == BusinessFlowDiscoveryProviderMode.MANUAL_REAL_LLM
            && !request.allowManualLlmProvider()) {
            var blocker = blocker(
                "LLM_PROVIDER_BLOCKED",
                "ERROR",
                "Manual real LLM suggestions require explicit approval.",
                orderedMap("providerMode", request.providerMode().name())
            );
            return new BusinessFlowDiscoveryResult(
                BusinessFlowDiscoveryResult.SCHEMA_VERSION,
                BusinessFlowDiscoveryStatus.BLOCKED,
                request.fixtureId(),
                request.providerMode(),
                false,
                false,
                List.of(),
                List.of(blocker),
                sourceCoverage(List.of(), List.of()),
                Map.of("runProfile", request.runProfile())
            );
        }
        if (request.apiSpecs().isEmpty()) {
            var blocker = blocker(
                "MISSING_API_SPECS",
                "ERROR",
                "Business Flow Discovery requires at least one ApiSpec input.",
                Map.of("fixtureId", request.fixtureId())
            );
            return new BusinessFlowDiscoveryResult(
                BusinessFlowDiscoveryResult.SCHEMA_VERSION,
                BusinessFlowDiscoveryStatus.BLOCKED,
                request.fixtureId(),
                request.providerMode(),
                false,
                false,
                List.of(),
                List.of(blocker),
                sourceCoverage(List.of(), List.of()),
                Map.of("runProfile", request.runProfile())
            );
        }

        var steps = orderedSteps(request);
        var evidence = new ArrayList<BusinessFlowDiscoveryEvidence>();
        evidence.addAll(structuralEvidence(steps));
        evidence.addAll(knowledgeEvidence(request.knowledgeEntries()));
        evidence.addAll(memoryEvidence(request.memoryHits()));
        evidence.addAll(userSelectionEvidence(request.selectedApiSpecIds()));
        evidence.addAll(request.llmSuggestionEvidence());

        var blockers = discoveryBlockers(steps, evidence);
        var confidence = confidence(evidence, blockers);
        var requiresHumanReview = confidence < HUMAN_REVIEW_THRESHOLD || !blockers.isEmpty();
        var candidate = new BusinessFlowCandidate(
            "flow-" + stableSlug(steps),
            scenarioName(steps),
            true,
            confidence,
            requiresHumanReview,
            steps,
            evidence,
            blockers,
            sourceCoverage(evidence, request.llmSuggestionEvidence()),
            List.of("v3-2", "business-flow-discovery", "suite-candidate"),
            orderedMap(
                "ruleExtractionOutputSuppressed", true,
                "staysDiscoveryOnly", true,
                "doesNotGenerateSuiteVariables", true,
                "source", "deterministic-discovery"
            )
        );
        return new BusinessFlowDiscoveryResult(
            BusinessFlowDiscoveryResult.SCHEMA_VERSION,
            blockers.isEmpty() ? BusinessFlowDiscoveryStatus.COMPLETED : BusinessFlowDiscoveryStatus.BLOCKED,
            request.fixtureId(),
            request.providerMode(),
            request.providerMode() == BusinessFlowDiscoveryProviderMode.MANUAL_REAL_LLM && request.allowManualLlmProvider(),
            false,
            List.of(candidate),
            blockers,
            candidate.sourceCoverage(),
            orderedMap(
                "runProfile", request.runProfile(),
                "candidateCount", 1,
                "requiresHumanReviewCount", requiresHumanReview ? 1 : 0,
                "basedOnKnowledgeRefs", evidence.stream()
                    .filter(item -> item.source() == BusinessFlowEvidenceSource.KNOWLEDGE)
                    .flatMap(item -> item.refs().stream())
                    .distinct()
                    .toList(),
                "basedOnMemoryRefs", evidence.stream()
                    .filter(item -> item.source() == BusinessFlowEvidenceSource.MEMORY)
                    .flatMap(item -> item.refs().stream())
                    .distinct()
                    .toList()
            )
        );
    }

    private List<BusinessFlowDiscoveryStep> orderedSteps(BusinessFlowDiscoveryRequest request) {
        var selectedOrder = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < request.selectedApiSpecIds().size(); i++) {
            selectedOrder.put(request.selectedApiSpecIds().get(i), i);
        }
        var sorted = request.apiSpecs().stream()
            .sorted(Comparator
                .comparingInt((ApiSpec apiSpec) -> selectedOrder.getOrDefault(apiSpec.getApiSpecId(), 100 + phaseOrder(apiSpec)))
                .thenComparing(ApiSpec::getPath)
                .thenComparing(ApiSpec::getApiSpecId))
            .toList();
        var steps = new ArrayList<BusinessFlowDiscoveryStep>();
        for (int i = 0; i < sorted.size(); i++) {
            var apiSpec = sorted.get(i);
            var operationKind = operationKind(apiSpec);
            steps.add(new BusinessFlowDiscoveryStep(
                stepId(operationKind, apiSpec),
                i + 1,
                apiSpec.getApiSpecId(),
                stepName(operationKind, apiSpec),
                operationKind,
                apiSpec.getHttpMethod(),
                apiSpec.getPath(),
                operationKind != BusinessFlowOperationKind.QUERY,
                List.of(apiSpec.getApiSpecId()),
                orderedMap(
                    "moduleName", apiSpec.getModuleName(),
                    "operationId", apiSpec.getOperationId(),
                    "sourceType", apiSpec.getSourceType() == null ? "unknown" : apiSpec.getSourceType().name(),
                    "pathVariables", listValue(apiSpec.getParameters(), "pathVariables"),
                    "requestFields", listValue(apiSpec.getParameters(), "requestFields"),
                    "responseFields", listValue(apiSpec.getConstraints(), "responseFields"),
                    "authReady", apiSpec.isAuthReady(),
                    "authKeys", apiSpec.getAuth() == null ? List.of() : apiSpec.getAuth().keySet().stream().sorted().toList()
                )
            ));
        }
        return steps;
    }

    private List<BusinessFlowDiscoveryEvidence> structuralEvidence(List<BusinessFlowDiscoveryStep> steps) {
        var evidence = new ArrayList<BusinessFlowDiscoveryEvidence>();
        for (var step : steps) {
            evidence.add(new BusinessFlowDiscoveryEvidence(
                "e-api-" + step.apiSpecId(),
                BusinessFlowEvidenceSource.API_SPEC_STRUCTURE,
                "ApiSpec structure suggests " + step.operationKind().name().toLowerCase(Locale.ROOT)
                    + " step from " + step.httpMethod() + " " + step.path(),
                structuralContribution(step.operationKind()),
                List.of(step.apiSpecId()),
                orderedMap(
                    "stepId", step.stepId(),
                    "operationKind", step.operationKind().name(),
                    "httpMethod", step.httpMethod().name(),
                    "path", step.path(),
                    "pathVariables", step.metadata().get("pathVariables"),
                    "requestFields", step.metadata().get("requestFields"),
                    "responseFields", step.metadata().get("responseFields"),
                    "authReady", step.metadata().get("authReady"),
                    "authKeys", step.metadata().get("authKeys")
                )
            ));
        }
        return evidence;
    }

    private List<BusinessFlowDiscoveryEvidence> knowledgeEvidence(List<KnowledgeContextEntry> entries) {
        return entries.stream()
            .map(entry -> new BusinessFlowDiscoveryEvidence(
                "e-knowledge-" + entry.chunkId(),
                BusinessFlowEvidenceSource.KNOWLEDGE,
                entry.title() == null || entry.title().isBlank()
                    ? "Knowledge evidence supports candidate business flow."
                    : entry.title(),
                Math.min(0.18, Math.max(0.06, entry.score() * 0.12)),
                List.of(entry.chunkId(), entry.documentId()),
                orderedMap(
                    "documentId", entry.documentId(),
                    "documentRevisionId", entry.documentRevisionId(),
                    "evidenceType", entry.evidenceType(),
                    "sourceRef", entry.sourceRef(),
                    "metadata", entry.metadata(),
                    "conflict", Boolean.TRUE.equals(entry.metadata() == null ? null : entry.metadata().get("conflict")),
                    "matchReasons", entry.matchReasons(),
                    "lowConfidence", entry.lowConfidence()
                )
            ))
            .toList();
    }

    private List<BusinessFlowDiscoveryEvidence> memoryEvidence(List<LongTermMemoryRetrievalHit> hits) {
        return hits.stream()
            .map(hit -> new BusinessFlowDiscoveryEvidence(
                "e-memory-" + hit.memoryId(),
                BusinessFlowEvidenceSource.MEMORY,
                hit.summary(),
                Math.min(0.16, Math.max(0.04, hit.score() * 0.08)),
                List.of(hit.memoryId()),
                orderedMap(
                    "sourceType", hit.sourceType() == null ? "unknown" : hit.sourceType().name(),
                    "sourceRef", hit.sourceRef(),
                    "tags", hit.tags(),
                    "confidence", hit.confidence(),
                    "matchReasons", hit.matchReasons(),
                    "lowConfidence", hit.lowConfidence()
                )
            ))
            .toList();
    }

    private List<BusinessFlowDiscoveryEvidence> userSelectionEvidence(List<String> selectedApiSpecIds) {
        if (selectedApiSpecIds.isEmpty()) {
            return List.of();
        }
        return List.of(new BusinessFlowDiscoveryEvidence(
            "e-user-selection",
            BusinessFlowEvidenceSource.USER_SELECTION,
            "User-selected ApiSpec order was preserved as business flow evidence.",
            0.14,
            selectedApiSpecIds,
            orderedMap("selectedApiSpecIds", selectedApiSpecIds)
        ));
    }

    private List<BusinessFlowDiscoveryBlocker> discoveryBlockers(
        List<BusinessFlowDiscoveryStep> steps,
        List<BusinessFlowDiscoveryEvidence> evidence
    ) {
        var blockers = new ArrayList<BusinessFlowDiscoveryBlocker>();
        if (steps.stream().noneMatch(step -> step.operationKind() == BusinessFlowOperationKind.CREATE)) {
            blockers.add(blocker("MISSING_CREATE_STEP", "ERROR", "Candidate flow is missing a create step.", Map.of()));
        }
        if (steps.stream().noneMatch(step -> step.operationKind() == BusinessFlowOperationKind.PAY)) {
            blockers.add(blocker("MISSING_PAY_STEP", "ERROR", "Candidate flow is missing a pay step.", Map.of()));
        }
        if (steps.stream().noneMatch(step -> step.operationKind() == BusinessFlowOperationKind.QUERY)) {
            blockers.add(blocker("MISSING_QUERY_STEP", "WARN", "Candidate flow has no query or verification step.", Map.of()));
        }
        if (steps.stream().noneMatch(step -> Boolean.TRUE.equals(step.metadata().get("authReady")))) {
            blockers.add(blocker(
                "MISSING_AUTH_PRECONDITION",
                "ERROR",
                "Candidate flow is missing authenticated ApiSpec readiness evidence.",
                orderedMap("requiredFor", "safe suite candidate execution review")
            ));
        }
        var hasConflict = evidence.stream()
            .anyMatch(item -> Boolean.TRUE.equals(item.metadata().get("conflict")));
        if (hasConflict) {
            blockers.add(blocker(
                "CONFLICTING_EVIDENCE",
                "ERROR",
                "Business flow evidence contains conflicting order or prerequisite claims.",
                Map.of()
            ));
        }
        return blockers;
    }

    private double confidence(
        List<BusinessFlowDiscoveryEvidence> evidence,
        List<BusinessFlowDiscoveryBlocker> blockers
    ) {
        var score = 0.34;
        for (var item : evidence) {
            score += item.confidenceContribution();
        }
        score -= blockers.stream().filter(blocker -> "ERROR".equals(blocker.severity())).count() * 0.30;
        score -= blockers.stream().filter(blocker -> "WARN".equals(blocker.severity())).count() * 0.10;
        return Math.max(0.0, Math.min(0.98, Math.round(score * 100.0) / 100.0));
    }

    private BusinessFlowSourceCoverage sourceCoverage(
        List<BusinessFlowDiscoveryEvidence> evidence,
        List<BusinessFlowDiscoveryEvidence> llmSuggestionEvidence
    ) {
        return new BusinessFlowSourceCoverage(
            count(evidence, BusinessFlowEvidenceSource.API_SPEC_STRUCTURE),
            count(evidence, BusinessFlowEvidenceSource.KNOWLEDGE),
            count(evidence, BusinessFlowEvidenceSource.MEMORY),
            count(evidence, BusinessFlowEvidenceSource.USER_SELECTION),
            count(evidence, BusinessFlowEvidenceSource.LLM_SUGGESTION),
            orderedMap(
                "hasApiSpecEvidence", count(evidence, BusinessFlowEvidenceSource.API_SPEC_STRUCTURE) > 0,
                "hasKnowledgeEvidence", count(evidence, BusinessFlowEvidenceSource.KNOWLEDGE) > 0,
                "hasMemoryEvidence", count(evidence, BusinessFlowEvidenceSource.MEMORY) > 0,
                "hasUserSelectionEvidence", count(evidence, BusinessFlowEvidenceSource.USER_SELECTION) > 0,
                "hasLlmSuggestionEvidence", !llmSuggestionEvidence.isEmpty()
            )
        );
    }

    private int count(List<BusinessFlowDiscoveryEvidence> evidence, BusinessFlowEvidenceSource source) {
        return (int) evidence.stream().filter(item -> item.source() == source).count();
    }

    private BusinessFlowDiscoveryBlocker blocker(
        String code,
        String severity,
        String message,
        Map<String, Object> metadata
    ) {
        return new BusinessFlowDiscoveryBlocker(code, severity, message, metadata);
    }

    private int phaseOrder(ApiSpec apiSpec) {
        return switch (operationKind(apiSpec)) {
            case AUTHENTICATE -> 0;
            case CREATE -> 10;
            case PAY -> 20;
            case QUERY -> 30;
            case UPDATE -> 40;
            case CANCEL -> 50;
            case OTHER -> 90;
        };
    }

    private double structuralContribution(BusinessFlowOperationKind kind) {
        return switch (kind) {
            case CREATE, PAY, QUERY -> 0.12;
            case AUTHENTICATE -> 0.08;
            case UPDATE, CANCEL -> 0.06;
            case OTHER -> 0.02;
        };
    }

    private BusinessFlowOperationKind operationKind(ApiSpec apiSpec) {
        var text = ((apiSpec.getOperationId() == null ? "" : apiSpec.getOperationId()) + " "
            + (apiSpec.getSummary() == null ? "" : apiSpec.getSummary()) + " "
            + (apiSpec.getPath() == null ? "" : apiSpec.getPath())).toLowerCase(Locale.ROOT);
        if (text.contains("login") || text.contains("auth") || text.contains("token")) {
            return BusinessFlowOperationKind.AUTHENTICATE;
        }
        if (text.contains("pay") || text.contains("payment")) {
            return BusinessFlowOperationKind.PAY;
        }
        if (apiSpec.getHttpMethod() == HttpMethod.GET || text.contains("query") || text.contains("detail")) {
            return BusinessFlowOperationKind.QUERY;
        }
        if (apiSpec.getHttpMethod() == HttpMethod.POST || text.contains("create")) {
            return BusinessFlowOperationKind.CREATE;
        }
        if (apiSpec.getHttpMethod() == HttpMethod.DELETE || text.contains("cancel")) {
            return BusinessFlowOperationKind.CANCEL;
        }
        if (apiSpec.getHttpMethod() == HttpMethod.PUT || apiSpec.getHttpMethod() == HttpMethod.PATCH) {
            return BusinessFlowOperationKind.UPDATE;
        }
        return BusinessFlowOperationKind.OTHER;
    }

    private String stepId(BusinessFlowOperationKind kind, ApiSpec apiSpec) {
        var entity = entitySlug(apiSpec);
        return switch (kind) {
            case AUTHENTICATE -> "authenticate-" + entity;
            case CREATE -> "create-" + entity;
            case PAY -> "pay-" + entity;
            case QUERY -> "query-" + entity;
            case CANCEL -> "cancel-" + entity;
            case UPDATE -> "update-" + entity;
            case OTHER -> "call-" + entity;
        };
    }

    private String stepName(BusinessFlowOperationKind kind, ApiSpec apiSpec) {
        if (apiSpec.getSummary() != null && !apiSpec.getSummary().isBlank()) {
            return apiSpec.getSummary();
        }
        return switch (kind) {
            case AUTHENTICATE -> "Authenticate";
            case CREATE -> "Create " + entitySlug(apiSpec);
            case PAY -> "Pay " + entitySlug(apiSpec);
            case QUERY -> "Query " + entitySlug(apiSpec);
            case CANCEL -> "Cancel " + entitySlug(apiSpec);
            case UPDATE -> "Update " + entitySlug(apiSpec);
            case OTHER -> "Call " + entitySlug(apiSpec);
        };
    }

    private String scenarioName(List<BusinessFlowDiscoveryStep> steps) {
        if (steps.stream().anyMatch(step -> step.operationKind() == BusinessFlowOperationKind.CREATE)
            && steps.stream().anyMatch(step -> step.operationKind() == BusinessFlowOperationKind.PAY)
            && steps.stream().anyMatch(step -> step.operationKind() == BusinessFlowOperationKind.QUERY)) {
            return "Create order -> Pay order -> Query order";
        }
        return steps.stream()
            .map(BusinessFlowDiscoveryStep::stepName)
            .reduce((left, right) -> left + " -> " + right)
            .orElse("Discovered business flow");
    }

    private String stableSlug(List<BusinessFlowDiscoveryStep> steps) {
        return steps.stream()
            .map(BusinessFlowDiscoveryStep::stepId)
            .reduce((left, right) -> left + "-" + right)
            .orElse("empty");
    }

    private String entitySlug(ApiSpec apiSpec) {
        var path = apiSpec.getPath() == null ? "api" : apiSpec.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("order")) {
            return "order";
        }
        if (path.contains("payment")) {
            return "payment";
        }
        var operationId = apiSpec.getOperationId() == null ? apiSpec.getApiSpecId() : apiSpec.getOperationId();
        return operationId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }

    private List<?> listValue(Map<String, Object> source, String key) {
        if (source == null) {
            return List.of();
        }
        var value = source.get(key);
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        if (value == null) {
            return List.of();
        }
        return List.of(value);
    }
}
