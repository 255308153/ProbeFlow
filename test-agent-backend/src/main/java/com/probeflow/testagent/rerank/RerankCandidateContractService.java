package com.probeflow.testagent.rerank;

import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalHit;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class RerankCandidateContractService {

    public List<RerankCandidate> normalize(
        List<KnowledgeRetrievalHit> knowledgeHits,
        List<LongTermMemoryRetrievalHit> memoryHits,
        Map<String, RerankRouteEvidence> routeEvidenceByCandidate
    ) {
        var normalized = new ArrayList<RerankCandidate>();
        var safeKnowledgeHits = knowledgeHits == null ? List.<KnowledgeRetrievalHit>of() : knowledgeHits;
        var safeMemoryHits = memoryHits == null ? List.<LongTermMemoryRetrievalHit>of() : memoryHits;
        var safeRouteEvidence = routeEvidenceByCandidate == null
            ? Map.<String, RerankRouteEvidence>of()
            : routeEvidenceByCandidate;

        for (var i = 0; i < safeKnowledgeHits.size(); i++) {
            normalized.add(knowledgeCandidate(safeKnowledgeHits.get(i), i + 1, safeRouteEvidence));
        }
        for (var i = 0; i < safeMemoryHits.size(); i++) {
            normalized.add(memoryCandidate(safeMemoryHits.get(i), safeKnowledgeHits.size() + i + 1, safeRouteEvidence));
        }
        return List.copyOf(normalized);
    }

    public RerankOutput recordOutput(
        List<RerankCandidate> beforeOrder,
        List<RerankScoreAssignment> rankedAssignments
    ) {
        var safeBeforeOrder = beforeOrder == null ? List.<RerankCandidate>of() : beforeOrder;
        var safeAssignments = rankedAssignments == null ? List.<RerankScoreAssignment>of() : rankedAssignments;
        var candidatesByKey = new LinkedHashMap<String, RerankCandidate>();
        var beforeRanksByKey = new LinkedHashMap<String, Integer>();
        for (var i = 0; i < safeBeforeOrder.size(); i++) {
            var candidate = safeBeforeOrder.get(i);
            var key = candidate.candidateIdentity().stableKey();
            candidatesByKey.put(key, candidate);
            beforeRanksByKey.put(key, candidate.beforeRank() > 0 ? candidate.beforeRank() : i + 1);
        }

        var items = new ArrayList<RerankOutputItem>();
        for (var i = 0; i < safeAssignments.size(); i++) {
            var assignment = safeAssignments.get(i);
            var key = assignment.candidateIdentity().stableKey();
            var candidate = candidatesByKey.get(key);
            if (candidate == null) {
                throw new IllegalArgumentException("Unknown rerank candidate: " + key);
            }
            var beforeRank = beforeRanksByKey.get(key);
            var afterRank = i + 1;
            items.add(new RerankOutputItem(
                candidate,
                beforeRank,
                afterRank,
                assignment.rerankScore(),
                scoreExplanation(candidate, beforeRank, afterRank, assignment),
                assignment.reasons(),
                assignment.penalties()
            ));
        }
        return new RerankOutput(items);
    }

    private RerankCandidate knowledgeCandidate(
        KnowledgeRetrievalHit hit,
        int fallbackRank,
        Map<String, RerankRouteEvidence> routeEvidenceByCandidate
    ) {
        var candidateId = hasText(hit.chunkId()) ? hit.chunkId() : "unknown-knowledge-" + fallbackRank;
        var identity = new RerankCandidateIdentity(RerankCorpusType.KNOWLEDGE, candidateId);
        var routeEvidence = routeEvidence(identity, routeEvidenceByCandidate);
        var hasRouteEvidence = routeEvidence != null;
        var effectiveRouteEvidence = hasRouteEvidence ? routeEvidence : RerankRouteEvidence.empty();
        var featureLedger = knowledgeFeatureLedger(hit, effectiveRouteEvidence, hasRouteEvidence);
        return new RerankCandidate(
            RerankCorpusType.KNOWLEDGE,
            identity,
            new RerankSourceIdentity(
                hit.documentType() == null ? null : hit.documentType().name(),
                hit.sourceRef(),
                knowledgeSourceIds(hit)
            ),
            hit.chunkTitle(),
            hit.chunkContent(),
            candidateRank(hit.metadata(), fallbackRank),
            effectiveRouteEvidence.fusedScore(),
            effectiveRouteEvidence,
            featureLedger,
            hit.metadata(),
            featureLedger.tokenCost(),
            missingContextHints(featureLedger, hit.metadata())
        );
    }

    private RerankCandidate memoryCandidate(
        LongTermMemoryRetrievalHit hit,
        int fallbackRank,
        Map<String, RerankRouteEvidence> routeEvidenceByCandidate
    ) {
        var candidateId = hasText(hit.memoryId()) ? hit.memoryId() : "unknown-memory-" + fallbackRank;
        var identity = new RerankCandidateIdentity(RerankCorpusType.MEMORY, candidateId);
        var routeEvidence = routeEvidence(identity, routeEvidenceByCandidate);
        var hasRouteEvidence = routeEvidence != null;
        var effectiveRouteEvidence = hasRouteEvidence ? routeEvidence : RerankRouteEvidence.empty();
        var featureLedger = memoryFeatureLedger(hit, effectiveRouteEvidence, hasRouteEvidence);
        return new RerankCandidate(
            RerankCorpusType.MEMORY,
            identity,
            new RerankSourceIdentity(
                hit.sourceType() == null ? null : hit.sourceType().name(),
                hit.sourceRef(),
                memorySourceIds(hit)
            ),
            hit.summary(),
            hasText(hit.fullContent()) ? hit.fullContent() : hit.content(),
            candidateRank(hit.metadata(), fallbackRank),
            effectiveRouteEvidence.fusedScore(),
            effectiveRouteEvidence,
            featureLedger,
            hit.metadata(),
            featureLedger.tokenCost(),
            missingContextHints(featureLedger, hit.metadata())
        );
    }

    private RerankFeatureLedger knowledgeFeatureLedger(
        KnowledgeRetrievalHit hit,
        RerankRouteEvidence routeEvidence,
        boolean hasRouteEvidence
    ) {
        var semantic = score(hit.componentScores(), "semantic", "vector", "embedding");
        var metadata = score(hit.componentScores(), "metadata", "structure");
        var lexical = score(hit.componentScores(), "lexical", "keyword");
        var routeAgreement = hasRouteEvidence ? routeEvidence.fusedScore() : null;
        var exactEntity = firstNonNull(
            score(hit.componentScores(), "exactEntity", "entityExact", "businessEntityExact"),
            numericOrBooleanMetadata(hit.metadata(), "exactEntity", "entityExact", "businessEntityExact")
        );
        var stageFit = score(hit.componentScores(), "stageFit", "stage_fit");
        var authority = authorityScore(hit.authority());
        var freshness = firstNonNull(
            score(hit.componentScores(), "freshness", "freshnessScore"),
            numericOrBooleanMetadata(hit.metadata(), "freshness", "freshnessScore")
        );
        var tokenCost = hit.tokenCount();
        var diagnostics = commonDiagnostics(
            semantic,
            metadata,
            lexical,
            routeAgreement,
            exactEntity,
            stageFit,
            freshness,
            tokenCost,
            hasRouteEvidence
        );
        if (authority == null) {
            diagnostics.add(RerankFeatureDiagnostic.missingFeature("authority"));
        }

        return new RerankFeatureLedger(
            semantic,
            metadata,
            lexical,
            routeAgreement,
            exactEntity,
            stageFit,
            authority,
            freshness,
            null,
            null,
            null,
            null,
            null,
            hit.lowConfidence(),
            conflictSignals(hit.metadata()),
            tokenCost,
            diagnostics
        );
    }

    private RerankFeatureLedger memoryFeatureLedger(
        LongTermMemoryRetrievalHit hit,
        RerankRouteEvidence routeEvidence,
        boolean hasRouteEvidence
    ) {
        var semantic = score(hit.componentScores(), "semantic", "vector", "embedding");
        var metadata = score(hit.componentScores(), "metadata", "structure", "tag");
        var lexical = score(hit.componentScores(), "lexical", "keyword");
        var routeAgreement = hasRouteEvidence ? routeEvidence.fusedScore() : null;
        var exactEntity = firstNonNull(
            score(hit.componentScores(), "exactEntity", "entityExact", "businessEntityExact"),
            numericOrBooleanMetadata(hit.metadata(), "exactEntity", "entityExact", "businessEntityExact")
        );
        var stageFit = score(hit.componentScores(), "stageFit", "stage_fit");
        var freshness = firstNonNull(
            score(hit.componentScores(), "freshness", "freshnessScore"),
            numericOrBooleanMetadata(hit.metadata(), "freshness", "freshnessScore")
        );
        var memoryConfidence = number(hit.confidence());
        var memoryImportance = number(hit.importance());
        var memorySuccessContribution = number(hit.successContribution());
        var graphConfidence = numericOrBooleanMetadata(hit.metadata(), "graphRelationConfidence", "graphConfidence");
        var graphPathLength = graphPathLength(hit.metadata());
        var tokenCost = hit.tokenCount();
        var diagnostics = commonDiagnostics(
            semantic,
            metadata,
            lexical,
            routeAgreement,
            exactEntity,
            stageFit,
            freshness,
            tokenCost,
            hasRouteEvidence
        );
        addMissing(diagnostics, "memoryConfidence", memoryConfidence);
        addMissing(diagnostics, "memoryImportance", memoryImportance);
        addMissing(diagnostics, "memorySuccessContribution", memorySuccessContribution);
        if (isGraphCandidate(hit.metadata())) {
            addMissing(diagnostics, "graphConfidence", graphConfidence);
            if (graphPathLength == null) {
                diagnostics.add(RerankFeatureDiagnostic.missingFeature("graphPathLength"));
            }
        }

        return new RerankFeatureLedger(
            semantic,
            metadata,
            lexical,
            routeAgreement,
            exactEntity,
            stageFit,
            null,
            freshness,
            memoryConfidence,
            memoryImportance,
            memorySuccessContribution,
            graphConfidence,
            graphPathLength,
            hit.lowConfidence(),
            conflictSignals(hit.metadata()),
            tokenCost,
            diagnostics
        );
    }

    private ArrayList<RerankFeatureDiagnostic> commonDiagnostics(
        Double semantic,
        Double metadata,
        Double lexical,
        Double routeAgreement,
        Double exactEntity,
        Double stageFit,
        Double freshness,
        int tokenCost,
        boolean hasRouteEvidence
    ) {
        var diagnostics = new ArrayList<RerankFeatureDiagnostic>();
        if (!hasRouteEvidence) {
            diagnostics.add(new RerankFeatureDiagnostic(
                "missing-route-evidence",
                "routeEvidence",
                "V5-4 route evidence was not supplied; rerank contract did not synthesize a replacement."
            ));
        }
        addMissing(diagnostics, "semantic", semantic);
        addMissing(diagnostics, "metadata", metadata);
        addMissing(diagnostics, "lexical", lexical);
        addMissing(diagnostics, "routeAgreement", routeAgreement);
        addMissing(diagnostics, "exactEntity", exactEntity);
        addMissing(diagnostics, "stageFit", stageFit);
        addMissing(diagnostics, "freshness", freshness);
        if (tokenCost <= 0) {
            diagnostics.add(RerankFeatureDiagnostic.missingFeature("tokenCost"));
        }
        return diagnostics;
    }

    private RerankRouteEvidence routeEvidence(
        RerankCandidateIdentity identity,
        Map<String, RerankRouteEvidence> routeEvidenceByCandidate
    ) {
        var byStableKey = routeEvidenceByCandidate.get(identity.stableKey());
        if (byStableKey != null) {
            return byStableKey;
        }
        return routeEvidenceByCandidate.get(identity.candidateId());
    }

    private Map<String, String> knowledgeSourceIds(KnowledgeRetrievalHit hit) {
        var sourceIds = new LinkedHashMap<String, String>();
        putIfHasText(sourceIds, "chunkId", hit.chunkId());
        putIfHasText(sourceIds, "documentId", hit.documentId());
        putIfHasText(sourceIds, "documentRevisionId", hit.documentRevisionId());
        return sourceIds;
    }

    private Map<String, String> memorySourceIds(LongTermMemoryRetrievalHit hit) {
        var sourceIds = new LinkedHashMap<String, String>();
        putIfHasText(sourceIds, "memoryId", hit.memoryId());
        if (hit.scopeType() != null) {
            sourceIds.put("scopeType", hit.scopeType().name());
        }
        putMetadataText(sourceIds, "factFingerprint", hit.metadata());
        return sourceIds;
    }

    private int candidateRank(Map<String, Object> metadata, int fallbackRank) {
        var rank = integerMetadata(metadata, "candidateRank", "rank", "beforeRank");
        return rank == null || rank <= 0 ? fallbackRank : rank;
    }

    private Double authorityScore(DocumentAuthority authority) {
        if (authority == null) {
            return null;
        }
        return switch (authority) {
            case HIGH -> 1.0d;
            case MEDIUM -> 0.6d;
            case LOW -> 0.3d;
        };
    }

    private Double score(Map<String, Double> componentScores, String... aliases) {
        if (componentScores == null || componentScores.isEmpty()) {
            return null;
        }
        for (var alias : aliases) {
            var normalizedAlias = normalizeKey(alias);
            for (var entry : componentScores.entrySet()) {
                if (normalizeKey(entry.getKey()).equals(normalizedAlias)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private Double numericOrBooleanMetadata(Map<String, Object> metadata, String... aliases) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (var alias : aliases) {
            var value = metadataValue(metadata, alias);
            var number = number(value);
            if (number != null) {
                return number;
            }
            if (value instanceof Boolean bool) {
                return bool ? 1.0d : 0.0d;
            }
        }
        return null;
    }

    private Integer integerMetadata(Map<String, Object> metadata, String... aliases) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (var alias : aliases) {
            var value = metadataValue(metadata, alias);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Object metadataValue(Map<String, Object> metadata, String alias) {
        var normalizedAlias = normalizeKey(alias);
        for (var entry : metadata.entrySet()) {
            if (normalizeKey(entry.getKey()).equals(normalizedAlias)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer graphPathLength(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        var path = metadataValue(metadata, "graphRelationPath");
        if (path instanceof Collection<?> collection) {
            return collection.size();
        }
        return integerMetadata(metadata, "graphPathLength", "pathLength");
    }

    private boolean isGraphCandidate(Map<String, Object> metadata) {
        var channel = metadata == null ? null : metadataValue(metadata, "retrievalChannel");
        return channel != null && "graph".equalsIgnoreCase(channel.toString());
    }

    private List<String> conflictSignals(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        var signals = new ArrayList<String>();
        addConflictSignal(signals, metadataValue(metadata, "conflictSignals"));
        addConflictSignal(signals, metadataValue(metadata, "conflicts"));
        addConflictSignal(signals, metadataValue(metadata, "conflictReasons"));
        var conflict = metadataValue(metadata, "conflict");
        if (conflict instanceof Boolean bool && bool && signals.isEmpty()) {
            signals.add("metadata-conflict");
        }
        return List.copyOf(signals);
    }

    private void addConflictSignal(List<String> signals, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(this::hasText)
                .forEach(signals::add);
            return;
        }
        if (value != null && hasText(value.toString())) {
            signals.add(value.toString());
        }
    }

    private List<String> missingContextHints(RerankFeatureLedger ledger, Map<String, Object> metadata) {
        var hints = new ArrayList<String>();
        addContextHint(hints, metadata == null ? null : metadataValue(metadata, "missingContextHints"));
        ledger.diagnostics().stream()
            .map(RerankFeatureDiagnostic::code)
            .forEach(hints::add);
        return List.copyOf(hints);
    }

    private void addContextHint(List<String> hints, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(this::hasText)
                .forEach(hints::add);
            return;
        }
        if (value != null && hasText(value.toString())) {
            hints.add(value.toString());
        }
    }

    private String scoreExplanation(
        RerankCandidate candidate,
        int beforeRank,
        int afterRank,
        RerankScoreAssignment assignment
    ) {
        return candidate.candidateIdentity().stableKey()
            + " | beforeRank=" + beforeRank
            + " | afterRank=" + afterRank
            + " | rerankScore=" + String.format(Locale.ROOT, "%.4f", assignment.rerankScore())
            + " | reasons=" + joinOrNone(assignment.reasons())
            + " | penalties=" + joinOrNone(assignment.penalties())
            + " | missingFeatures=" + missingFeatureSummary(candidate);
    }

    private String missingFeatureSummary(RerankCandidate candidate) {
        var missing = candidate.featureLedger().diagnostics().stream()
            .map(RerankFeatureDiagnostic::code)
            .filter(code -> code.startsWith("missing-feature:"))
            .map(code -> code.substring("missing-feature:".length()))
            .toList();
        return joinOrNone(missing);
    }

    private String joinOrNone(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(",", values);
    }

    private void addMissing(List<RerankFeatureDiagnostic> diagnostics, String feature, Object value) {
        if (value == null) {
            diagnostics.add(RerankFeatureDiagnostic.missingFeature(feature));
        }
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private void putIfHasText(Map<String, String> values, String key, String value) {
        if (hasText(value)) {
            values.put(key, value);
        }
    }

    private void putMetadataText(Map<String, String> values, String key, Map<String, Object> metadata) {
        var value = metadata == null ? null : metadataValue(metadata, key);
        if (value != null && hasText(value.toString())) {
            values.put(key, value.toString());
        }
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.replace("_", "").replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
