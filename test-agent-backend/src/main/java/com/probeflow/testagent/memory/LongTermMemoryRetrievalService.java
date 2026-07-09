package com.probeflow.testagent.memory;

import com.probeflow.testagent.knowledge.EmbeddingService;
import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
import com.probeflow.testagent.knowledge.EmbeddingValidation;
import com.probeflow.testagent.memorygraph.MemoryGraphEntityType;
import com.probeflow.testagent.memorygraph.MemoryGraphQueryService;
import com.probeflow.testagent.memorygraph.MemoryGraphRelatedMemory;
import com.probeflow.testagent.memorygraph.MemoryGraphSeed;
import com.probeflow.testagent.retrieval.QueryFilters;
import com.probeflow.testagent.retrieval.QueryIntent;
import com.probeflow.testagent.retrieval.QueryTargetCorpus;
import com.probeflow.testagent.retrieval.QueryVariant;
import com.probeflow.testagent.retrieval.RetrievalRouteDiagnostic;
import com.probeflow.testagent.retrieval.RetrievalRouteEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LongTermMemoryRetrievalService {

    private static final int DEFAULT_LIMIT = 6;
    private static final int DEFAULT_TOKEN_BUDGET = 800;
    private static final int DEFAULT_ROUTE_LIMIT = 6;
    private static final double FUSION_RANK_CONSTANT = 60.0d;
    private static final double SEMANTIC_CONFIDENCE_GATE = 0.55d;
    private static final double METADATA_CONFIDENCE_GATE = 0.55d;
    private static final double EXACT_CONFIDENCE_GATE = 0.65d;
    private static final double GRAPH_CONFIDENCE_GATE = MemoryGraphQueryService.DEFAULT_CONFIDENCE_GATE;
    private static final String SEMANTIC_ROUTE = "semantic_memory";
    private static final String METADATA_ROUTE = "metadata_memory";
    private static final String GRAPH_ROUTE = "graph_memory";
    private static final String EXACT_ROUTE = "exact_entity";

    private final LongTermMemoryRepository longTermMemories;
    private final EmbeddingService embeddingService;
    private final MemoryGraphQueryService graphQueryService;

    @Autowired
    public LongTermMemoryRetrievalService(
        LongTermMemoryRepository longTermMemories,
        EmbeddingService embeddingService,
        MemoryGraphQueryService graphQueryService
    ) {
        this.longTermMemories = longTermMemories;
        this.embeddingService = embeddingService;
        this.graphQueryService = graphQueryService;
    }

    public LongTermMemoryRetrievalService(
        LongTermMemoryRepository longTermMemories,
        EmbeddingService embeddingService
    ) {
        this.longTermMemories = longTermMemories;
        this.embeddingService = embeddingService;
        this.graphQueryService = null;
    }

    @Transactional
    public LongTermMemoryRetrievalResult retrieve(LongTermMemoryQuery query) {
        validate(query);
        var normalized = normalize(query);
        var queryEmbedding = embeddingService.embedQuery(normalized.rawQuery());
        validateEmbedding(queryEmbedding);
        var candidateLimit = Math.max(normalized.limit() * 4, 24);

        var candidates = longTermMemories.findPgvectorCandidates(
            normalized.scopeTypes(),
            normalized.systemName(),
            normalized.moduleName(),
            normalized.apiPath(),
            normalized.errorCode(),
            normalized.tags(),
            normalized.stageProfile(),
            queryEmbedding,
            candidateLimit
        );
        if (candidates.isEmpty()) {
            return new LongTermMemoryRetrievalResult(List.of(), 0, 0);
        }

        var newest = candidates.stream().mapToLong(candidate -> candidate.memory().getUpdatedAt().toEpochMilli()).max().orElse(0L);
        var oldest = candidates.stream().mapToLong(candidate -> candidate.memory().getUpdatedAt().toEpochMilli()).min().orElse(newest);

        var ranked = candidates.stream()
            .map(candidate -> score(candidate, normalized, newest, oldest))
            .sorted(Comparator
                .comparingDouble(LongTermMemoryRetrievalHit::score).reversed()
                .thenComparing(LongTermMemoryRetrievalHit::summary)
                .thenComparing(LongTermMemoryRetrievalHit::memoryId))
            .toList();

        var expanded = appendGraphExpandedHits(ranked, normalized);
        var selected = applyLimitAndBudget(expanded, normalized.limit(), normalized.tokenBudget());
        selected = touchSelected(selected);
        return new LongTermMemoryRetrievalResult(
            selected,
            expanded.size(),
            selected.stream().mapToInt(LongTermMemoryRetrievalHit::tokenCount).sum()
        );
    }

    @Transactional
    public LongTermMemoryRetrievalResult retrieveWithQueryVariants(
        LongTermMemoryQuery query,
        List<QueryVariant> queryVariants
    ) {
        validate(query);
        var normalized = normalize(query);
        var variants = memoryVariants(queryVariants, normalized);
        var routeLimit = routeLimit(normalized.limit());
        var diagnostics = new ArrayList<RetrievalRouteDiagnostic>();
        var candidates = new LinkedHashMap<String, MemoryRouteAccumulator>();
        var routeHitCount = 0;

        routeHitCount += collectRouteHits(candidates, semanticRouteHits(normalized, variants, routeLimit, diagnostics), diagnostics);
        var activeMemories = longTermMemories.findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE);
        routeHitCount += collectRouteHits(candidates, metadataRouteHits(normalized, variants, activeMemories, routeLimit, diagnostics), diagnostics);
        routeHitCount += collectRouteHits(candidates, exactRouteHits(variants, activeMemories, routeLimit, diagnostics), diagnostics);
        routeHitCount += collectRouteHits(candidates, graphRouteHits(variants, routeLimit, diagnostics), diagnostics);

        if (candidates.isEmpty()) {
            return new LongTermMemoryRetrievalResult(List.of(), 0, 0, diagnostics);
        }
        if (routeHitCount > candidates.size()) {
            diagnostics.add(fusionDiagnostic(
                normalized.limit(),
                candidates.size(),
                "deduplicated-routes:" + (routeHitCount - candidates.size())
            ));
        }

        var fusionCandidateLimit = fusionCandidateLimit(normalized.limit());
        var fusedCandidates = candidates.values().stream()
            .map(this::fusedHit)
            .sorted(Comparator
                .comparingDouble(LongTermMemoryRetrievalHit::score).reversed()
                .thenComparing(hit -> hit.routeEvidence().size(), Comparator.reverseOrder())
                .thenComparing(LongTermMemoryRetrievalHit::summary)
                .thenComparing(LongTermMemoryRetrievalHit::memoryId))
            .toList();
        if (fusedCandidates.size() > fusionCandidateLimit) {
            diagnostics.add(fusionDiagnostic(
                fusionCandidateLimit,
                fusedCandidates.size(),
                "candidate-limit-applied:" + fusionCandidateLimit
            ));
        }
        var fused = fusedCandidates.stream()
            .limit(fusionCandidateLimit)
            .toList();

        var selected = applyLimitAndBudget(fused, normalized.limit(), normalized.tokenBudget());
        selected = touchSelected(selected);
        return new LongTermMemoryRetrievalResult(
            selected,
            fused.size(),
            selected.stream().mapToInt(LongTermMemoryRetrievalHit::tokenCount).sum(),
            diagnostics
        );
    }

    private List<QueryVariant> memoryVariants(List<QueryVariant> queryVariants, LongTermMemoryQuery query) {
        var variants = queryVariants == null ? List.<QueryVariant>of() : queryVariants.stream()
            .filter(variant -> variant != null && memoryCorpus(variant.targetCorpus()))
            .toList();
        if (!variants.isEmpty()) {
            return variants;
        }
        return List.of(new QueryVariant(
            "qv-memory-base",
            query.rawQuery(),
            QueryIntent.RAW_TASK,
            QueryTargetCorpus.ALL,
            query.stageProfile(),
            new QueryFilters(
                query.systemName(),
                query.moduleName(),
                query.apiPath(),
                null,
                null,
                query.errorCode(),
                null,
                null,
                null,
                null,
                null,
                query.tags(),
                List.of(),
                List.of()
            ),
            1,
            "Base memory query keeps the existing read path available."
        ));
    }

    private boolean memoryCorpus(QueryTargetCorpus targetCorpus) {
        return targetCorpus == null
            || targetCorpus == QueryTargetCorpus.ALL
            || targetCorpus == QueryTargetCorpus.MEMORY
            || targetCorpus == QueryTargetCorpus.GRAPH;
    }

    private boolean graphCorpus(QueryTargetCorpus targetCorpus) {
        return targetCorpus == null
            || targetCorpus == QueryTargetCorpus.ALL
            || targetCorpus == QueryTargetCorpus.MEMORY
            || targetCorpus == QueryTargetCorpus.GRAPH;
    }

    private List<RouteHit> semanticRouteHits(
        LongTermMemoryQuery baseQuery,
        List<QueryVariant> variants,
        int routeLimit,
        List<RetrievalRouteDiagnostic> diagnostics
    ) {
        var routeHits = new ArrayList<RouteHit>();
        for (var variant : variants) {
            if (variant.targetCorpus() == QueryTargetCorpus.GRAPH) {
                continue;
            }
            var routeQuery = queryForVariant(baseQuery, variant, routeLimit);
            var queryEmbedding = embeddingService.embedQuery(routeQuery.rawQuery());
            validateEmbedding(queryEmbedding);
            var candidateLimit = Math.max(routeLimit * 4, 24);
            var candidates = longTermMemories.findPgvectorCandidates(
                routeQuery.scopeTypes(),
                routeQuery.systemName(),
                routeQuery.moduleName(),
                routeQuery.apiPath(),
                routeQuery.errorCode(),
                routeQuery.tags(),
                routeQuery.stageProfile(),
                queryEmbedding,
                candidateLimit
            );
            if (candidates.isEmpty()) {
                diagnostics.add(routeDiagnostic(SEMANTIC_ROUTE, variant, routeLimit, SEMANTIC_CONFIDENCE_GATE, 0, "empty-route-result"));
                continue;
            }
            var newest = candidates.stream().mapToLong(candidate -> candidate.memory().getUpdatedAt().toEpochMilli()).max().orElse(0L);
            var oldest = candidates.stream().mapToLong(candidate -> candidate.memory().getUpdatedAt().toEpochMilli()).min().orElse(newest);
            var ranked = candidates.stream()
                .filter(candidate -> usableMemory(candidate.memory(), SEMANTIC_CONFIDENCE_GATE))
                .map(candidate -> score(candidate, routeQuery, newest, oldest))
                .sorted(Comparator
                    .comparingDouble(LongTermMemoryRetrievalHit::score).reversed()
                    .thenComparing(LongTermMemoryRetrievalHit::summary)
                    .thenComparing(LongTermMemoryRetrievalHit::memoryId))
                .limit(routeLimit)
                .toList();
            for (var index = 0; index < ranked.size(); index++) {
                var hit = ranked.get(index);
                routeHits.add(new RouteHit(hit, routeEvidence(
                    SEMANTIC_ROUTE,
                    variant,
                    index + 1,
                    hit.score(),
                    firstReason(hit.matchReasons(), "semantic-match"),
                    semanticEvidence(hit)
                )));
            }
            diagnostics.add(routeDiagnostic(
                SEMANTIC_ROUTE,
                variant,
                routeLimit,
                SEMANTIC_CONFIDENCE_GATE,
                ranked.size(),
                ranked.isEmpty() ? "empty-route-result" : "ok"
            ));
        }
        return List.copyOf(routeHits);
    }

    private List<RouteHit> metadataRouteHits(
        LongTermMemoryQuery baseQuery,
        List<QueryVariant> variants,
        List<LongTermMemory> activeMemories,
        int routeLimit,
        List<RetrievalRouteDiagnostic> diagnostics
    ) {
        var routeHits = new ArrayList<RouteHit>();
        for (var variant : variants) {
            if (variant.targetCorpus() == QueryTargetCorpus.GRAPH || !hasMetadataFilter(baseQuery, variant.filters())) {
                diagnostics.add(routeDiagnostic(METADATA_ROUTE, variant, routeLimit, METADATA_CONFIDENCE_GATE, 0, "no-applicable-filter"));
                continue;
            }
            var scored = activeMemories.stream()
                .filter(memory -> usableMemory(memory, METADATA_CONFIDENCE_GATE))
                .map(memory -> routeScore(memory, metadataScore(memory, baseQuery, variant.filters())))
                .filter(scoredMemory -> scoredMemory.score().score() >= METADATA_CONFIDENCE_GATE)
                .sorted(Comparator
                    .comparingDouble((ScoredRouteMemory scoredMemory) -> scoredMemory.score().score()).reversed()
                    .thenComparing(scoredMemory -> scoredMemory.memory().getSummary())
                    .thenComparing(scoredMemory -> scoredMemory.memory().getMemoryId()))
                .limit(routeLimit)
                .toList();
            for (var index = 0; index < scored.size(); index++) {
                var scoredMemory = scored.get(index);
                var hit = routeHit(scoredMemory.memory(), scoredMemory.score(), METADATA_ROUTE);
                routeHits.add(new RouteHit(hit, routeEvidence(
                    METADATA_ROUTE,
                    variant,
                    index + 1,
                    scoredMemory.score().score(),
                    firstReason(scoredMemory.score().reasons(), "metadata-match"),
                    scoredMemory.score().sourceEvidence()
                )));
            }
            diagnostics.add(routeDiagnostic(METADATA_ROUTE, variant, routeLimit, METADATA_CONFIDENCE_GATE, scored.size(), scored.isEmpty() ? "empty-route-result" : "ok"));
        }
        return List.copyOf(routeHits);
    }

    private List<RouteHit> exactRouteHits(
        List<QueryVariant> variants,
        List<LongTermMemory> activeMemories,
        int routeLimit,
        List<RetrievalRouteDiagnostic> diagnostics
    ) {
        var routeHits = new ArrayList<RouteHit>();
        for (var variant : variants) {
            if (variant.targetCorpus() == QueryTargetCorpus.GRAPH || !hasExactEntityFilter(variant.filters())) {
                diagnostics.add(routeDiagnostic(EXACT_ROUTE, variant, routeLimit, EXACT_CONFIDENCE_GATE, 0, "no-applicable-filter"));
                continue;
            }
            var scored = activeMemories.stream()
                .filter(memory -> usableMemory(memory, EXACT_CONFIDENCE_GATE))
                .map(memory -> routeScore(memory, exactEntityScore(memory, variant.filters())))
                .filter(scoredMemory -> scoredMemory.score().score() >= EXACT_CONFIDENCE_GATE)
                .sorted(Comparator
                    .comparingDouble((ScoredRouteMemory scoredMemory) -> scoredMemory.score().score()).reversed()
                    .thenComparing(scoredMemory -> scoredMemory.memory().getSummary())
                    .thenComparing(scoredMemory -> scoredMemory.memory().getMemoryId()))
                .limit(routeLimit)
                .toList();
            for (var index = 0; index < scored.size(); index++) {
                var scoredMemory = scored.get(index);
                var hit = routeHit(scoredMemory.memory(), scoredMemory.score(), EXACT_ROUTE);
                routeHits.add(new RouteHit(hit, routeEvidence(
                    EXACT_ROUTE,
                    variant,
                    index + 1,
                    scoredMemory.score().score(),
                    firstReason(scoredMemory.score().reasons(), "exact-entity-match"),
                    scoredMemory.score().sourceEvidence()
                )));
            }
            diagnostics.add(routeDiagnostic(EXACT_ROUTE, variant, routeLimit, EXACT_CONFIDENCE_GATE, scored.size(), scored.isEmpty() ? "empty-route-result" : "ok"));
        }
        return List.copyOf(routeHits);
    }

    private List<RouteHit> graphRouteHits(
        List<QueryVariant> variants,
        int routeLimit,
        List<RetrievalRouteDiagnostic> diagnostics
    ) {
        var routeHits = new ArrayList<RouteHit>();
        for (var variant : variants) {
            if (!graphCorpus(variant.targetCorpus())) {
                continue;
            }
            if (graphQueryService == null) {
                diagnostics.add(routeDiagnostic(GRAPH_ROUTE, variant, routeLimit, GRAPH_CONFIDENCE_GATE, 0, "graph-service-unavailable"));
                continue;
            }
            var seeds = graphSeeds(variant.filters());
            if (seeds.isEmpty()) {
                diagnostics.add(routeDiagnostic(GRAPH_ROUTE, variant, routeLimit, GRAPH_CONFIDENCE_GATE, 0, "no-graph-seed"));
                continue;
            }
            var graphHits = new LinkedHashMap<String, RouteHit>();
            for (var seed : seeds) {
                var graphResult = graphQueryService.queryRelated(seed, 2, Math.max(routeLimit * 3, 12));
                for (var related : graphResult.relatedMemories()) {
                    if (related.confidence() < GRAPH_CONFIDENCE_GATE || graphHits.containsKey(related.memoryId())) {
                        continue;
                    }
                    longTermMemories.findById(related.memoryId())
                        .filter(memory -> usableMemory(memory, GRAPH_CONFIDENCE_GATE))
                        .map(memory -> graphHit(memory, related))
                        .ifPresent(hit -> graphHits.put(hit.memoryId(), new RouteHit(hit, routeEvidence(
                            GRAPH_ROUTE,
                            variant,
                            graphHits.size() + 1,
                            hit.score(),
                            firstReason(hit.matchReasons(), related.matchReason()),
                            graphEvidence(related)
                        ))));
                    if (graphHits.size() >= routeLimit) {
                        break;
                    }
                }
                if (graphHits.size() >= routeLimit) {
                    break;
                }
            }
            routeHits.addAll(graphHits.values());
            diagnostics.add(routeDiagnostic(GRAPH_ROUTE, variant, routeLimit, GRAPH_CONFIDENCE_GATE, graphHits.size(), graphHits.isEmpty() ? "empty-route-result" : "ok"));
        }
        return List.copyOf(routeHits);
    }

    private LongTermMemoryQuery queryForVariant(LongTermMemoryQuery baseQuery, QueryVariant variant, int routeLimit) {
        var filters = variant.filters();
        return new LongTermMemoryQuery(
            firstNonBlank(variant.stageProfile(), baseQuery.stageProfile()),
            firstNonBlank(variant.queryText(), baseQuery.rawQuery()),
            firstNonBlank(filters == null ? null : filters.systemName(), baseQuery.systemName()),
            firstNonBlank(filters == null ? null : filters.moduleName(), baseQuery.moduleName()),
            firstNonBlank(filters == null ? null : filters.apiPath(), baseQuery.apiPath()),
            firstNonBlank(filters == null ? null : filters.errorCode(), baseQuery.errorCode()),
            mergeTags(baseQuery.tags(), filters == null ? List.of() : filters.tags()),
            scopeTypesFor(baseQuery.scopeTypes(), filters == null ? List.of() : filters.factTypes()),
            routeLimit,
            baseQuery.tokenBudget()
        );
    }

    private List<MemoryScopeType> scopeTypesFor(List<MemoryScopeType> baseScopeTypes, List<MemoryFactType> factTypes) {
        var scopeTypes = new LinkedHashSet<MemoryScopeType>();
        if (baseScopeTypes != null) {
            scopeTypes.addAll(baseScopeTypes);
        }
        if (factTypes != null) {
            for (var factType : factTypes) {
                var scopeType = scopeTypeFor(factType);
                if (scopeType != null) {
                    scopeTypes.add(scopeType);
                }
            }
        }
        return List.copyOf(scopeTypes);
    }

    private MemoryScopeType scopeTypeFor(MemoryFactType factType) {
        if (factType == null) {
            return null;
        }
        return switch (factType) {
            case TESTING_PATTERN -> MemoryScopeType.TESTING_PATTERN;
            case PREFERENCE -> MemoryScopeType.PREFERENCE;
            case PROJECT_KNOWLEDGE -> MemoryScopeType.PROJECT_KNOWLEDGE;
            case FAILURE_PATTERN, POLICY_LEARNING, SUITE_DEPENDENCY_FACT, VARIABLE_EXTRACTION_FACT,
                BUSINESS_PRECONDITION_FACT -> MemoryScopeType.FAILURE_PATTERN;
        };
    }

    private int routeLimit(int requestedLimit) {
        return Math.max(1, Math.min(Math.max(requestedLimit, DEFAULT_ROUTE_LIMIT), DEFAULT_ROUTE_LIMIT));
    }

    private int fusionCandidateLimit(int finalLimit) {
        return Math.max(finalLimit * 4, 24);
    }

    private boolean usableMemory(LongTermMemory memory, double confidenceGate) {
        return memory.getStatus() == MemoryStatus.ACTIVE
            && (memory.getConfidence() == null || memory.getConfidence() >= confidenceGate);
    }

    private int collectRouteHits(
        Map<String, MemoryRouteAccumulator> candidates,
        List<RouteHit> routeHits,
        List<RetrievalRouteDiagnostic> diagnostics
    ) {
        for (var routeHit : routeHits) {
            var routeHitForFusion = routeHit;
            if (routeHit.evidence() == null) {
                diagnostics.add(fusionDiagnostic(1, 0, "missing-route-evidence"));
                routeHitForFusion = new RouteHit(routeHit.hit(), fallbackRouteEvidence(routeHit.hit()));
            }
            var memoryId = routeHitForFusion.hit().memoryId();
            var firstHit = routeHitForFusion.hit();
            candidates
                .computeIfAbsent(memoryId, ignored -> new MemoryRouteAccumulator(firstHit))
                .add(routeHitForFusion);
        }
        return routeHits.size();
    }

    private boolean hasMetadataFilter(LongTermMemoryQuery baseQuery, QueryFilters filters) {
        return filters != null && (
            hasText(filters.systemName())
                || hasText(filters.moduleName())
                || hasText(filters.apiPath())
                || hasText(filters.httpMethod())
                || hasText(filters.businessEntity())
                || hasText(filters.errorCode())
                || hasText(filters.suiteId())
                || hasText(filters.variableKey())
                || hasText(filters.policyReason())
                || !filters.tags().isEmpty()
                || !filters.factTypes().isEmpty()
        ) || baseQuery != null && (
            hasText(baseQuery.systemName())
                || hasText(baseQuery.moduleName())
                || hasText(baseQuery.apiPath())
                || hasText(baseQuery.errorCode())
                || !baseQuery.tags().isEmpty()
                || !baseQuery.scopeTypes().isEmpty()
        );
    }

    private boolean hasExactEntityFilter(QueryFilters filters) {
        return filters != null && (
            hasText(filters.errorCode())
                || hasText(filters.apiPath())
                || hasText(filters.variableKey())
                || hasText(filters.toolName())
                || hasText(filters.policyReason())
        );
    }

    private RouteScore metadataScore(LongTermMemory memory, LongTermMemoryQuery baseQuery, QueryFilters filters) {
        var matched = new LinkedHashMap<String, Object>();
        var reasons = new ArrayList<String>();
        var componentScores = new LinkedHashMap<String, Double>();
        var score = 0.0d;
        var total = 0.0d;

        var scopeTypes = scopeTypesFor(baseQuery.scopeTypes(), filters == null ? List.of() : filters.factTypes());
        if (!scopeTypes.isEmpty()) {
            total += 0.12d;
            if (scopeTypes.contains(memory.getScopeType())) {
                score += 0.12d;
                matched.put("scopeType", memory.getScopeType().name());
                reasons.add("metadata-scope-type");
                componentScores.put("scopeType", 1.0d);
            }
        }
        var factTypes = filters == null ? List.<MemoryFactType>of() : filters.factTypes();
        if (!factTypes.isEmpty()) {
            total += 0.16d;
            var factType = matchingFactType(memory, factTypes);
            if (factType != null) {
                score += 0.16d;
                matched.put("factType", factType.metadataValue());
                reasons.add("metadata-fact-type");
                componentScores.put("factType", 1.0d);
            }
        }
        var fieldScores = new ArrayList<FieldScore>();
        fieldScores.add(fieldScore(memory, "systemName", firstNonBlank(filters == null ? null : filters.systemName(), baseQuery.systemName()), 0.06d, "systemName", "system", "serviceName"));
        fieldScores.add(fieldScore(memory, "module", firstNonBlank(filters == null ? null : filters.moduleName(), baseQuery.moduleName()), 0.08d, "module", "component"));
        fieldScores.add(fieldScore(memory, "apiPath", firstNonBlank(filters == null ? null : filters.apiPath(), baseQuery.apiPath()), 0.12d, "apiPath", "path", "endpoint"));
        fieldScores.add(fieldScore(memory, "httpMethod", filters == null ? null : filters.httpMethod(), 0.08d, "httpMethod", "method"));
        fieldScores.add(fieldScore(memory, "errorCode", firstNonBlank(filters == null ? null : filters.errorCode(), baseQuery.errorCode()), 0.12d, "errorCode", "error", "code"));
        fieldScores.add(fieldScore(memory, "businessEntity", filters == null ? null : filters.businessEntity(), 0.12d, "businessEntity", "businessObject", "entity", "entityName", "resource"));
        fieldScores.add(fieldScore(memory, "suiteId", filters == null ? null : filters.suiteId(), 0.10d, "suiteId", "suite"));
        fieldScores.add(fieldScore(memory, "variableKey", filters == null ? null : filters.variableKey(), 0.12d, "variableKey", "suiteVariableKey", "targetKey"));
        fieldScores.add(fieldScore(memory, "policyReason", filters == null ? null : filters.policyReason(), 0.10d, "policyReason", "policyReasonCode", "reasonCode", "approvalReason"));
        for (var fieldScore : fieldScores) {
            if (!fieldScore.applicable()) {
                continue;
            }
            total += fieldScore.weight();
            if (fieldScore.matched()) {
                score += fieldScore.weight();
                matched.put(fieldScore.fieldName(), fieldScore.expected());
                reasons.add("metadata-" + kebab(fieldScore.fieldName()));
                componentScores.put(fieldScore.fieldName(), 1.0d);
            }
        }
        var tags = mergeTags(baseQuery.tags(), filters == null ? List.of() : filters.tags());
        if (!tags.isEmpty()) {
            total += 0.08d;
            var tagScore = tagOverlap(memory.getTags(), tags);
            if (tagScore > 0.0d) {
                score += tagScore * 0.08d;
                matched.put("tags", tags);
                reasons.add("metadata-tags");
                componentScores.put("tags", tagScore);
            }
        }

        if (total == 0.0d) {
            return RouteScore.empty();
        }
        var normalizedScore = score / total;
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("matchedMetadata", matched);
        evidence.put("componentScores", componentScores);
        return new RouteScore(normalizedScore, componentScores, reasons, evidence);
    }

    private RouteScore exactEntityScore(LongTermMemory memory, QueryFilters filters) {
        var matched = new LinkedHashMap<String, Object>();
        var reasons = new ArrayList<String>();
        var componentScores = new LinkedHashMap<String, Double>();
        var score = 0.0d;
        var total = 0.0d;
        var fieldScores = List.of(
            fieldScore(memory, "errorCode", filters.errorCode(), 0.20d, "errorCode", "error", "code"),
            fieldScore(memory, "apiPath", filters.apiPath(), 0.20d, "apiPath", "path", "endpoint"),
            fieldScore(memory, "variableKey", filters.variableKey(), 0.20d, "variableKey", "suiteVariableKey", "targetKey"),
            fieldScore(memory, "toolName", filters.toolName(), 0.20d, "toolName", "tool", "toolId", "plannerToolName"),
            fieldScore(memory, "policyReason", filters.policyReason(), 0.20d, "policyReason", "policyReasonCode", "reasonCode", "approvalReason")
        );
        for (var fieldScore : fieldScores) {
            if (!fieldScore.applicable()) {
                continue;
            }
            total += fieldScore.weight();
            if (fieldScore.matched()) {
                score += fieldScore.weight();
                matched.put(fieldScore.fieldName(), fieldScore.expected());
                reasons.add("exact-" + kebab(fieldScore.fieldName()));
                componentScores.put(fieldScore.fieldName(), 1.0d);
            }
        }
        if (total == 0.0d) {
            return RouteScore.empty();
        }
        var normalizedScore = score / total;
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("matchedEntities", matched);
        evidence.put("componentScores", componentScores);
        return new RouteScore(normalizedScore, componentScores, reasons, evidence);
    }

    private FieldScore fieldScore(
        LongTermMemory memory,
        String fieldName,
        String expected,
        double weight,
        String... metadataKeys
    ) {
        if (!hasText(expected)) {
            return new FieldScore(fieldName, null, weight, false, false);
        }
        var actual = metadataValue(memory.getMetadata(), metadataKeys);
        var matched = actual != null && expected.trim().equalsIgnoreCase(actual.trim());
        return new FieldScore(fieldName, expected.trim(), weight, true, matched);
    }

    private MemoryFactType matchingFactType(LongTermMemory memory, List<MemoryFactType> factTypes) {
        var actual = metadataValue(memory.getMetadata(), "factType", "memoryFactType");
        if (!hasText(actual)) {
            return null;
        }
        var normalizedActual = actual.trim().toLowerCase(Locale.ROOT);
        for (var factType : factTypes) {
            if (factType != null && (
                factType.metadataValue().equals(normalizedActual)
                    || factType.name().toLowerCase(Locale.ROOT).equals(normalizedActual)
            )) {
                return factType;
            }
        }
        return null;
    }

    private double tagOverlap(List<String> memoryTags, List<String> expectedTags) {
        if (memoryTags == null || memoryTags.isEmpty() || expectedTags == null || expectedTags.isEmpty()) {
            return 0.0d;
        }
        var normalizedMemoryTags = memoryTags.stream()
            .filter(StringUtils::hasText)
            .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var matches = 0;
        for (var tag : expectedTags) {
            if (normalizedMemoryTags.contains(tag.trim().toLowerCase(Locale.ROOT))) {
                matches++;
            }
        }
        return matches / (double) expectedTags.size();
    }

    private LongTermMemoryRetrievalHit routeHit(LongTermMemory memory, RouteScore routeScore, String routeName) {
        var metadata = new LinkedHashMap<>(memory.getMetadata());
        metadata.put("retrievalChannel", routeName);
        metadata.put("routeScore", routeScore.score());
        var componentScores = new LinkedHashMap<String, Double>();
        componentScores.put(routeName, routeScore.score());
        componentScores.putAll(routeScore.componentScores());
        return new LongTermMemoryRetrievalHit(
            memory.getMemoryId(),
            memory.getScopeType(),
            memory.getSummary(),
            memory.getContent(),
            memory.getFullContent(),
            List.copyOf(memory.getTags()),
            memory.getSourceType(),
            memory.getSourceRef(),
            memory.getConfidence(),
            memory.getImportance(),
            memory.getSuccessContribution(),
            memory.getHitCount(),
            memory.getLastUsedAt(),
            Map.copyOf(metadata),
            estimateTokens(memory),
            routeScore.score(),
            Map.copyOf(componentScores),
            routeScore.reasons(),
            routeScore.score() < 0.35d,
            List.of()
        );
    }

    private LongTermMemoryRetrievalHit fusedHit(MemoryRouteAccumulator accumulator) {
        var bestHit = accumulator.bestHit();
        var routeEvidence = List.copyOf(accumulator.routeEvidence());
        var routeNames = routeEvidence.stream()
            .map(RetrievalRouteEvidence::routeName)
            .distinct()
            .toList();
        var queryVariantIds = routeEvidence.stream()
            .map(RetrievalRouteEvidence::queryVariantId)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        var fusedScore = fusedScore(routeEvidence);
        var metadata = new LinkedHashMap<>(bestHit.metadata());
        for (var hit : accumulator.hits()) {
            copyGraphMetadata(metadata, hit.metadata());
        }
        var bestEvidence = routeEvidence.stream()
            .min(Comparator
                .comparingInt(RetrievalRouteEvidence::routeRank)
                .thenComparing(RetrievalRouteEvidence::routeName))
            .orElseGet(() -> fallbackRouteEvidence(bestHit));
        metadata.put("retrievalRoutes", routeNames);
        metadata.put("queryVariantIds", queryVariantIds);
        metadata.put("routeEvidence", routeEvidenceView(routeEvidence));
        metadata.put("preFusionRoute", bestEvidence.routeName());
        metadata.put("preFusionRank", bestEvidence.routeRank());
        metadata.put("preFusionRanks", routeEvidence.stream()
            .collect(java.util.stream.Collectors.toMap(
                item -> item.routeName() + ":" + item.queryVariantId(),
                RetrievalRouteEvidence::routeRank,
                (left, right) -> left,
                LinkedHashMap::new
            )));
        metadata.putIfAbsent("candidateRank", bestEvidence.routeRank());
        metadata.put("fusedScore", fusedScore);
        metadata.put("fusionExplanation", fusionExplanation(routeEvidence, fusedScore));

        var componentScores = new LinkedHashMap<>(bestHit.componentScores());
        for (var evidence : routeEvidence) {
            componentScores.merge("route." + evidence.routeName(), evidence.routeScore(), Math::max);
        }
        componentScores.put("routeAgreement", Math.min(1.0d, routeNames.size() / 4.0d));
        componentScores.put("rankContribution", routeEvidence.stream()
            .mapToDouble(this::rankContribution)
            .sum());
        var matchReasons = new LinkedHashSet<>(accumulator.matchReasons());
        matchReasons.add("route-fusion");
        if (routeEvidence.size() > 1) {
            matchReasons.add("route-agreement");
        }

        return new LongTermMemoryRetrievalHit(
            bestHit.memoryId(),
            bestHit.scopeType(),
            bestHit.summary(),
            bestHit.content(),
            bestHit.fullContent(),
            bestHit.tags(),
            bestHit.sourceType(),
            bestHit.sourceRef(),
            bestHit.confidence(),
            bestHit.importance(),
            bestHit.successContribution(),
            bestHit.hitCount(),
            bestHit.lastUsedAt(),
            Map.copyOf(metadata),
            bestHit.tokenCount(),
            fusedScore,
            Map.copyOf(componentScores),
            List.copyOf(matchReasons),
            fusedScore < 0.35d,
            routeEvidence
        );
    }

    private double fusedScore(List<RetrievalRouteEvidence> routeEvidence) {
        var bestWeighted = routeEvidence.stream()
            .mapToDouble(this::weightedRouteScore)
            .max()
            .orElse(0.0d);
        var rankContributionTotal = routeEvidence.stream()
            .mapToDouble(this::rankContribution)
            .sum();
        return Math.min(1.0d, bestWeighted + rankContributionTotal + routeAgreementBonus(routeEvidence));
    }

    private double weightedRouteScore(RetrievalRouteEvidence evidence) {
        return evidence.routeScore() * routeWeight(evidence.routeName());
    }

    private double rankContribution(RetrievalRouteEvidence evidence) {
        return routeWeight(evidence.routeName()) / (FUSION_RANK_CONSTANT + evidence.routeRank());
    }

    private double routeAgreementBonus(List<RetrievalRouteEvidence> routeEvidence) {
        var routeCount = routeEvidence.stream()
            .map(RetrievalRouteEvidence::routeName)
            .distinct()
            .count();
        return Math.min(0.15d, Math.max(0L, routeCount - 1L) * 0.05d);
    }

    private double routeWeight(String routeName) {
        return switch (routeName) {
            case SEMANTIC_ROUTE -> 1.00d;
            case EXACT_ROUTE -> 0.95d;
            case METADATA_ROUTE -> 0.90d;
            case GRAPH_ROUTE -> 0.85d;
            default -> 0.80d;
        };
    }

    private Map<String, Object> fusionExplanation(List<RetrievalRouteEvidence> routeEvidence, double fusedScore) {
        var routeWeights = new LinkedHashMap<String, Double>();
        var rankContributions = new LinkedHashMap<String, Double>();
        var preFusionRanks = new LinkedHashMap<String, Integer>();
        for (var evidence : routeEvidence) {
            var key = evidence.routeName() + ":" + evidence.queryVariantId();
            routeWeights.putIfAbsent(evidence.routeName(), routeWeight(evidence.routeName()));
            rankContributions.put(key, rankContribution(evidence));
            preFusionRanks.put(key, evidence.routeRank());
        }
        var explanation = new LinkedHashMap<String, Object>();
        explanation.put("formula", "bestWeightedRouteScore + rankContributionTotal + routeAgreementBonus");
        explanation.put("routeCount", routeEvidence.size());
        explanation.put("routeNames", routeEvidence.stream().map(RetrievalRouteEvidence::routeName).distinct().toList());
        explanation.put("routeWeights", routeWeights);
        explanation.put("preFusionRanks", preFusionRanks);
        explanation.put("rankContributions", rankContributions);
        explanation.put("bestWeightedRouteScore", routeEvidence.stream().mapToDouble(this::weightedRouteScore).max().orElse(0.0d));
        explanation.put("rankContributionTotal", routeEvidence.stream().mapToDouble(this::rankContribution).sum());
        explanation.put("routeAgreementBonus", routeAgreementBonus(routeEvidence));
        explanation.put("fusedScore", fusedScore);
        return Map.copyOf(explanation);
    }

    private void copyGraphMetadata(Map<String, Object> target, Map<String, Object> source) {
        copyIfAbsent(target, source, "graphMatchReason");
        copyIfAbsent(target, source, "graphRelationPath");
        copyIfAbsent(target, source, "graphRelationConfidence");
        copyIfAbsent(target, source, "graphSourceMemoryIds");
        copyIfAbsent(target, source, "graphSourceRefs");
        copyIfAbsent(target, source, "graphFactFingerprints");
        copyIfAbsent(target, source, "graphEvidenceSummaries");
    }

    private void copyIfAbsent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (!target.containsKey(key) && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private List<Map<String, Object>> routeEvidenceView(List<RetrievalRouteEvidence> routeEvidence) {
        return routeEvidence.stream()
            .map(evidence -> {
                var value = new LinkedHashMap<String, Object>();
                value.put("routeName", evidence.routeName());
                value.put("queryVariantId", evidence.queryVariantId());
                value.put("routeRank", evidence.routeRank());
                value.put("routeScore", evidence.routeScore());
                value.put("matchReason", evidence.matchReason());
                value.put("routeWeight", routeWeight(evidence.routeName()));
                value.put("weightedRouteScore", weightedRouteScore(evidence));
                value.put("rankContribution", rankContribution(evidence));
                value.put("sourceEvidence", evidence.sourceEvidence());
                return Map.copyOf(value);
            })
            .toList();
    }

    private RetrievalRouteEvidence fallbackRouteEvidence(LongTermMemoryRetrievalHit hit) {
        var metadataRank = hit.metadata().get("candidateRank");
        var rank = metadataRank instanceof Number number ? Math.max(1, number.intValue()) : 1;
        return new RetrievalRouteEvidence(
            "unknown_memory_route",
            "qv-unknown",
            rank,
            Math.max(0.0d, hit.score()),
            "missing route evidence fallback",
            Map.of("memoryId", hit.memoryId())
        );
    }

    private RetrievalRouteEvidence routeEvidence(
        String routeName,
        QueryVariant variant,
        int routeRank,
        double routeScore,
        String matchReason,
        Map<String, Object> sourceEvidence
    ) {
        return new RetrievalRouteEvidence(
            routeName,
            variant == null ? null : variant.deterministicId(),
            routeRank,
            routeScore,
            matchReason,
            sourceEvidence
        );
    }

    private RetrievalRouteDiagnostic routeDiagnostic(
        String routeName,
        QueryVariant variant,
        int routeLimit,
        double confidenceGate,
        int candidateCount,
        String diagnostic
    ) {
        return new RetrievalRouteDiagnostic(
            routeName,
            variant == null ? null : variant.deterministicId(),
            routeLimit,
            confidenceGate,
            candidateCount,
            diagnostic
        );
    }

    private RetrievalRouteDiagnostic fusionDiagnostic(
        int routeLimit,
        int candidateCount,
        String diagnostic
    ) {
        return new RetrievalRouteDiagnostic(
            "route_fusion",
            null,
            routeLimit,
            0.0d,
            candidateCount,
            diagnostic
        );
    }

    private Map<String, Object> semanticEvidence(LongTermMemoryRetrievalHit hit) {
        var evidence = new LinkedHashMap<String, Object>();
        copyIfPresent(evidence, hit.metadata(), "retrievalChannel");
        copyIfPresent(evidence, hit.metadata(), "vectorDistance");
        copyIfPresent(evidence, hit.metadata(), "candidateRank");
        evidence.put("componentScores", hit.componentScores());
        return Map.copyOf(evidence);
    }

    private Map<String, Object> graphEvidence(MemoryGraphRelatedMemory related) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("graphMatchReason", related.matchReason());
        evidence.put("graphRelationPath", related.relationPath());
        evidence.put("graphRelationConfidence", related.confidence());
        evidence.put("graphSourceMemoryIds", related.sourceMemoryIds());
        evidence.put("graphSourceRefs", related.sourceRefs());
        evidence.put("graphFactFingerprints", related.factFingerprints());
        evidence.put("graphEvidenceSummaries", related.evidenceSummaries());
        return Map.copyOf(evidence);
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private ScoredRouteMemory routeScore(LongTermMemory memory, RouteScore score) {
        return new ScoredRouteMemory(memory, score);
    }

    private String firstReason(List<String> reasons, String fallback) {
        return reasons == null || reasons.isEmpty() ? fallback : reasons.getFirst();
    }

    private String kebab(String value) {
        if (!hasText(value)) {
            return "field";
        }
        return value.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : normalizeNullable(fallback);
    }

    private List<String> mergeTags(List<String> left, List<String> right) {
        var merged = new LinkedHashSet<String>();
        if (left != null) {
            merged.addAll(normalizeTags(left));
        }
        if (right != null) {
            merged.addAll(normalizeTags(right));
        }
        return List.copyOf(merged);
    }

    private List<MemoryGraphSeed> graphSeeds(QueryFilters filters) {
        if (filters == null) {
            return List.of();
        }
        var seeds = new ArrayList<MemoryGraphSeed>();
        addSeed(seeds, MemoryGraphEntityType.ERROR_CODE, filters.errorCode(), null);
        addSeed(seeds, MemoryGraphEntityType.API_PATH, filters.apiPath(), null);
        addSeed(seeds, MemoryGraphEntityType.BUSINESS_ENTITY, filters.businessEntity(), null);
        addSeed(seeds, MemoryGraphEntityType.VARIABLE_KEY, filters.variableKey(), suiteScope(filters.suiteId()));
        addSeed(seeds, MemoryGraphEntityType.POLICY_REASON, filters.policyReason(), null);
        addSeed(seeds, MemoryGraphEntityType.TOOL_NAME, filters.toolName(), null);
        return List.copyOf(seeds);
    }

    private List<LongTermMemoryRetrievalHit> appendGraphExpandedHits(List<LongTermMemoryRetrievalHit> ranked, LongTermMemoryQuery query) {
        if (graphQueryService == null || ranked.isEmpty()) {
            return ranked;
        }
        var existingMemoryIds = ranked.stream()
            .map(LongTermMemoryRetrievalHit::memoryId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var graphHits = new LinkedHashMap<String, LongTermMemoryRetrievalHit>();
        var seedHits = ranked.stream().limit(Math.max(1, Math.min(query.limit(), 8))).toList();
        for (var seedHit : seedHits) {
            for (var seed : graphSeeds(seedHit)) {
                var graphResult = graphQueryService.queryRelated(seed, 2, Math.max(query.limit() * 3, 12));
                for (var related : graphResult.relatedMemories()) {
                    if (existingMemoryIds.contains(related.memoryId()) || graphHits.containsKey(related.memoryId())) {
                        continue;
                    }
                    longTermMemories.findById(related.memoryId())
                        .filter(memory -> memory.getStatus() == MemoryStatus.ACTIVE)
                        .map(memory -> graphHit(memory, related))
                        .ifPresent(hit -> graphHits.put(hit.memoryId(), hit));
                }
            }
        }
        if (graphHits.isEmpty()) {
            return ranked;
        }
        var combined = new ArrayList<LongTermMemoryRetrievalHit>(ranked);
        combined.addAll(graphHits.values().stream()
            .sorted(Comparator
                .comparingDouble(LongTermMemoryRetrievalHit::score).reversed()
                .thenComparing(LongTermMemoryRetrievalHit::summary)
                .thenComparing(LongTermMemoryRetrievalHit::memoryId))
            .toList());
        return List.copyOf(combined);
    }

    private LongTermMemoryRetrievalHit graphHit(LongTermMemory memory, MemoryGraphRelatedMemory related) {
        var metadata = new LinkedHashMap<>(memory.getMetadata());
        metadata.put("retrievalChannel", "graph");
        metadata.put("graphMatchReason", related.matchReason());
        metadata.put("graphRelationPath", related.relationPath());
        metadata.put("graphRelationConfidence", related.confidence());
        metadata.put("graphSourceMemoryIds", related.sourceMemoryIds());
        metadata.put("graphSourceRefs", related.sourceRefs());
        metadata.put("graphFactFingerprints", related.factFingerprints());
        metadata.put("graphEvidenceSummaries", related.evidenceSummaries());
        var pathScore = 1.0d / Math.max(1, related.relationPath().size());
        var memoryConfidence = memory.getConfidence() == null ? 0.55d : memory.getConfidence();
        var importance = memory.getImportance() == null ? 0.50d : memory.getImportance();
        var success = memory.getSuccessContribution() == null ? 0.50d : memory.getSuccessContribution();
        var componentScores = new LinkedHashMap<String, Double>();
        componentScores.put("graphRelation", related.confidence() * 0.50d);
        componentScores.put("graphPath", pathScore * 0.15d);
        componentScores.put("confidence", memoryConfidence * 0.15d);
        componentScores.put("importance", importance * 0.10d);
        componentScores.put("successContribution", success * 0.10d);
        var score = componentScores.values().stream().mapToDouble(Double::doubleValue).sum();
        return new LongTermMemoryRetrievalHit(
            memory.getMemoryId(),
            memory.getScopeType(),
            memory.getSummary(),
            memory.getContent(),
            memory.getFullContent(),
            List.copyOf(memory.getTags()),
            memory.getSourceType(),
            memory.getSourceRef(),
            memory.getConfidence(),
            memory.getImportance(),
            memory.getSuccessContribution(),
            memory.getHitCount(),
            memory.getLastUsedAt(),
            Map.copyOf(metadata),
            estimateTokens(memory),
            score,
            Map.copyOf(componentScores),
            List.of(related.matchReason()),
            related.confidence() < MemoryGraphQueryService.DEFAULT_CONFIDENCE_GATE || score < 0.35d
        );
    }

    private List<MemoryGraphSeed> graphSeeds(LongTermMemoryRetrievalHit hit) {
        var seeds = new ArrayList<MemoryGraphSeed>();
        var metadata = hit.metadata();
        addSeed(seeds, MemoryGraphEntityType.ERROR_CODE, metadataValue(metadata, "errorCode", "error", "code"), null);
        addSeed(seeds, MemoryGraphEntityType.API_PATH, metadataValue(metadata, "apiPath", "path", "endpoint"), null);
        addSeed(seeds, MemoryGraphEntityType.BUSINESS_ENTITY, metadataValue(metadata, "businessEntity", "businessObject", "entity", "entityName", "resource"), null);
        var suiteScope = suiteScope(metadata);
        addSeed(seeds, MemoryGraphEntityType.VARIABLE_KEY, metadataValue(metadata, "variableKey", "suiteVariableKey", "targetKey"), suiteScope);
        addSeed(seeds, MemoryGraphEntityType.POLICY_REASON, metadataValue(metadata, "policyReason", "policyReasonCode", "reasonCode", "approvalReason"), null);
        for (var tag : hit.tags()) {
            addSeed(seeds, MemoryGraphEntityType.TAG, tag, null);
        }
        return List.copyOf(seeds);
    }

    private void addSeed(List<MemoryGraphSeed> seeds, MemoryGraphEntityType entityType, String value, String scope) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        var seed = new MemoryGraphSeed(entityType, value, scope);
        if (!seeds.contains(seed)) {
            seeds.add(seed);
        }
    }

    private String metadataValue(Map<String, Object> metadata, String... keys) {
        for (var key : keys) {
            var value = metadata.get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString();
            }
        }
        var identityHints = metadata.get("identityHints");
        if (identityHints instanceof Map<?, ?> hints) {
            for (var key : keys) {
                var value = hints.get(key);
                if (value != null && StringUtils.hasText(value.toString())) {
                    return value.toString();
                }
            }
        }
        return null;
    }

    private String suiteScope(Map<String, Object> metadata) {
        var suiteId = metadataValue(metadata, "suiteId", "suite");
        return StringUtils.hasText(suiteId) ? "suite:" + suiteId.trim().toLowerCase(Locale.ROOT) : null;
    }

    private String suiteScope(String suiteId) {
        return StringUtils.hasText(suiteId) ? "suite:" + suiteId.trim().toLowerCase(Locale.ROOT) : null;
    }

    private LongTermMemoryRetrievalHit score(
        LongTermMemoryVectorCandidate candidate,
        LongTermMemoryQuery query,
        long newest,
        long oldest
    ) {
        var memory = candidate.memory();
        var structure = structureScore(memory, query);
        var tag = tagScore(memory, query);
        var metadata = new LinkedHashMap<>(
            EmbeddingProfileMetadata.withReindexStatus(memory.getMetadata(), embeddingService.profile())
        );
        metadata.put("retrievalChannel", "pgvector");
        metadata.put("vectorDistance", candidate.vectorDistance());
        metadata.put("candidateRank", candidate.candidateRank());
        var profileCompatible = EmbeddingProfileMetadata.isCompatible(metadata, embeddingService.profile());
        var vector = profileCompatible ? semanticScore(memory, query, candidate.vectorDistance()) : 0.0d;
        var importance = memory.getImportance();
        var confidence = memory.getConfidence();
        var success = memory.getSuccessContribution();
        var hitCount = Math.min(1.0d, memory.getHitCount() / 5.0d);
        var freshness = freshnessScore(memory, newest, oldest);
        var stageFit = stageFitScore(memory, query.stageProfile());
        var weighted = weightedScores(query.stageProfile(), structure, tag, vector, importance, confidence, success, hitCount, freshness, stageFit);
        var score = weighted.values().stream().mapToDouble(Double::doubleValue).sum();
        var reasons = new ArrayList<String>();
        addReason(reasons, structure >= 1.0d, "structure-match");
        addReason(reasons, tag >= 0.5d, "tag-match");
        addReason(reasons, vector >= 0.45d, "semantic-match");
        addReason(reasons, stageFit >= 0.9d, "stage-fit");
        addReason(reasons, !profileCompatible, "reindex-required");

        return new LongTermMemoryRetrievalHit(
            memory.getMemoryId(),
            memory.getScopeType(),
            memory.getSummary(),
            memory.getContent(),
            memory.getFullContent(),
            List.copyOf(memory.getTags()),
            memory.getSourceType(),
            memory.getSourceRef(),
            memory.getConfidence(),
            memory.getImportance(),
            memory.getSuccessContribution(),
            memory.getHitCount(),
            memory.getLastUsedAt(),
            Map.copyOf(metadata),
            estimateTokens(memory),
            score,
            weighted,
            reasons,
            !profileCompatible || score < 0.35d
        );
    }

    private List<LongTermMemoryRetrievalHit> touchSelected(List<LongTermMemoryRetrievalHit> hits) {
        var now = Instant.now();
        var updatedHits = new ArrayList<LongTermMemoryRetrievalHit>(hits.size());
        for (var hit : hits) {
            var memory = longTermMemories.findById(hit.memoryId()).orElseThrow();
            memory.setHitCount(memory.getHitCount() + 1);
            memory.setLastUsedAt(now);
            longTermMemories.save(memory);
            var metadata = new LinkedHashMap<>(hit.metadata());
            metadata.put("selectedAt", now.toString());
            updatedHits.add(new LongTermMemoryRetrievalHit(
                hit.memoryId(),
                hit.scopeType(),
                hit.summary(),
                hit.content(),
                hit.fullContent(),
                hit.tags(),
                hit.sourceType(),
                hit.sourceRef(),
                hit.confidence(),
                hit.importance(),
                hit.successContribution(),
                memory.getHitCount(),
                now,
                metadata,
                hit.tokenCount(),
                hit.score(),
                hit.componentScores(),
                hit.matchReasons(),
                hit.lowConfidence(),
                hit.routeEvidence()
            ));
        }
        return List.copyOf(updatedHits);
    }

    private List<LongTermMemoryRetrievalHit> applyLimitAndBudget(List<LongTermMemoryRetrievalHit> ranked, int limit, int tokenBudget) {
        var selected = new ArrayList<LongTermMemoryRetrievalHit>();
        var tokens = 0;
        for (var hit : ranked) {
            if (selected.size() >= limit) {
                break;
            }
            if (tokens + hit.tokenCount() > tokenBudget) {
                continue;
            }
            selected.add(hit);
            tokens += hit.tokenCount();
        }
        return selected;
    }

    private double structureScore(LongTermMemory memory, LongTermMemoryQuery query) {
        double score = 0.0d;
        score += scoreMetadata(memory, "systemName", query.systemName(), 0.25d);
        score += scoreMetadata(memory, "module", query.moduleName(), 0.30d);
        score += scoreMetadata(memory, "apiPath", query.apiPath(), 0.30d);
        score += scoreMetadata(memory, "errorCode", query.errorCode(), 0.30d);
        return Math.min(1.0d, score);
    }

    private double scoreMetadata(LongTermMemory memory, String key, String expected, double weight) {
        if (!StringUtils.hasText(expected)) {
            return 0.0d;
        }
        var value = memory.getMetadata().get(key);
        return value != null && expected.equalsIgnoreCase(value.toString().trim()) ? weight : 0.0d;
    }

    private double tagScore(LongTermMemory memory, LongTermMemoryQuery query) {
        if (query.tags().isEmpty()) {
            return 0.0d;
        }
        var memoryTags = new LinkedHashSet<>(memory.getTags());
        var matches = 0;
        for (var tag : query.tags()) {
            if (memoryTags.contains(tag)) {
                matches++;
            }
        }
        return matches / (double) query.tags().size();
    }

    private double semanticScore(LongTermMemory memory, LongTermMemoryQuery query, double vectorDistance) {
        var embeddingSimilarity = Math.max(0.0d, 1.0d - vectorDistance);
        var lexicalSimilarity = Math.max(
            tokenSimilarity(query.rawQuery(), memory.getSummary()),
            tokenSimilarity(query.rawQuery(), memory.getContent())
        );
        return Math.max(embeddingSimilarity, lexicalSimilarity);
    }

    private double stageFitScore(LongTermMemory memory, String stageProfile) {
        return switch (normalizeStage(stageProfile)) {
            case "failure_analysis" -> memory.getScopeType() == MemoryScopeType.FAILURE_PATTERN ? 1.0d
                : (memory.getScopeType() == MemoryScopeType.PROJECT_KNOWLEDGE ? 0.55d : 0.35d);
            case "case_generation" -> memory.getScopeType() == MemoryScopeType.TESTING_PATTERN ? 1.0d
                : (memory.getScopeType() == MemoryScopeType.PROJECT_KNOWLEDGE ? 0.8d : 0.35d);
            case "execution_preparation" -> memory.getScopeType() == MemoryScopeType.PROJECT_KNOWLEDGE ? 1.0d
                : (memory.getScopeType() == MemoryScopeType.FAILURE_PATTERN ? 0.75d : 0.40d);
            case "report_generation" -> memory.getScopeType() == MemoryScopeType.PREFERENCE ? 1.0d
                : (memory.getScopeType() == MemoryScopeType.FAILURE_PATTERN ? 0.7d : 0.45d);
            default -> 0.5d;
        };
    }

    private Map<String, Double> weightedScores(
        String stageProfile,
        double structure,
        double tag,
        double vector,
        double importance,
        double confidence,
        double success,
        double hitCount,
        double freshness,
        double stageFit
    ) {
        var weights = stageWeights(stageProfile);
        var weighted = new LinkedHashMap<String, Double>();
        weighted.put("structure", structure * weights.get("structure"));
        weighted.put("tag", tag * weights.get("tag"));
        weighted.put("vector", vector * weights.get("vector"));
        weighted.put("importance", importance * weights.get("importance"));
        weighted.put("confidence", confidence * weights.get("confidence"));
        weighted.put("successContribution", success * weights.get("success"));
        weighted.put("hitCount", hitCount * weights.get("hitCount"));
        weighted.put("freshness", freshness * weights.get("freshness"));
        weighted.put("stageFit", stageFit * weights.get("stageFit"));
        return weighted;
    }

    private Map<String, Double> stageWeights(String stageProfile) {
        var stage = normalizeStage(stageProfile);
        if ("failure_analysis".equals(stage)) {
            return Map.of("structure", 0.12d, "tag", 0.16d, "vector", 0.16d, "importance", 0.10d,
                "confidence", 0.10d, "success", 0.10d, "hitCount", 0.06d, "freshness", 0.08d, "stageFit", 0.12d);
        }
        if ("case_generation".equals(stage)) {
            return Map.of("structure", 0.14d, "tag", 0.12d, "vector", 0.14d, "importance", 0.10d,
                "confidence", 0.08d, "success", 0.10d, "hitCount", 0.08d, "freshness", 0.06d, "stageFit", 0.18d);
        }
        if ("execution_preparation".equals(stage)) {
            return Map.of("structure", 0.18d, "tag", 0.14d, "vector", 0.12d, "importance", 0.10d,
                "confidence", 0.10d, "success", 0.08d, "hitCount", 0.06d, "freshness", 0.07d, "stageFit", 0.15d);
        }
        if ("report_generation".equals(stage)) {
            return Map.of("structure", 0.10d, "tag", 0.12d, "vector", 0.10d, "importance", 0.10d,
                "confidence", 0.10d, "success", 0.12d, "hitCount", 0.08d, "freshness", 0.08d, "stageFit", 0.20d);
        }
        return Map.of("structure", 0.14d, "tag", 0.14d, "vector", 0.14d, "importance", 0.10d,
            "confidence", 0.10d, "success", 0.10d, "hitCount", 0.06d, "freshness", 0.08d, "stageFit", 0.14d);
    }

    private double freshnessScore(LongTermMemory memory, long newest, long oldest) {
        if (newest <= oldest) {
            return 1.0d;
        }
        return (memory.getUpdatedAt().toEpochMilli() - oldest) / (double) (newest - oldest);
    }

    private double tokenSimilarity(String left, String right) {
        var leftTokens = normalizedTokens(left);
        var rightTokens = normalizedTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0d;
        }
        var matches = 0;
        for (var token : leftTokens) {
            if (rightTokens.contains(token)) {
                matches++;
            }
        }
        return matches / (double) Math.max(leftTokens.size(), rightTokens.size());
    }

    private Set<String> normalizedTokens(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        var tokens = new LinkedHashSet<String>();
        for (var token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private int estimateTokens(LongTermMemory memory) {
        var text = memory.getSummary() + "\n" + memory.getContent();
        var normalized = text.trim();
        if (normalized.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(normalized.length() / 24.0d));
    }

    private void addReason(List<String> reasons, boolean condition, String reason) {
        if (condition) {
            reasons.add(reason);
        }
    }

    private void validate(LongTermMemoryQuery query) {
        if (!StringUtils.hasText(query.rawQuery())) {
            throw new IllegalArgumentException("rawQuery must not be blank");
        }
        if (query.limit() != null && query.limit() <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (query.tokenBudget() != null && query.tokenBudget() <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
    }

    private void validateEmbedding(float[] embedding) {
        EmbeddingValidation.requireVector(
            "query",
            embeddingService.profile(),
            embedding,
            embeddingService.dimensions()
        );
    }

    private LongTermMemoryQuery normalize(LongTermMemoryQuery query) {
        return new LongTermMemoryQuery(
            normalizeStage(query.stageProfile()),
            query.rawQuery().trim(),
            normalizeNullable(query.systemName()),
            normalizeNullable(query.moduleName()),
            normalizeNullable(query.apiPath()),
            normalizeNullable(query.errorCode()),
            normalizeTags(query.tags()),
            query.scopeTypes() == null ? List.of() : List.copyOf(query.scopeTypes()),
            query.limit() == null ? DEFAULT_LIMIT : query.limit(),
            query.tokenBudget() == null ? DEFAULT_TOKEN_BUDGET : query.tokenBudget()
        );
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
            .filter(StringUtils::hasText)
            .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList();
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeStage(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "general";
    }

    private record RouteScore(
        double score,
        Map<String, Double> componentScores,
        List<String> reasons,
        Map<String, Object> sourceEvidence
    ) {

        private static RouteScore empty() {
            return new RouteScore(0.0d, Map.of(), List.of(), Map.of());
        }
    }

    private record FieldScore(
        String fieldName,
        String expected,
        double weight,
        boolean applicable,
        boolean matched
    ) {
    }

    private record ScoredRouteMemory(
        LongTermMemory memory,
        RouteScore score
    ) {
    }

    private record RouteHit(
        LongTermMemoryRetrievalHit hit,
        RetrievalRouteEvidence evidence
    ) {
    }

    private static final class MemoryRouteAccumulator {

        private LongTermMemoryRetrievalHit bestHit;
        private final List<LongTermMemoryRetrievalHit> hits = new ArrayList<>();
        private final List<RetrievalRouteEvidence> routeEvidence = new ArrayList<>();
        private final LinkedHashSet<String> matchReasons = new LinkedHashSet<>();

        private MemoryRouteAccumulator(LongTermMemoryRetrievalHit firstHit) {
            bestHit = firstHit;
        }

        private void add(RouteHit routeHit) {
            var hit = routeHit.hit();
            hits.add(hit);
            routeEvidence.add(routeHit.evidence());
            matchReasons.addAll(hit.matchReasons());
            matchReasons.add(routeHit.evidence().matchReason());
            if (hit.score() > bestHit.score()) {
                bestHit = hit;
            }
        }

        private LongTermMemoryRetrievalHit bestHit() {
            return bestHit;
        }

        private List<LongTermMemoryRetrievalHit> hits() {
            return hits;
        }

        private List<RetrievalRouteEvidence> routeEvidence() {
            return routeEvidence;
        }

        private LinkedHashSet<String> matchReasons() {
            return matchReasons;
        }
    }
}
