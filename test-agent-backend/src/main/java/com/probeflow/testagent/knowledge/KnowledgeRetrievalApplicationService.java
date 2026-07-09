package com.probeflow.testagent.knowledge;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.retrieval.QueryFilters;
import com.probeflow.testagent.retrieval.QueryIntent;
import com.probeflow.testagent.retrieval.QueryRewriteResult;
import com.probeflow.testagent.retrieval.QueryTargetCorpus;
import com.probeflow.testagent.retrieval.QueryVariant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeRetrievalApplicationService {

    private static final int DEFAULT_LIMIT = 8;
    private static final int DEFAULT_TOKEN_BUDGET = 1200;
    private static final int DEFAULT_ROUTE_LIMIT = 4;
    private static final double FUSION_RANK_CONSTANT = 60.0d;
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]{2,}\\b");
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "from", "that", "this", "into", "must", "have", "when",
        "only", "after", "before", "order", "payment", "guide", "notes", "route", "api"
    );

    private final KnowledgeChunkRepository chunks;
    private final KnowledgeDocumentRepository documents;
    private final EmbeddingService embeddingService;
    private final ApiSpecRepository apiSpecs;

    public KnowledgeRetrievalApplicationService(
        KnowledgeChunkRepository chunks,
        KnowledgeDocumentRepository documents,
        EmbeddingService embeddingService,
        ApiSpecRepository apiSpecs
    ) {
        this.chunks = chunks;
        this.documents = documents;
        this.embeddingService = embeddingService;
        this.apiSpecs = apiSpecs;
    }

    @Transactional(readOnly = true)
    public KnowledgeRetrievalResult retrieve(KnowledgeQuery query) {
        validate(query);
        var normalized = normalize(query);
        var queryEmbedding = embeddingService.embedQuery(normalized.rawQuery());
        validateEmbedding(queryEmbedding);
        var candidateLimit = Math.max(normalized.limit() * 4, 24);

        var candidates = chunks.findPgvectorCandidates(
            normalized.systemName(),
            normalized.moduleName(),
            normalized.bizEntity(),
            normalized.documentType(),
            normalized.apiPath(),
            normalized.httpMethod(),
            normalized.applicableStage(),
            normalized.tags(),
            queryEmbedding,
            candidateLimit
        );
        if (candidates.isEmpty()) {
            return emptyResult(normalized.rawQuery());
        }

        var documentsById = loadDocuments(candidates);
        var filtered = candidates.stream()
            .map(candidate -> toHit(candidate, documentsById.get(candidate.chunk().getDocumentId())))
            .filter(Objects::nonNull)
            .toList();
        if (filtered.isEmpty()) {
            return emptyResult(normalized.rawQuery());
        }

        var ranked = rankCandidates(filtered, normalized, queryEmbedding);
        var deduplicated = deduplicate(ranked);
        var constrained = applyLimitAndTokenBudget(deduplicated, normalized.limit(), normalized.tokenBudget());
        var lowConfidence = constrained.isEmpty() || constrained.getFirst().lowConfidence();
        var coverage = constrained.isEmpty() ? 0.0d : (lowConfidence ? 0.4d : 1.0d);
        var context = assembleKnowledgeContext(constrained, coverage, lowConfidence);
        return new KnowledgeRetrievalResult(
            normalized.rawQuery(),
            constrained,
            context,
            coverage,
            filtered.size(),
            constrained.stream().mapToInt(KnowledgeRetrievalHit::tokenCount).sum(),
            lowConfidence
        );
    }

    @Transactional(readOnly = true)
    public KnowledgeRetrievalResult retrieve(KnowledgeQuery query, QueryRewriteResult rewriteResult) {
        validate(query);
        var normalized = normalize(query);
        var diagnostics = new ArrayList<String>();
        if (rewriteResult != null) {
            diagnostics.addAll(rewriteResult.diagnostics());
        }

        var routeLimit = perRouteLimit(normalized.limit());
        var variants = applicableKnowledgeVariants(normalized, rewriteResult, diagnostics);
        var originalVariants = originalVariants(normalized, variants);
        var routeVariants = routeVariants(normalized, variants);

        var routeCandidates = new ArrayList<RouteCandidate>();
        routeCandidates.addAll(runKnowledgeRoute(
            "original-semantic",
            diagnostics,
            () -> semanticRoute("original-semantic", normalized, originalVariants, routeLimit)
        ));
        routeCandidates.addAll(runKnowledgeRoute(
            "rewritten-semantic",
            diagnostics,
            () -> semanticRoute("rewritten-semantic", normalized, rewrittenVariants(routeVariants), routeLimit)
        ));
        routeCandidates.addAll(runKnowledgeRoute(
            "metadata-exact",
            diagnostics,
            () -> metadataRoute(normalized, routeVariants, routeLimit)
        ));
        routeCandidates.addAll(runKnowledgeRoute(
            "lexical-tag",
            diagnostics,
            () -> lexicalRoute(normalized, routeVariants, routeLimit)
        ));
        routeCandidates.addAll(runKnowledgeRoute(
            "document-type",
            diagnostics,
            () -> documentTypeRoute(normalized, routeVariants, routeLimit)
        ));

        if (routeCandidates.isEmpty()) {
            return new KnowledgeRetrievalResult(
                normalized.rawQuery(),
                List.of(),
                KnowledgeContext.empty(true, true),
                0.0d,
                0,
                0,
                true,
                diagnostics
            );
        }

        var merged = mergeRouteCandidates(routeCandidates, diagnostics, normalized.limit());
        var constrained = applyLimitAndTokenBudget(merged, normalized.limit(), normalized.tokenBudget());
        var lowConfidence = constrained.isEmpty() || constrained.getFirst().lowConfidence();
        var coverage = constrained.isEmpty() ? 0.0d : (lowConfidence ? 0.4d : 1.0d);
        var context = assembleKnowledgeContext(constrained, coverage, lowConfidence);
        return new KnowledgeRetrievalResult(
            normalized.rawQuery(),
            constrained,
            context,
            coverage,
            routeCandidates.size(),
            constrained.stream().mapToInt(KnowledgeRetrievalHit::tokenCount).sum(),
            lowConfidence,
            diagnostics
        );
    }

    @Transactional
    public KnowledgeRetrievalResult retrieveForApiSpec(String apiSpecId, KnowledgeQuery query) {
        requireNonBlank(apiSpecId, "apiSpecId must not be blank");
        var apiSpec = apiSpecs.findById(apiSpecId)
            .orElseThrow(() -> new IllegalArgumentException("ApiSpec not found: " + apiSpecId));

        var scopedQuery = new KnowledgeQuery(
            query.rawQuery(),
            firstNonBlank(query.systemName(), apiSpec.getSystemName()),
            firstNonBlank(query.moduleName(), apiSpec.getModuleName()),
            firstNonBlank(query.apiPath(), apiSpec.getPath()),
            query.httpMethod() != null ? query.httpMethod() : httpMethodValue(apiSpec.getHttpMethod()),
            query.bizEntity(),
            query.documentType(),
            query.applicableStage(),
            query.tags(),
            query.limit(),
            query.tokenBudget()
        );

        var result = retrieve(scopedQuery);
        apiSpec.setKnowledgeContextReady(hasUsefulContext(result.knowledgeContext(), result.coverage()));
        apiSpecs.save(apiSpec);
        return result;
    }

    private void validate(KnowledgeQuery query) {
        requireNonBlank(query.rawQuery(), "rawQuery must not be blank");
        if (query.limit() != null && query.limit() <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (query.tokenBudget() != null && query.tokenBudget() <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
    }

    private KnowledgeQuery normalize(KnowledgeQuery query) {
        return new KnowledgeQuery(
            query.rawQuery().trim(),
            normalizeNullable(query.systemName()),
            normalizeNullable(query.moduleName()),
            normalizeNullable(query.apiPath()),
            normalizeUpper(query.httpMethod()),
            normalizeNullable(query.bizEntity()),
            query.documentType(),
            normalizeNullable(query.applicableStage()),
            normalizeTags(query.tags()),
            query.limit() == null ? DEFAULT_LIMIT : query.limit(),
            query.tokenBudget() == null ? DEFAULT_TOKEN_BUDGET : query.tokenBudget()
        );
    }

    private int perRouteLimit(int finalLimit) {
        return Math.max(1, Math.min(DEFAULT_ROUTE_LIMIT, finalLimit));
    }

    private int fusionCandidateLimit(int finalLimit) {
        return Math.max(finalLimit * 4, 24);
    }

    private List<RouteCandidate> runKnowledgeRoute(
        String routeName,
        List<String> diagnostics,
        Supplier<List<RouteCandidate>> route
    ) {
        try {
            var candidates = route.get();
            if (candidates.isEmpty()) {
                diagnostics.add("knowledge-route-empty:" + routeName);
            }
            return candidates;
        } catch (RuntimeException exception) {
            diagnostics.add("knowledge-route-failed:" + routeName);
            return List.of();
        }
    }

    private List<QueryVariant> applicableKnowledgeVariants(
        KnowledgeQuery query,
        QueryRewriteResult rewriteResult,
        List<String> diagnostics
    ) {
        if (rewriteResult == null || rewriteResult.variants().isEmpty()) {
            diagnostics.add("knowledge-query-variants-fallback:original-query");
            return List.of(syntheticVariant(query));
        }

        var variants = new ArrayList<QueryVariant>();
        for (var variant : rewriteResult.variants()) {
            if (variant == null || variant.targetCorpus() == QueryTargetCorpus.MEMORY || variant.targetCorpus() == QueryTargetCorpus.GRAPH) {
                continue;
            }
            if (filtersConflict(query, variant.filters())) {
                diagnostics.add("knowledge-query-variant-skipped:filter-conflict");
                continue;
            }
            variants.add(variant);
        }
        if (variants.isEmpty()) {
            diagnostics.add("knowledge-query-variants-fallback:original-query");
            variants.add(syntheticVariant(query));
        }
        return List.copyOf(variants);
    }

    private QueryVariant syntheticVariant(KnowledgeQuery query) {
        return new QueryVariant(
            "qv-knowledge-original",
            query.rawQuery(),
            QueryIntent.RAW_TASK,
            QueryTargetCorpus.KNOWLEDGE,
            query.applicableStage() == null ? "default" : query.applicableStage(),
            new QueryFilters(
                query.systemName(),
                query.moduleName(),
                query.apiPath(),
                query.httpMethod(),
                query.bizEntity(),
                null,
                null,
                null,
                null,
                null,
                null,
                query.tags(),
                query.documentType() == null ? List.of() : List.of(query.documentType()),
                List.of()
            ),
            100,
            "Original query used because no knowledge query variant was supplied."
        );
    }

    private boolean filtersConflict(KnowledgeQuery query, QueryFilters filters) {
        if (filters == null) {
            return false;
        }
        return conflicts(query.systemName(), filters.systemName())
            || conflicts(query.moduleName(), filters.moduleName())
            || conflicts(query.apiPath(), filters.apiPath())
            || conflicts(query.httpMethod(), normalizeUpper(filters.httpMethod()))
            || conflicts(query.bizEntity(), filters.businessEntity())
            || (query.documentType() != null
                && !filters.documentTypes().isEmpty()
                && !filters.documentTypes().contains(query.documentType()));
    }

    private boolean conflicts(String left, String right) {
        return left != null && right != null && !left.equals(right);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<QueryVariant> originalVariants(KnowledgeQuery query, List<QueryVariant> variants) {
        var originals = variants.stream()
            .filter(variant -> variant.intent() == QueryIntent.RAW_TASK)
            .toList();
        return originals.isEmpty() ? List.of(syntheticVariant(query)) : originals;
    }

    private List<QueryVariant> routeVariants(KnowledgeQuery query, List<QueryVariant> variants) {
        return variants.isEmpty() ? List.of(syntheticVariant(query)) : variants;
    }

    private List<QueryVariant> rewrittenVariants(List<QueryVariant> variants) {
        return variants.stream()
            .filter(variant -> variant.intent() != QueryIntent.RAW_TASK)
            .filter(variant -> hasText(variant.queryText()))
            .toList();
    }

    private List<RouteCandidate> semanticRoute(
        String routeName,
        KnowledgeQuery baseQuery,
        List<QueryVariant> variants,
        int routeLimit
    ) {
        if (variants.isEmpty()) {
            return List.of();
        }
        var scored = new ArrayList<ScoredRouteHit>();
        for (var variant : variants) {
            for (var documentType : semanticDocumentTypes(baseQuery, variant)) {
                var routeQuery = routeQuery(baseQuery, variant, documentType, variant.queryText());
                var queryEmbedding = embeddingService.embedQuery(routeQuery.query().rawQuery());
                validateEmbedding(queryEmbedding);
                var candidates = chunks.findPgvectorCandidates(
                    routeQuery.query().systemName(),
                    routeQuery.query().moduleName(),
                    routeQuery.query().bizEntity(),
                    routeQuery.query().documentType(),
                    routeQuery.query().apiPath(),
                    routeQuery.query().httpMethod(),
                    routeQuery.query().applicableStage(),
                    routeQuery.query().tags(),
                    queryEmbedding,
                    Math.max(routeLimit * 4, 8)
                );
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                var documentsById = loadDocuments(candidates);
                var routeHits = candidates.stream()
                    .map(candidate -> toHit(candidate, documentsById.get(candidate.chunk().getDocumentId())))
                    .filter(Objects::nonNull)
                    .toList();
                var ranked = rankCandidates(routeHits, routeQuery.query(), queryEmbedding);
                for (var hit : ranked) {
                    var score = semanticRouteScore(hit);
                    scored.add(new ScoredRouteHit(
                        hit,
                        variant,
                        score,
                        routeName + " matched query variant " + variant.deterministicId(),
                        hit.matchReasons(),
                        Map.of("semantic", score)
                    ));
                }
            }
        }
        return topRouteCandidates(routeName, scored, routeLimit);
    }

    private List<DocumentType> semanticDocumentTypes(KnowledgeQuery baseQuery, QueryVariant variant) {
        if (baseQuery.documentType() != null) {
            return List.of(baseQuery.documentType());
        }
        if (variant.filters() != null && !variant.filters().documentTypes().isEmpty()) {
            return variant.filters().documentTypes();
        }
        return Collections.singletonList(null);
    }

    private double semanticRouteScore(KnowledgeRetrievalHit hit) {
        var distance = hit.metadata().get("vectorDistance");
        if (distance instanceof Number number) {
            return Math.max(0.0d, 1.0d - number.doubleValue());
        }
        return Math.max(0.0d, hit.score());
    }

    private List<RouteCandidate> metadataRoute(
        KnowledgeQuery baseQuery,
        List<QueryVariant> variants,
        int routeLimit
    ) {
        var scored = new ArrayList<ScoredRouteHit>();
        for (var variant : variants) {
            for (var documentType : exactDocumentTypes(baseQuery, variant)) {
                var routeQuery = routeQuery(baseQuery, variant, documentType, variant.queryText());
                for (var hit : activeRouteHits(routeQuery, "metadata-exact")) {
                    var evidence = metadataEvidence(hit, routeQuery.query());
                    if (evidence.score() > 0.0d) {
                        scored.add(new ScoredRouteHit(
                            hit,
                            variant,
                            evidence.score(),
                            "metadata exact matched " + String.join(",", evidence.reasons()),
                            evidence.reasons(),
                            Map.of("metadata", evidence.score())
                        ));
                    }
                }
            }
        }
        return topRouteCandidates("metadata-exact", scored, routeLimit);
    }

    private List<RouteCandidate> lexicalRoute(
        KnowledgeQuery baseQuery,
        List<QueryVariant> variants,
        int routeLimit
    ) {
        var scored = new ArrayList<ScoredRouteHit>();
        for (var variant : variants) {
            for (var documentType : exactDocumentTypes(baseQuery, variant)) {
                var routeQuery = routeQuery(baseQuery, variant, documentType, variant.queryText());
                for (var hit : activeRouteHits(routeQuery, "lexical-tag")) {
                    var evidence = lexicalEvidence(hit, routeQuery);
                    if (evidence.score() > 0.0d) {
                        scored.add(new ScoredRouteHit(
                            hit,
                            variant,
                            evidence.score(),
                            "lexical and tag matched " + String.join(",", evidence.reasons()),
                            evidence.reasons(),
                            Map.of("lexical", evidence.score())
                        ));
                    }
                }
            }
        }
        return topRouteCandidates("lexical-tag", scored, routeLimit);
    }

    private List<RouteCandidate> documentTypeRoute(
        KnowledgeQuery baseQuery,
        List<QueryVariant> variants,
        int routeLimit
    ) {
        var scored = new ArrayList<ScoredRouteHit>();
        for (var variant : variants) {
            var preferredTypes = documentTypesForStage(baseQuery, variant);
            for (var index = 0; index < preferredTypes.size(); index++) {
                var documentType = preferredTypes.get(index);
                var routeQuery = routeQuery(baseQuery, variant, documentType, variant.queryText());
                for (var hit : activeRouteHits(routeQuery, "document-type")) {
                    var stageFit = stageFitScore(hit, routeQuery.query());
                    var keyword = keywordEvidence(hit, routeQuery.query()).score();
                    var score = Math.max(0.05d, 1.0d - (index * 0.08d)) + (stageFit * 0.10d) + (keyword * 0.05d);
                    var reasons = new ArrayList<String>();
                    reasons.add("document-type");
                    addReasonWhen(reasons, stageFit >= 1.0d, "stage-fit");
                    scored.add(new ScoredRouteHit(
                        hit,
                        variant,
                        Math.min(1.0d, score),
                        "document type preference matched " + documentType.name(),
                        reasons,
                        Map.of("stageFit", stageFit)
                    ));
                }
            }
        }
        return topRouteCandidates("document-type", scored, routeLimit);
    }

    private List<DocumentType> exactDocumentTypes(KnowledgeQuery baseQuery, QueryVariant variant) {
        if (baseQuery.documentType() != null) {
            return List.of(baseQuery.documentType());
        }
        if (variant.filters() != null && !variant.filters().documentTypes().isEmpty()) {
            return variant.filters().documentTypes();
        }
        return Collections.singletonList(null);
    }

    private List<DocumentType> documentTypesForStage(KnowledgeQuery baseQuery, QueryVariant variant) {
        if (baseQuery.documentType() != null) {
            return List.of(baseQuery.documentType());
        }
        if (variant.filters() != null && !variant.filters().documentTypes().isEmpty()) {
            return variant.filters().documentTypes();
        }
        var stage = firstNonBlank(baseQuery.applicableStage(), variant.stageProfile());
        if (stage == null) {
            return List.of(DocumentType.API_NOTE, DocumentType.BUSINESS_FLOW, DocumentType.TEST_SPEC, DocumentType.DOMAIN_RULE);
        }
        return switch (stage) {
            case "api_analysis" -> List.of(DocumentType.API_NOTE, DocumentType.BUSINESS_FLOW, DocumentType.DOMAIN_RULE);
            case "case_generation" -> List.of(DocumentType.TEST_SPEC, DocumentType.BUSINESS_FLOW, DocumentType.DOMAIN_RULE);
            case "failure_analysis" -> List.of(DocumentType.ERROR_CODE_GUIDE, DocumentType.INCIDENT_POSTMORTEM, DocumentType.ENV_GUIDE);
            case "suite_generation", "suite_recovery" -> List.of(DocumentType.BUSINESS_FLOW, DocumentType.TEST_SPEC, DocumentType.DOMAIN_RULE);
            default -> List.of(DocumentType.DOMAIN_RULE, DocumentType.API_NOTE, DocumentType.TEST_SPEC);
        };
    }

    private RouteQuery routeQuery(
        KnowledgeQuery baseQuery,
        QueryVariant variant,
        DocumentType documentType,
        String queryText
    ) {
        var filters = variant.filters() == null ? emptyFilters() : variant.filters();
        var tags = mergeTags(baseQuery.tags(), filters.tags());
        var query = new KnowledgeQuery(
            firstNonBlank(queryText, baseQuery.rawQuery()),
            firstNonBlank(filters.systemName(), baseQuery.systemName()),
            firstNonBlank(filters.moduleName(), baseQuery.moduleName()),
            firstNonBlank(filters.apiPath(), baseQuery.apiPath()),
            normalizeUpper(firstNonBlank(filters.httpMethod(), baseQuery.httpMethod())),
            firstNonBlank(filters.businessEntity(), baseQuery.bizEntity()),
            documentType,
            firstNonBlank(baseQuery.applicableStage(), variant.stageProfile()),
            tags,
            baseQuery.limit(),
            baseQuery.tokenBudget()
        );
        return new RouteQuery(variant, query, filters);
    }

    private QueryFilters emptyFilters() {
        return new QueryFilters(null, null, null, null, null, null, null, null, null, null, null, List.of(), List.of(), List.of());
    }

    private List<String> mergeTags(List<String> left, List<String> right) {
        var tags = new LinkedHashSet<String>();
        tags.addAll(left == null ? List.of() : left);
        tags.addAll(right == null ? List.of() : right);
        return normalizeTags(List.copyOf(tags));
    }

    private List<KnowledgeRetrievalHit> activeRouteHits(RouteQuery routeQuery, String channel) {
        var query = routeQuery.query();
        var routeChunks = chunks.findActiveLatestChunks(
            query.systemName(),
            query.moduleName(),
            query.bizEntity(),
            query.documentType()
        );
        if (routeChunks == null || routeChunks.isEmpty()) {
            return List.of();
        }
        var documentsById = loadDocumentsForChunks(routeChunks);
        return routeChunks.stream()
            .filter(chunk -> matchesApiPath(chunk, query.apiPath()))
            .filter(chunk -> matchesHttpMethod(chunk, query.httpMethod()))
            .filter(chunk -> query.applicableStage() == null || chunk.getApplicableStages().contains(query.applicableStage()))
            .filter(chunk -> query.tags().isEmpty() || chunk.getTags().containsAll(query.tags()))
            .map(chunk -> toRouteHit(chunk, documentsById.get(chunk.getDocumentId()), channel))
            .filter(Objects::nonNull)
            .toList();
    }

    private Map<String, KnowledgeDocument> loadDocumentsForChunks(List<KnowledgeChunk> routeChunks) {
        var documentIds = routeChunks.stream()
            .map(KnowledgeChunk::getDocumentId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var documentsById = new LinkedHashMap<String, KnowledgeDocument>();
        for (var document : documents.findAllById(documentIds)) {
            documentsById.put(document.getDocumentId(), document);
        }
        return documentsById;
    }

    private KnowledgeRetrievalHit toRouteHit(KnowledgeChunk chunk, KnowledgeDocument document, String channel) {
        if (document == null) {
            return null;
        }
        var metadata = new LinkedHashMap<>(
            EmbeddingProfileMetadata.withReindexStatus(chunk.getMetadata(), embeddingService.profile())
        );
        metadata.put("retrievalChannel", channel);
        return new KnowledgeRetrievalHit(
            chunk.getChunkId(),
            chunk.getDocumentId(),
            chunk.getDocumentRevisionId(),
            chunk.getChunkTitle(),
            chunk.getChunkContent(),
            document.getSourceRef(),
            document.getDocType(),
            document.getAuthority(),
            document.getSystemName(),
            document.getModuleName(),
            document.getBizEntity(),
            List.copyOf(chunk.getTags()),
            List.copyOf(chunk.getApplicableStages()),
            metadata,
            chunk.getTokenCount(),
            0.0d,
            Map.of(),
            List.of(),
            true
        );
    }

    private RouteScoring metadataEvidence(KnowledgeRetrievalHit hit, KnowledgeQuery query) {
        var reasons = new ArrayList<String>();
        var possible = 0;
        var matched = 0;
        possible += addExactReason(reasons, query.systemName(), hit.systemName(), "system");
        matched = reasons.size();
        var beforeModule = reasons.size();
        possible += addExactReason(reasons, query.moduleName(), hit.moduleName(), "module");
        matched += reasons.size() - beforeModule;
        var beforeEntity = reasons.size();
        possible += addExactReason(reasons, query.bizEntity(), hit.bizEntity(), "business-entity");
        matched += reasons.size() - beforeEntity;
        if (query.documentType() != null) {
            possible++;
            if (query.documentType() == hit.documentType()) {
                matched++;
                reasons.add("document-type");
            }
        }
        if (query.apiPath() != null) {
            possible++;
            if (metadataList(hit.metadata(), "apiPathHints").contains(query.apiPath())) {
                matched++;
                reasons.add("api-path");
            }
        }
        if (query.httpMethod() != null) {
            possible++;
            if (matchesHttpMethodHint(hit, query)) {
                matched++;
                reasons.add("http-method");
            }
        }
        if (query.applicableStage() != null) {
            possible++;
            if (hit.applicableStages().contains(query.applicableStage())) {
                matched++;
                reasons.add("applicable-stage");
            }
        }
        return new RouteScoring(possible == 0 ? 0.0d : matched / (double) possible, List.copyOf(reasons));
    }

    private int addExactReason(List<String> reasons, String expected, String actual, String reason) {
        if (expected == null) {
            return 0;
        }
        if (expected.equals(actual)) {
            reasons.add(reason);
        }
        return 1;
    }

    private RouteScoring lexicalEvidence(KnowledgeRetrievalHit hit, RouteQuery routeQuery) {
        var query = routeQuery.query();
        var filters = routeQuery.filters();
        var reasons = new LinkedHashSet<String>();
        double score = 0.0d;
        double possible = 0.0d;

        var keyword = keywordEvidence(hit, query);
        possible += 1.0d;
        score += keyword.score();
        reasons.addAll(keyword.reasons());

        if (filters.errorCode() != null) {
            possible += 1.0d;
            if (metadataList(hit.metadata(), "errorCodeHints").contains(filters.errorCode())) {
                score += 1.0d;
                reasons.add("error-code");
            }
        }

        var queryTerms = normalizedTerms(joinNonBlank(query.rawQuery(), filters.errorCode(), String.join(" ", query.tags())));
        var frontmatterTerms = new ArrayList<String>();
        frontmatterTerms.addAll(normalizedTerms(String.join(" ", metadataList(hit.metadata(), "frontmatterKeys"))));
        frontmatterTerms.addAll(normalizedTerms(String.join(" ", metadataList(hit.metadata(), "keywordTags"))));
        frontmatterTerms.addAll(normalizedTerms(String.join(" ", metadataList(hit.metadata(), "headerPath"))));
        possible += 1.0d;
        var frontmatterMatch = overlapRatio(queryTerms, frontmatterTerms);
        if (frontmatterMatch > 0.0d) {
            score += frontmatterMatch;
            reasons.add("frontmatter");
        }

        possible += 1.0d;
        var tagMatch = overlapRatio(queryTerms, lowercased(hit.tags()));
        if (!query.tags().isEmpty() && hit.tags().containsAll(query.tags())) {
            tagMatch = Math.max(tagMatch, 1.0d);
        }
        if (tagMatch > 0.0d) {
            score += tagMatch;
            reasons.add("tag");
        }

        return new RouteScoring(possible == 0.0d ? 0.0d : score / possible, List.copyOf(reasons));
    }

    private List<RouteCandidate> topRouteCandidates(
        String routeName,
        List<ScoredRouteHit> scored,
        int routeLimit
    ) {
        var sorted = scored.stream()
            .sorted(Comparator
                .comparingDouble(ScoredRouteHit::score).reversed()
                .thenComparing(hit -> hit.hit().chunkTitle(), Comparator.nullsLast(String::compareTo))
                .thenComparing(hit -> hit.hit().chunkId()))
            .limit(routeLimit)
            .toList();
        var candidates = new ArrayList<RouteCandidate>();
        for (var index = 0; index < sorted.size(); index++) {
            var scoredHit = sorted.get(index);
            var evidence = new KnowledgeRouteEvidence(
                routeName,
                scoredHit.variant().deterministicId(),
                scoredHit.variant().intent() == null ? null : scoredHit.variant().intent().name(),
                index + 1,
                scoredHit.score(),
                scoredHit.matchReason()
            );
            var hit = withRouteEvidence(
                scoredHit.hit(),
                evidence,
                scoredHit.score(),
                scoredHit.componentScores(),
                scoredHit.reasons()
            );
            candidates.add(new RouteCandidate(hit, evidence));
        }
        return List.copyOf(candidates);
    }

    private KnowledgeRetrievalHit withRouteEvidence(
        KnowledgeRetrievalHit hit,
        KnowledgeRouteEvidence evidence,
        double score,
        Map<String, Double> componentScores,
        List<String> reasons
    ) {
        var metadata = new LinkedHashMap<>(hit.metadata());
        metadata.put("preFusionRoute", evidence.routeName());
        metadata.put("preFusionRank", evidence.routeRank());
        metadata.put("preFusionScore", evidence.routeScore());
        metadata.putIfAbsent("candidateRank", evidence.routeRank());
        return new KnowledgeRetrievalHit(
            hit.chunkId(),
            hit.documentId(),
            hit.documentRevisionId(),
            hit.chunkTitle(),
            hit.chunkContent(),
            hit.sourceRef(),
            hit.documentType(),
            hit.authority(),
            hit.systemName(),
            hit.moduleName(),
            hit.bizEntity(),
            hit.tags(),
            hit.applicableStages(),
            metadata,
            hit.tokenCount(),
            score,
            componentScores,
            reasons,
            score < 0.30d,
            List.of(evidence)
        );
    }

    private List<KnowledgeRetrievalHit> mergeRouteCandidates(
        List<RouteCandidate> routeCandidates,
        List<String> diagnostics,
        int finalLimit
    ) {
        var mergedByChunk = new LinkedHashMap<String, MergedRouteHit>();
        for (var candidate : routeCandidates) {
            var candidateForFusion = candidate;
            if (candidate.evidence() == null) {
                diagnostics.add("knowledge-fusion-warning:missing-route-evidence");
                candidateForFusion = new RouteCandidate(candidate.hit(), fallbackRouteEvidence(candidate.hit()));
            }
            var chunkId = candidateForFusion.hit().chunkId();
            var firstHit = candidateForFusion.hit();
            mergedByChunk.computeIfAbsent(chunkId, ignored -> new MergedRouteHit(firstHit))
                .add(candidateForFusion);
        }
        var duplicateRoutes = routeCandidates.size() - mergedByChunk.size();
        if (duplicateRoutes > 0) {
            diagnostics.add("knowledge-fusion-deduplicated-routes:" + duplicateRoutes);
        }
        var fusionCandidateLimit = fusionCandidateLimit(finalLimit);
        var fused = mergedByChunk.values().stream()
            .map(MergedRouteHit::toHit)
            .sorted(Comparator
                .comparingDouble(KnowledgeRetrievalHit::score).reversed()
                .thenComparing((KnowledgeRetrievalHit hit) -> hit.routeEvidence().size(), Comparator.reverseOrder())
                .thenComparing(KnowledgeRetrievalHit::chunkTitle, Comparator.nullsLast(String::compareTo))
                .thenComparing(KnowledgeRetrievalHit::chunkId))
            .toList();
        if (fused.size() > fusionCandidateLimit) {
            diagnostics.add("knowledge-fusion-candidate-limit-applied:" + fusionCandidateLimit);
        }
        return fused.stream()
            .limit(fusionCandidateLimit)
            .toList();
    }

    private KnowledgeRouteEvidence fallbackRouteEvidence(KnowledgeRetrievalHit hit) {
        var metadataRank = hit.metadata().get("candidateRank");
        var rank = metadataRank instanceof Number number ? Math.max(1, number.intValue()) : 1;
        return new KnowledgeRouteEvidence(
            "unknown-route",
            "qv-unknown",
            rank,
            Math.max(0.0d, hit.score()),
            "missing route evidence fallback"
        );
    }

    private boolean matchesApiPath(KnowledgeChunk chunk, String apiPath) {
        return apiPath == null || metadataList(chunk, "apiPathHints").contains(apiPath);
    }

    private boolean matchesHttpMethod(KnowledgeChunk chunk, String httpMethod) {
        if (httpMethod == null) {
            return true;
        }
        return metadataList(chunk, "httpMethodHints").stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(httpMethod::equals);
    }

    private Map<String, KnowledgeDocument> loadDocuments(List<KnowledgeVectorCandidate> candidates) {
        var documentIds = candidates.stream()
            .map(candidate -> candidate.chunk().getDocumentId())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var documentsById = new LinkedHashMap<String, KnowledgeDocument>();
        for (var document : documents.findAllById(documentIds)) {
            documentsById.put(document.getDocumentId(), document);
        }
        return documentsById;
    }

    private KnowledgeRetrievalHit toHit(KnowledgeVectorCandidate candidate, KnowledgeDocument document) {
        if (document == null) {
            return null;
        }
        var chunk = candidate.chunk();
        var metadata = new LinkedHashMap<>(
            EmbeddingProfileMetadata.withReindexStatus(chunk.getMetadata(), embeddingService.profile())
        );
        metadata.put("retrievalChannel", "pgvector");
        metadata.put("vectorDistance", candidate.vectorDistance());
        metadata.put("candidateRank", candidate.candidateRank());
        return new KnowledgeRetrievalHit(
            chunk.getChunkId(),
            chunk.getDocumentId(),
            chunk.getDocumentRevisionId(),
            chunk.getChunkTitle(),
            chunk.getChunkContent(),
            document.getSourceRef(),
            document.getDocType(),
            document.getAuthority(),
            document.getSystemName(),
            document.getModuleName(),
            document.getBizEntity(),
            List.copyOf(chunk.getTags()),
            List.copyOf(chunk.getApplicableStages()),
            metadata,
            chunk.getTokenCount(),
            0.0d,
            Map.of(),
            List.of(),
            true
        );
    }

    private List<KnowledgeRetrievalHit> rankCandidates(
        List<KnowledgeRetrievalHit> candidates,
        KnowledgeQuery query,
        float[] queryEmbedding
    ) {
        var newestTimestamp = candidates.stream()
            .mapToLong(hit -> documents.findById(hit.documentId()).map(document -> document.getUpdatedAt().toEpochMilli()).orElse(0L))
            .max()
            .orElse(0L);
        var oldestTimestamp = candidates.stream()
            .mapToLong(hit -> documents.findById(hit.documentId()).map(document -> document.getUpdatedAt().toEpochMilli()).orElse(0L))
            .min()
            .orElse(newestTimestamp);

        return candidates.stream()
            .map(hit -> rerankHit(hit, query, queryEmbedding, newestTimestamp, oldestTimestamp))
            .sorted(Comparator
                .comparingDouble(KnowledgeRetrievalHit::score).reversed()
                .thenComparing(KnowledgeRetrievalHit::chunkTitle, Comparator.nullsLast(String::compareTo))
                .thenComparing(KnowledgeRetrievalHit::chunkId))
            .toList();
    }

    private KnowledgeRetrievalHit rerankHit(
        KnowledgeRetrievalHit hit,
        KnowledgeQuery query,
        float[] queryEmbedding,
        long newestTimestamp,
        long oldestTimestamp
    ) {
        var keyword = keywordEvidence(hit, query);
        var profileCompatible = EmbeddingProfileMetadata.isCompatible(hit.metadata(), embeddingService.profile());
        var vector = profileCompatible
            ? semanticScore(hit, queryEmbedding)
            : 0.0d;
        var structure = structureScore(hit, query);
        var authority = authorityScore(hit.authority());
        var freshness = freshnessScore(hit.documentId(), newestTimestamp, oldestTimestamp);
        var stageFit = stageFitScore(hit, query);

        var weightedScores = new LinkedHashMap<String, Double>();
        weightedScores.put("keyword", keyword.score() * Weights.KEYWORD);
        weightedScores.put("vector", vector * Weights.VECTOR);
        weightedScores.put("structure", structure * Weights.STRUCTURE);
        weightedScores.put("authority", authority * Weights.AUTHORITY);
        weightedScores.put("freshness", freshness * Weights.FRESHNESS);
        weightedScores.put("stageFit", stageFit * Weights.STAGE_FIT);

        var finalScore = weightedScores.values().stream().mapToDouble(Double::doubleValue).sum();
        var reasons = new ArrayList<>(keyword.reasons());
        addReasonWhen(reasons, structure > 0.35d, "structure");
        addReasonWhen(reasons, authority >= 1.0d, "high-authority");
        addReasonWhen(reasons, stageFit >= 1.0d, "stage-fit");
        addReasonWhen(reasons, vector >= 0.7d, "semantic-match");
        addReasonWhen(reasons, !profileCompatible, "reindex-required");

        return new KnowledgeRetrievalHit(
            hit.chunkId(),
            hit.documentId(),
            hit.documentRevisionId(),
            hit.chunkTitle(),
            hit.chunkContent(),
            hit.sourceRef(),
            hit.documentType(),
            hit.authority(),
            hit.systemName(),
            hit.moduleName(),
            hit.bizEntity(),
            hit.tags(),
            hit.applicableStages(),
            hit.metadata(),
            hit.tokenCount(),
            finalScore,
            weightedScores,
            reasons,
            !profileCompatible || finalScore < 0.30d || (keyword.score() < 0.20d && vector < 0.45d),
            hit.routeEvidence()
        );
    }

    private List<KnowledgeRetrievalHit> applyLimitAndTokenBudget(
        List<KnowledgeRetrievalHit> hits,
        int limit,
        int tokenBudget
    ) {
        var constrained = new ArrayList<KnowledgeRetrievalHit>();
        var tokenCount = 0;
        for (var hit : hits) {
            if (constrained.size() >= limit) {
                break;
            }
            if (!constrained.isEmpty() && tokenCount + hit.tokenCount() > tokenBudget) {
                break;
            }
            constrained.add(hit);
            tokenCount += hit.tokenCount();
        }
        return constrained;
    }

    private List<KnowledgeRetrievalHit> deduplicate(List<KnowledgeRetrievalHit> rankedHits) {
        var seen = new HashSet<String>();
        var deduplicated = new ArrayList<KnowledgeRetrievalHit>();
        for (var hit : rankedHits) {
            var key = normalizeContent(hit.chunkTitle()) + "|" + normalizeContent(hit.chunkContent());
            if (seen.add(key)) {
                deduplicated.add(hit);
            }
        }
        return deduplicated;
    }

    private KeywordEvidence keywordEvidence(KnowledgeRetrievalHit hit, KnowledgeQuery query) {
        var rawQueryUpper = query.rawQuery().toUpperCase(Locale.ROOT);
        var queryTerms = normalizedTerms(query.rawQuery());
        var reasons = new LinkedHashSet<String>();
        double score = 0.0d;
        double maxScore = 0.0d;

        maxScore += 1.0d;
        if (matchesApiPathHint(hit, query) || containsAny(rawQueryUpper, metadataUpper(hit, "apiPathHints"))) {
            score += 1.0d;
            reasons.add("api-path");
        }

        maxScore += 1.0d;
        if (matchesHttpMethodHint(hit, query) || containsAny(rawQueryUpper, metadataUpper(hit, "httpMethodHints"))) {
            score += 1.0d;
            reasons.add("http-method");
        }

        maxScore += 1.0d;
        if (containsAny(rawQueryUpper, metadataUpper(hit, "errorCodeHints"))) {
            score += 1.0d;
            reasons.add("error-code");
        }

        maxScore += 1.0d;
        var fieldMatch = overlapRatio(queryTerms, identifiers(hit.chunkContent()));
        if (fieldMatch > 0.0d) {
            score += fieldMatch;
            reasons.add("field-name");
        }

        maxScore += 1.0d;
        var tagMatch = overlapRatio(queryTerms, lowercased(hit.tags()));
        if (!query.tags().isEmpty() && hit.tags().containsAll(query.tags())) {
            tagMatch = Math.max(tagMatch, 1.0d);
        }
        if (tagMatch > 0.0d) {
            score += tagMatch;
            reasons.add("tag");
        }

        maxScore += 1.0d;
        var headerTerms = new ArrayList<String>(normalizedTerms(hit.chunkTitle()));
        headerTerms.addAll(normalizedTerms(String.join(" ", metadataList(hit.metadata(), "headerPath"))));
        var headerMatch = overlapRatio(queryTerms, headerTerms);
        if (headerMatch > 0.0d) {
            score += headerMatch;
            reasons.add("title-heading");
        }

        return new KeywordEvidence(maxScore == 0.0d ? 0.0d : score / maxScore, List.copyOf(reasons));
    }

    private boolean matchesApiPathHint(KnowledgeRetrievalHit hit, KnowledgeQuery query) {
        return query.apiPath() != null && metadataList(hit.metadata(), "apiPathHints").contains(query.apiPath());
    }

    private boolean matchesHttpMethodHint(KnowledgeRetrievalHit hit, KnowledgeQuery query) {
        if (query.httpMethod() == null) {
            return false;
        }
        return metadataList(hit.metadata(), "httpMethodHints").stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(query.httpMethod()::equals);
    }

    private double structureScore(KnowledgeRetrievalHit hit, KnowledgeQuery query) {
        var headerTerms = normalizedTerms(String.join(" ", metadataList(hit.metadata(), "headerPath")));
        var titleTerms = normalizedTerms(hit.chunkTitle());
        return Math.max(overlapRatio(normalizedTerms(query.rawQuery()), headerTerms), overlapRatio(normalizedTerms(query.rawQuery()), titleTerms));
    }

    private double authorityScore(DocumentAuthority authority) {
        return switch (authority) {
            case HIGH -> 1.0d;
            case MEDIUM -> 0.6d;
            case LOW -> 0.2d;
        };
    }

    private double freshnessScore(String documentId, long newestTimestamp, long oldestTimestamp) {
        var currentTimestamp = documents.findById(documentId)
            .map(document -> document.getUpdatedAt().toEpochMilli())
            .orElse(oldestTimestamp);
        if (newestTimestamp <= oldestTimestamp) {
            return 1.0d;
        }
        return (double) (currentTimestamp - oldestTimestamp) / (double) (newestTimestamp - oldestTimestamp);
    }

    private double stageFitScore(KnowledgeRetrievalHit hit, KnowledgeQuery query) {
        if (query.applicableStage() == null) {
            var inferredStage = inferStageFromQuery(query.rawQuery());
            return inferredStage == null
                ? 0.5d
                : (hit.applicableStages().contains(inferredStage) ? 1.0d : 0.0d);
        }
        return hit.applicableStages().contains(query.applicableStage()) ? 1.0d : 0.0d;
    }

    private String inferStageFromQuery(String rawQuery) {
        var lower = rawQuery.toLowerCase(Locale.ROOT);
        if (lower.contains("case")) {
            return "case_generation";
        }
        if (lower.contains("error") || lower.contains("failure")) {
            return "failure_analysis";
        }
        if (lower.contains("api")) {
            return "api_analysis";
        }
        return null;
    }

    private double semanticScore(KnowledgeRetrievalHit hit, float[] queryEmbedding) {
        var distance = hit.metadata().get("vectorDistance");
        if (distance instanceof Number number) {
            return Math.max(0.0d, 1.0d - number.doubleValue());
        }
        return Math.max(0.0d, cosineSimilarity(queryEmbedding, new float[embeddingService.dimensions()]));
    }

    private void validateEmbedding(float[] embedding) {
        EmbeddingValidation.requireVector(
            "query",
            embeddingService.profile(),
            embedding,
            embeddingService.dimensions()
        );
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    @SuppressWarnings("unchecked")
    private List<String> metadataList(KnowledgeChunk chunk, String key) {
        return metadataList(chunk.getMetadata(), key);
    }

    @SuppressWarnings("unchecked")
    private List<String> metadataList(Map<String, Object> metadata, String key) {
        var value = metadata.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        }
        return List.of();
    }

    private KnowledgeRetrievalResult emptyResult(String rawQuery) {
        return new KnowledgeRetrievalResult(rawQuery, List.of(), KnowledgeContext.empty(true, true), 0.0d, 0, 0, true);
    }

    private KnowledgeContext assembleKnowledgeContext(
        List<KnowledgeRetrievalHit> hits,
        double coverage,
        boolean lowConfidence
    ) {
        if (hits.isEmpty()) {
            return KnowledgeContext.empty(lowConfidence, coverage < 0.5d);
        }

        var businessRules = new ArrayList<KnowledgeContextEntry>();
        var apiNotes = new ArrayList<KnowledgeContextEntry>();
        var testSpecs = new ArrayList<KnowledgeContextEntry>();
        var errorCodeGuides = new ArrayList<KnowledgeContextEntry>();
        var environmentNotes = new ArrayList<KnowledgeContextEntry>();
        var incidentHints = new ArrayList<KnowledgeContextEntry>();
        var citedChunks = new ArrayList<KnowledgeContextEntry>();

        for (var hit : hits) {
            var entry = toContextEntry(hit);
            citedChunks.add(entry);
            switch (hit.documentType()) {
                case BUSINESS_FLOW, DOMAIN_RULE -> businessRules.add(entry);
                case API_NOTE -> apiNotes.add(entry);
                case TEST_SPEC -> testSpecs.add(entry);
                case ERROR_CODE_GUIDE -> errorCodeGuides.add(entry);
                case ENV_GUIDE -> environmentNotes.add(entry);
                case INCIDENT_POSTMORTEM -> incidentHints.add(entry);
            }
        }

        return new KnowledgeContext(
            List.copyOf(businessRules),
            List.copyOf(apiNotes),
            List.copyOf(testSpecs),
            List.copyOf(errorCodeGuides),
            List.copyOf(environmentNotes),
            List.copyOf(incidentHints),
            List.copyOf(citedChunks),
            lowConfidence,
            coverage < 0.5d
        );
    }

    private KnowledgeContextEntry toContextEntry(KnowledgeRetrievalHit hit) {
        return new KnowledgeContextEntry(
            hit.chunkId(),
            hit.documentId(),
            hit.documentRevisionId(),
            hit.chunkTitle(),
            hit.score(),
            evidenceType(hit.documentType()),
            hit.sourceRef(),
            hit.metadata(),
            hit.matchReasons(),
            hit.lowConfidence()
        );
    }

    private String evidenceType(DocumentType documentType) {
        return switch (documentType) {
            case BUSINESS_FLOW, DOMAIN_RULE -> "business-rule";
            case API_NOTE -> "api-note";
            case TEST_SPEC -> "test-spec";
            case ERROR_CODE_GUIDE -> "error-code-guide";
            case ENV_GUIDE -> "environment-note";
            case INCIDENT_POSTMORTEM -> "incident-hint";
        };
    }

    private boolean hasUsefulContext(KnowledgeContext context, double coverage) {
        return !context.isEmpty() && !context.lowConfidence() && coverage >= 0.5d;
    }

    private String firstNonBlank(String preferred, String fallback) {
        var normalizedPreferred = normalizeNullable(preferred);
        return normalizedPreferred != null ? normalizedPreferred : normalizeNullable(fallback);
    }

    private String httpMethodValue(HttpMethod httpMethod) {
        return httpMethod == null ? null : httpMethod.name();
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeUpper(String value) {
        var normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList();
    }

    private List<String> normalizedTerms(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9_{}\\-/]+"))
            .map(String::trim)
            .filter(token -> !token.isBlank())
            .filter(token -> !STOP_WORDS.contains(token))
            .distinct()
            .toList();
    }

    private List<String> identifiers(String value) {
        var matches = new LinkedHashSet<String>();
        var matcher = IDENTIFIER.matcher(value == null ? "" : value);
        while (matcher.find()) {
            var token = matcher.group().toLowerCase(Locale.ROOT);
            if (!STOP_WORDS.contains(token)) {
                matches.add(token);
            }
        }
        return List.copyOf(matches);
    }

    private List<String> lowercased(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    private List<String> metadataUpper(KnowledgeRetrievalHit hit, String key) {
        return metadataList(hit.metadata(), key).stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .toList();
    }

    private boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }

    private double overlapRatio(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        var rightSet = new LinkedHashSet<>(right);
        var matches = left.stream().filter(rightSet::contains).count();
        return Math.min(1.0d, matches / (double) Math.max(1, Math.min(left.size(), rightSet.size())));
    }

    private String normalizeContent(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String joinNonBlank(String... parts) {
        var values = new ArrayList<String>();
        for (var part : parts) {
            if (part != null && !part.isBlank()) {
                values.add(part.trim());
            }
        }
        return String.join(" ", values);
    }

    private void addReasonWhen(List<String> reasons, boolean condition, String reason) {
        if (condition && !reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private Map<String, Object> routeEvidenceMetadata(KnowledgeRouteEvidence evidence) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("routeName", evidence.routeName());
        metadata.put("queryVariantId", evidence.queryVariantId());
        if (StringUtils.hasText(evidence.queryIntent())) {
            metadata.put("queryIntent", evidence.queryIntent());
        }
        metadata.put("routeRank", evidence.routeRank());
        metadata.put("routeScore", evidence.routeScore());
        metadata.put("matchReason", evidence.matchReason());
        metadata.put("routeWeight", routeWeight(evidence.routeName()));
        metadata.put("weightedRouteScore", weightedRouteScore(evidence));
        metadata.put("rankContribution", rankContribution(evidence));
        return metadata;
    }

    private double fusedScore(List<KnowledgeRouteEvidence> evidence) {
        var bestWeighted = evidence.stream()
            .mapToDouble(this::weightedRouteScore)
            .max()
            .orElse(0.0d);
        var rankContributionTotal = evidence.stream()
            .mapToDouble(this::rankContribution)
            .sum();
        var agreementBonus = routeAgreementBonus(evidence);
        return Math.min(1.0d, bestWeighted + rankContributionTotal + agreementBonus);
    }

    private double weightedRouteScore(KnowledgeRouteEvidence evidence) {
        return evidence.routeScore() * routeWeight(evidence.routeName());
    }

    private double rankContribution(KnowledgeRouteEvidence evidence) {
        return routeWeight(evidence.routeName()) / (FUSION_RANK_CONSTANT + evidence.routeRank());
    }

    private double routeAgreementBonus(List<KnowledgeRouteEvidence> evidence) {
        var routeCount = evidence.stream()
            .map(KnowledgeRouteEvidence::routeName)
            .distinct()
            .count();
        return Math.min(0.12d, Math.max(0L, routeCount - 1L) * 0.04d);
    }

    private double routeWeight(String routeName) {
        return switch (routeName) {
            case "original-semantic" -> 1.00d;
            case "rewritten-semantic" -> 0.98d;
            case "metadata-exact" -> 0.95d;
            case "lexical-tag" -> 0.90d;
            case "document-type" -> 0.85d;
            default -> 0.80d;
        };
    }

    private Map<String, Object> fusionExplanation(List<KnowledgeRouteEvidence> evidence, double fusedScore) {
        var routeWeights = new LinkedHashMap<String, Double>();
        var rankContributions = new LinkedHashMap<String, Double>();
        var preFusionRanks = new LinkedHashMap<String, Integer>();
        for (var item : evidence) {
            var key = item.routeName() + ":" + item.queryVariantId();
            routeWeights.putIfAbsent(item.routeName(), routeWeight(item.routeName()));
            rankContributions.put(key, rankContribution(item));
            preFusionRanks.put(key, item.routeRank());
        }
        var explanation = new LinkedHashMap<String, Object>();
        explanation.put("formula", "bestWeightedRouteScore + rankContributionTotal + routeAgreementBonus");
        explanation.put("routeCount", evidence.size());
        explanation.put("routeNames", evidence.stream().map(KnowledgeRouteEvidence::routeName).distinct().toList());
        explanation.put("routeWeights", routeWeights);
        explanation.put("preFusionRanks", preFusionRanks);
        explanation.put("rankContributions", rankContributions);
        explanation.put("bestWeightedRouteScore", evidence.stream().mapToDouble(this::weightedRouteScore).max().orElse(0.0d));
        explanation.put("rankContributionTotal", evidence.stream().mapToDouble(this::rankContribution).sum());
        explanation.put("routeAgreementBonus", routeAgreementBonus(evidence));
        explanation.put("fusedScore", fusedScore);
        return Map.copyOf(explanation);
    }

    private record RouteQuery(QueryVariant variant, KnowledgeQuery query, QueryFilters filters) {
    }

    private record RouteScoring(double score, List<String> reasons) {
        private RouteScoring {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    private record ScoredRouteHit(
        KnowledgeRetrievalHit hit,
        QueryVariant variant,
        double score,
        String matchReason,
        List<String> reasons,
        Map<String, Double> componentScores
    ) {
        private ScoredRouteHit {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            componentScores = componentScores == null ? Map.of() : Map.copyOf(componentScores);
        }
    }

    private record RouteCandidate(KnowledgeRetrievalHit hit, KnowledgeRouteEvidence evidence) {
    }

    private final class MergedRouteHit {
        private KnowledgeRetrievalHit baseHit;
        private double score;
        private final List<KnowledgeRouteEvidence> evidence = new ArrayList<>();
        private final LinkedHashMap<String, Double> componentScores = new LinkedHashMap<>();
        private final LinkedHashSet<String> matchReasons = new LinkedHashSet<>();

        private MergedRouteHit(KnowledgeRetrievalHit baseHit) {
            this.baseHit = baseHit;
        }

        private MergedRouteHit add(RouteCandidate candidate) {
            var hit = candidate.hit();
            if (hit.score() >= score) {
                baseHit = hit;
                score = hit.score();
            }
            evidence.add(candidate.evidence());
            matchReasons.addAll(hit.matchReasons());
            mergeScores(hit.componentScores());
            return this;
        }

        private void mergeScores(Map<String, Double> scores) {
            if (scores == null) {
                return;
            }
            for (var entry : scores.entrySet()) {
                componentScores.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }

        private KnowledgeRetrievalHit toHit() {
            var fusedScore = fusedScore(evidence);
            var bestEvidence = evidence.stream()
                .min(Comparator
                    .comparingInt(KnowledgeRouteEvidence::routeRank)
                    .thenComparing(KnowledgeRouteEvidence::routeName))
                .orElseGet(() -> fallbackRouteEvidence(baseHit));
            var metadata = new LinkedHashMap<>(baseHit.metadata());
            metadata.put("routeEvidence", evidence.stream()
                .map(KnowledgeRetrievalApplicationService.this::routeEvidenceMetadata)
                .toList());
            metadata.put("routeNames", evidence.stream()
                .map(KnowledgeRouteEvidence::routeName)
                .distinct()
                .toList());
            metadata.put("queryVariantIds", evidence.stream()
                .map(KnowledgeRouteEvidence::queryVariantId)
                .distinct()
                .toList());
            metadata.put("queryVariantIntents", evidence.stream()
                .map(KnowledgeRouteEvidence::queryIntent)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
            metadata.put("preFusionRoute", bestEvidence.routeName());
            metadata.put("preFusionRank", bestEvidence.routeRank());
            metadata.put("preFusionRanks", evidence.stream()
                .collect(java.util.stream.Collectors.toMap(
                    item -> item.routeName() + ":" + item.queryVariantId(),
                    KnowledgeRouteEvidence::routeRank,
                    (left, right) -> left,
                    LinkedHashMap::new
                )));
            metadata.putIfAbsent("candidateRank", bestEvidence.routeRank());
            metadata.put("fusedScore", fusedScore);
            metadata.put("fusionExplanation", fusionExplanation(evidence, fusedScore));

            componentScores.put("routeAgreement", Math.min(1.0d, evidence.stream()
                .map(KnowledgeRouteEvidence::routeName)
                .distinct()
                .count() / 4.0d));
            componentScores.put("rankContribution", evidence.stream()
                .mapToDouble(KnowledgeRetrievalApplicationService.this::rankContribution)
                .sum());
            for (var item : evidence) {
                componentScores.merge("route." + item.routeName(), item.routeScore(), Math::max);
            }
            matchReasons.add("route-fusion");
            if (evidence.size() > 1) {
                matchReasons.add("route-agreement");
            }

            return new KnowledgeRetrievalHit(
                baseHit.chunkId(),
                baseHit.documentId(),
                baseHit.documentRevisionId(),
                baseHit.chunkTitle(),
                baseHit.chunkContent(),
                baseHit.sourceRef(),
                baseHit.documentType(),
                baseHit.authority(),
                baseHit.systemName(),
                baseHit.moduleName(),
                baseHit.bizEntity(),
                baseHit.tags(),
                baseHit.applicableStages(),
                metadata,
                baseHit.tokenCount(),
                fusedScore,
                componentScores,
                List.copyOf(matchReasons),
                fusedScore < 0.30d,
                evidence
            );
        }
    }

    private record KeywordEvidence(double score, List<String> reasons) {
    }

    private static final class Weights {
        private static final double KEYWORD = 0.34d;
        private static final double VECTOR = 0.30d;
        private static final double STRUCTURE = 0.14d;
        private static final double AUTHORITY = 0.10d;
        private static final double FRESHNESS = 0.04d;
        private static final double STAGE_FIT = 0.08d;

        private Weights() {
        }
    }
}
