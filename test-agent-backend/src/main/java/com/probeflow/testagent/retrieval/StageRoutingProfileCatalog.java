package com.probeflow.testagent.retrieval;

import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.memory.MemoryFactType;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StageRoutingProfileCatalog {

    public StageRoutingProfile profileFor(String requestedStage) {
        var stage = normalizeStage(requestedStage);
        return switch (stage) {
            case "api_analysis" -> apiAnalysis();
            case "case_generation" -> caseGeneration();
            case "failure_analysis" -> failureAnalysis();
            case "suite_generation", "suite_recovery" -> suiteFlow(stage);
            case "planner", "planner_policy", "policy" -> plannerPolicy(stage);
            case "report", "report_generation" -> report(stage);
            default -> defaultProfile(stage);
        };
    }

    private StageRoutingProfile apiAnalysis() {
        return profile(
            "api_analysis",
            List.of(
                RetrievalRoute.METADATA_EXACT,
                RetrievalRoute.ORIGINAL_SEMANTIC,
                RetrievalRoute.REWRITTEN_SEMANTIC,
                RetrievalRoute.DOCUMENT_TYPE,
                RetrievalRoute.LEXICAL_TAG
            ),
            weights(
                1.25d,
                0.95d,
                0.90d,
                0.85d,
                0.60d,
                0.35d,
                0.20d,
                0.10d,
                0.45d
            ),
            List.of(DocumentType.BUSINESS_FLOW, DocumentType.API_NOTE, DocumentType.DOMAIN_RULE),
            List.of(MemoryFactType.PROJECT_KNOWLEDGE, MemoryFactType.BUSINESS_PRECONDITION_FACT),
            "api analysis enables structure-first retrieval with metadata exact evidence"
        );
    }

    private StageRoutingProfile caseGeneration() {
        return profile(
            "case_generation",
            List.of(
                RetrievalRoute.REWRITTEN_SEMANTIC,
                RetrievalRoute.DOCUMENT_TYPE,
                RetrievalRoute.METADATA_EXACT,
                RetrievalRoute.SEMANTIC_MEMORY,
                RetrievalRoute.LEXICAL_TAG
            ),
            weights(0.75d, 1.00d, 0.85d, 0.75d, 0.95d, 0.80d, 0.45d, 0.10d, 0.50d),
            List.of(DocumentType.TEST_SPEC, DocumentType.DOMAIN_RULE, DocumentType.BUSINESS_FLOW),
            List.of(MemoryFactType.TESTING_PATTERN, MemoryFactType.BUSINESS_PRECONDITION_FACT),
            "case generation favors test specs, domain rules, and reusable testing patterns"
        );
    }

    private StageRoutingProfile failureAnalysis() {
        return profile(
            "failure_analysis",
            List.of(
                RetrievalRoute.EXACT_ENTITY,
                RetrievalRoute.METADATA_EXACT,
                RetrievalRoute.DOCUMENT_TYPE,
                RetrievalRoute.SEMANTIC_MEMORY,
                RetrievalRoute.METADATA_MEMORY,
                RetrievalRoute.GRAPH_MEMORY,
                RetrievalRoute.REWRITTEN_SEMANTIC
            ),
            weights(0.70d, 0.95d, 1.05d, 0.75d, 0.90d, 0.95d, 0.90d, 0.85d, 1.10d),
            List.of(DocumentType.ERROR_CODE_GUIDE, DocumentType.INCIDENT_POSTMORTEM, DocumentType.API_NOTE),
            List.of(MemoryFactType.FAILURE_PATTERN, MemoryFactType.PROJECT_KNOWLEDGE),
            "failure analysis enables error-code, incident, historical failure, and graph evidence routes"
        );
    }

    private StageRoutingProfile suiteFlow(String stage) {
        return profile(
            stage,
            List.of(
                RetrievalRoute.METADATA_EXACT,
                RetrievalRoute.EXACT_ENTITY,
                RetrievalRoute.DOCUMENT_TYPE,
                RetrievalRoute.METADATA_MEMORY,
                RetrievalRoute.GRAPH_MEMORY,
                RetrievalRoute.REWRITTEN_SEMANTIC
            ),
            weights(0.70d, 0.90d, 1.00d, 0.55d, 0.85d, 0.95d, 1.00d, 0.80d, 1.05d),
            List.of(DocumentType.BUSINESS_FLOW, DocumentType.DOMAIN_RULE, DocumentType.TEST_SPEC),
            List.of(MemoryFactType.SUITE_DEPENDENCY_FACT, MemoryFactType.VARIABLE_EXTRACTION_FACT),
            "suite profile favors business flow, dependency facts, variable extraction, and graph variable evidence"
        );
    }

    private StageRoutingProfile plannerPolicy(String stage) {
        return profile(
            stage,
            List.of(
                RetrievalRoute.EXACT_ENTITY,
                RetrievalRoute.METADATA_MEMORY,
                RetrievalRoute.SEMANTIC_MEMORY,
                RetrievalRoute.REWRITTEN_SEMANTIC
            ),
            weights(0.45d, 0.65d, 1.15d, 0.35d, 0.20d, 1.10d, 0.65d, 0.35d, 1.20d),
            List.of(DocumentType.DOMAIN_RULE),
            List.of(MemoryFactType.POLICY_LEARNING, MemoryFactType.PREFERENCE),
            "planner policy profile favors toolName, policyReason, and high-confidence memory evidence"
        );
    }

    private StageRoutingProfile report(String stage) {
        return profile(
            stage,
            List.of(
                RetrievalRoute.METADATA_EXACT,
                RetrievalRoute.SEMANTIC_MEMORY,
                RetrievalRoute.METADATA_MEMORY,
                RetrievalRoute.DOCUMENT_TYPE,
                RetrievalRoute.REWRITTEN_SEMANTIC
            ),
            weights(0.60d, 0.80d, 0.85d, 0.50d, 0.70d, 0.80d, 0.50d, 0.35d, 0.45d),
            List.of(DocumentType.INCIDENT_POSTMORTEM, DocumentType.ERROR_CODE_GUIDE, DocumentType.API_NOTE),
            List.of(MemoryFactType.FAILURE_PATTERN, MemoryFactType.PROJECT_KNOWLEDGE),
            "report profile favors confirmed observations, failure analysis, high-confidence memory, and citations"
        );
    }

    private StageRoutingProfile defaultProfile(String stage) {
        return profile(
            stage,
            List.of(
                RetrievalRoute.ORIGINAL_SEMANTIC,
                RetrievalRoute.REWRITTEN_SEMANTIC,
                RetrievalRoute.METADATA_EXACT,
                RetrievalRoute.SEMANTIC_MEMORY
            ),
            weights(0.75d, 0.75d, 0.65d, 0.45d, 0.45d, 0.50d, 0.35d, 0.20d, 0.45d),
            List.of(DocumentType.DOMAIN_RULE, DocumentType.API_NOTE),
            List.of(MemoryFactType.PROJECT_KNOWLEDGE),
            "default profile safely falls back to original semantic and broad domain evidence"
        );
    }

    private StageRoutingProfile profile(
        String stage,
        List<RetrievalRoute> enabledRoutes,
        Map<RetrievalRoute, Double> weights,
        List<DocumentType> documentTypes,
        List<MemoryFactType> factTypes,
        String diagnostic
    ) {
        return new StageRoutingProfile(
            stage,
            enabledRoutes,
            weights,
            documentTypes,
            factTypes,
            limits(enabledRoutes),
            "fallback-to-original-semantic-with-route-diagnostic",
            diagnostic,
            RoutingBudgetPolicy.conservativeDefault()
        );
    }

    private Map<RetrievalRoute, Double> weights(
        double originalSemantic,
        double rewrittenSemantic,
        double metadataExact,
        double lexicalTag,
        double documentType,
        double semanticMemory,
        double metadataMemory,
        double graphMemory,
        double exactEntity
    ) {
        var weights = new EnumMap<RetrievalRoute, Double>(RetrievalRoute.class);
        weights.put(RetrievalRoute.ORIGINAL_SEMANTIC, originalSemantic);
        weights.put(RetrievalRoute.REWRITTEN_SEMANTIC, rewrittenSemantic);
        weights.put(RetrievalRoute.METADATA_EXACT, metadataExact);
        weights.put(RetrievalRoute.LEXICAL_TAG, lexicalTag);
        weights.put(RetrievalRoute.DOCUMENT_TYPE, documentType);
        weights.put(RetrievalRoute.SEMANTIC_MEMORY, semanticMemory);
        weights.put(RetrievalRoute.METADATA_MEMORY, metadataMemory);
        weights.put(RetrievalRoute.GRAPH_MEMORY, graphMemory);
        weights.put(RetrievalRoute.EXACT_ENTITY, exactEntity);
        return weights;
    }

    private Map<RetrievalRoute, Integer> limits(List<RetrievalRoute> enabledRoutes) {
        var limits = new EnumMap<RetrievalRoute, Integer>(RetrievalRoute.class);
        for (var route : enabledRoutes) {
            limits.put(route, switch (route) {
                case ORIGINAL_SEMANTIC, REWRITTEN_SEMANTIC, SEMANTIC_MEMORY -> 6;
                case METADATA_EXACT, METADATA_MEMORY, EXACT_ENTITY -> 5;
                case DOCUMENT_TYPE, LEXICAL_TAG, GRAPH_MEMORY -> 4;
            });
        }
        return limits;
    }

    private String normalizeStage(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
