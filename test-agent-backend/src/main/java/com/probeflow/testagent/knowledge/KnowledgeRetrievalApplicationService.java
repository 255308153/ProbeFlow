package com.probeflow.testagent.knowledge;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.HttpMethod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeRetrievalApplicationService {

    private static final int DEFAULT_LIMIT = 8;
    private static final int DEFAULT_TOKEN_BUDGET = 1200;
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

        var candidates = chunks.findActiveLatestChunks(
            normalized.systemName(),
            normalized.moduleName(),
            normalized.bizEntity(),
            normalized.documentType()
        );
        if (candidates.isEmpty()) {
            return emptyResult(normalized.rawQuery());
        }

        var documentsById = loadDocuments(candidates);
        var filtered = candidates.stream()
            .filter(chunk -> matchesApiPath(chunk, normalized.apiPath()))
            .filter(chunk -> matchesHttpMethod(chunk, normalized.httpMethod()))
            .filter(chunk -> matchesStage(chunk, normalized.applicableStage()))
            .filter(chunk -> matchesTags(chunk, normalized.tags()))
            .map(chunk -> toHit(chunk, documentsById.get(chunk.getDocumentId())))
            .filter(Objects::nonNull)
            .toList();
        if (filtered.isEmpty()) {
            return emptyResult(normalized.rawQuery());
        }

        var ranked = rankCandidates(filtered, normalized);
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

    private Map<String, KnowledgeDocument> loadDocuments(List<KnowledgeChunk> candidates) {
        var documentIds = candidates.stream()
            .map(KnowledgeChunk::getDocumentId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var documentsById = new LinkedHashMap<String, KnowledgeDocument>();
        for (var document : documents.findAllById(documentIds)) {
            documentsById.put(document.getDocumentId(), document);
        }
        return documentsById;
    }

    private KnowledgeRetrievalHit toHit(KnowledgeChunk chunk, KnowledgeDocument document) {
        if (document == null) {
            return null;
        }
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
            new LinkedHashMap<>(chunk.getMetadata()),
            chunk.getTokenCount(),
            0.0d,
            Map.of(),
            List.of(),
            true
        );
    }

    private List<KnowledgeRetrievalHit> rankCandidates(List<KnowledgeRetrievalHit> candidates, KnowledgeQuery query) {
        var queryEmbedding = embeddingService.embedQuery(query.rawQuery());
        validateEmbedding(queryEmbedding);
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
        var vector = Math.max(0.0d, cosineSimilarity(queryEmbedding, loadChunkEmbedding(hit)));
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
            finalScore < 0.30d || (keyword.score() < 0.20d && vector < 0.45d)
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

    private boolean matchesApiPath(KnowledgeChunk chunk, String apiPath) {
        if (apiPath == null) {
            return true;
        }
        return metadataList(chunk, "apiPathHints").contains(apiPath);
    }

    private boolean matchesHttpMethod(KnowledgeChunk chunk, String httpMethod) {
        if (httpMethod == null) {
            return true;
        }
        return metadataList(chunk, "httpMethodHints").stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(httpMethod::equals);
    }

    private boolean matchesStage(KnowledgeChunk chunk, String applicableStage) {
        return applicableStage == null || chunk.getApplicableStages().contains(applicableStage);
    }

    private boolean matchesTags(KnowledgeChunk chunk, List<String> tags) {
        return tags.isEmpty() || chunk.getTags().containsAll(tags);
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

    private float[] loadChunkEmbedding(KnowledgeRetrievalHit hit) {
        return chunks.findById(hit.chunkId())
            .map(KnowledgeChunk::getEmbedding)
            .orElseGet(() -> new float[embeddingService.dimensions()]);
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

    private void addReasonWhen(List<String> reasons, boolean condition, String reason) {
        if (condition && !reasons.contains(reason)) {
            reasons.add(reason);
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
