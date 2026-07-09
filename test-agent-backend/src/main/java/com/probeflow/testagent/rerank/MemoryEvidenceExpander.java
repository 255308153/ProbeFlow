package com.probeflow.testagent.rerank;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class MemoryEvidenceExpander {

    private static final Pattern BEARER_TOKEN = Pattern.compile(
        "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(password|secret|token|api[_-]?key)(\\s*[:=]\\s*)[^\\s,;]+"
    );

    public MemoryEvidenceExpansionResult expand(MemoryEvidenceExpansionRequest request) {
        var safeRequest = request == null
            ? new MemoryEvidenceExpansionRequest(List.of(), 0)
            : request;
        var items = new ArrayList<MemoryEvidenceExpansionItem>();
        var resultPruningReasons = new ArrayList<String>();
        var totalTokens = 0;
        var hasBudget = safeRequest.tokenBudget() > 0;

        for (var outputItem : safeRequest.rerankedItems()) {
            if (outputItem == null || outputItem.candidate() == null) {
                continue;
            }
            if (outputItem.candidate().corpusType() != RerankCorpusType.MEMORY) {
                continue;
            }

            var expanded = expandItem(outputItem);
            if (hasBudget && totalTokens + expanded.estimatedTokens() > safeRequest.tokenBudget()) {
                var remainingBudget = safeRequest.tokenBudget() - totalTokens;
                if (remainingBudget <= 0) {
                    resultPruningReasons.add("token-budget-exceeded: skipped " + expanded.anchor().memoryAnchor());
                    continue;
                }
                expanded = pruneToBudget(expanded, remainingBudget);
            }

            items.add(expanded);
            totalTokens += expanded.estimatedTokens();
            resultPruningReasons.addAll(expanded.pruningReasons());
        }

        var pruned = !resultPruningReasons.isEmpty();
        return new MemoryEvidenceExpansionResult(
            items,
            safeRequest.tokenBudget(),
            totalTokens,
            pruned,
            resultPruningReasons
        );
    }

    private MemoryEvidenceExpansionItem expandItem(RerankOutputItem outputItem) {
        var candidate = outputItem.candidate();
        var metadata = candidate.metadata();
        var factFingerprint = firstText(
            candidate.sourceIdentity().sourceIds().get("factFingerprint"),
            textValue(metadata, "factFingerprint")
        );
        var graphRelationPath = textList(metadataValue(metadata, "graphRelationPath"));
        var anchorType = !graphRelationPath.isEmpty()
            ? MemoryEvidenceAnchorType.GRAPH_RELATION
            : hasText(factFingerprint) ? MemoryEvidenceAnchorType.FACT_FINGERPRINT : MemoryEvidenceAnchorType.MEMORY_HIT;
        var anchor = new MemoryEvidenceAnchor(
            anchorType,
            candidate.candidateIdentity().stableKey(),
            factFingerprint,
            graphRelationPath
        );
        var evidenceSummaries = evidenceSummaries(candidate);
        var sourceRefs = sourceRefs(candidate);
        var mergedSourceRefs = mergedSourceRefs(metadata, sourceRefs);
        var evidenceCount = evidenceCount(metadata, evidenceSummaries);
        var identityHints = identityHints(metadata);
        var graphRelation = graphRelation(metadata, anchor, sourceRefs);
        var conflictAudit = conflictAudit(candidate, sourceRefs);
        var lowConfidence = lowConfidence(candidate);
        var lowConfidenceReason = lowConfidence ? lowConfidenceReason(candidate) : null;
        var positiveRecommendationEligible = conflictAudit.isEmpty() && !lowConfidence;
        var citations = citations(anchor, sourceRefs, graphRelation, conflictAudit);
        var item = new MemoryEvidenceExpansionItem(
            anchor,
            candidate.title(),
            fullContent(candidate),
            evidenceSummaries,
            sourceRefs,
            mergedSourceRefs,
            evidenceCount,
            identityHints,
            graphRelation,
            conflictAudit,
            positiveRecommendationEligible,
            lowConfidence,
            lowConfidenceReason,
            0,
            List.of(),
            citations
        );
        return withEstimate(item, estimateTokens(item));
    }

    private MemoryEvidenceExpansionItem pruneToBudget(MemoryEvidenceExpansionItem item, int tokenBudget) {
        var reasons = new ArrayList<>(item.pruningReasons());
        reasons.add("token-budget-exceeded: " + item.anchor().memoryAnchor() + " budget=" + tokenBudget);

        var evidenceSummaries = new ArrayList<>(item.evidenceSummaries());
        if (evidenceSummaries.size() > 1) {
            evidenceSummaries = new ArrayList<>(evidenceSummaries.subList(0, 1));
            reasons.add("pruned-evidence-summaries: kept 1 of " + item.evidenceSummaries().size());
        }

        var fullContent = item.fullContent();
        var graphRelation = item.graphRelation();
        var conflictAudit = new ArrayList<>(item.conflictAudit());
        var citations = new ArrayList<>(item.citations());
        var pruned = rebuild(item, fullContent, evidenceSummaries, graphRelation, conflictAudit, reasons, citations);

        if (estimateTokens(pruned) > tokenBudget && hasText(fullContent)) {
            var withoutContent = rebuild(item, null, evidenceSummaries, graphRelation, conflictAudit, reasons, citations);
            var availableForContent = Math.max(0, tokenBudget - estimateTokens(withoutContent));
            fullContent = truncateToTokens(fullContent, availableForContent);
            reasons.add("pruned-full-content: truncated normalized memory content");
            pruned = rebuild(item, fullContent, evidenceSummaries, graphRelation, conflictAudit, reasons, citations);
        }

        if (estimateTokens(pruned) > tokenBudget && graphRelation != null && hasText(graphRelation.explanation())) {
            graphRelation = new MemoryGraphRelationExpansion(
                graphRelation.relationPath(),
                graphRelation.relationConfidence(),
                graphRelation.sourceMemoryIds(),
                graphRelation.sourceRefs(),
                graphRelation.factFingerprints(),
                graphRelation.graphEvidenceSummary(),
                truncateToTokens(graphRelation.explanation(), 12)
            );
            reasons.add("pruned-graph-explanation: kept relation path, confidence, and sources");
            pruned = rebuild(item, fullContent, evidenceSummaries, graphRelation, conflictAudit, reasons, citations);
        }

        if (estimateTokens(pruned) > tokenBudget && !conflictAudit.isEmpty()) {
            conflictAudit = new ArrayList<>(conflictAudit.subList(0, 1));
            reasons.add("pruned-conflict-audit: kept highest priority audit evidence");
            pruned = rebuild(item, fullContent, evidenceSummaries, graphRelation, conflictAudit, reasons, citations);
        }

        if (estimateTokens(pruned) > tokenBudget && evidenceSummaries.size() == 1) {
            evidenceSummaries = new ArrayList<>(List.of(truncateToTokens(evidenceSummaries.getFirst(), 16)));
            reasons.add("pruned-evidence-summary-text: shortened supporting evidence summary");
            pruned = rebuild(item, fullContent, evidenceSummaries, graphRelation, conflictAudit, reasons, citations);
        }

        if (estimateTokens(pruned) > tokenBudget && citations.size() > 1) {
            citations = new ArrayList<>(citations.subList(0, 1));
            reasons.add("pruned-citations: kept strongest evidence source citation");
            pruned = rebuild(item, fullContent, evidenceSummaries, graphRelation, conflictAudit, reasons, citations);
        }

        var estimated = tokenBudget <= 0 ? estimateTokens(pruned) : Math.min(estimateTokens(pruned), tokenBudget);
        return withEstimate(pruned, estimated);
    }

    private MemoryEvidenceExpansionItem rebuild(
        MemoryEvidenceExpansionItem item,
        String fullContent,
        List<String> evidenceSummaries,
        MemoryGraphRelationExpansion graphRelation,
        List<MemoryConflictAuditEvidence> conflictAudit,
        List<String> pruningReasons,
        List<MemoryEvidenceCitation> citations
    ) {
        return new MemoryEvidenceExpansionItem(
            item.anchor(),
            item.title(),
            fullContent,
            evidenceSummaries,
            item.sourceRefs(),
            item.mergedSourceRefs(),
            item.evidenceCount(),
            item.identityHints(),
            graphRelation,
            conflictAudit,
            item.positiveRecommendationEligible(),
            item.lowConfidence(),
            item.lowConfidenceReason(),
            0,
            pruningReasons,
            citations
        );
    }

    private MemoryEvidenceExpansionItem withEstimate(MemoryEvidenceExpansionItem item, int estimatedTokens) {
        return new MemoryEvidenceExpansionItem(
            item.anchor(),
            item.title(),
            item.fullContent(),
            item.evidenceSummaries(),
            item.sourceRefs(),
            item.mergedSourceRefs(),
            item.evidenceCount(),
            item.identityHints(),
            item.graphRelation(),
            item.conflictAudit(),
            item.positiveRecommendationEligible(),
            item.lowConfidence(),
            item.lowConfidenceReason(),
            estimatedTokens,
            item.pruningReasons(),
            item.citations()
        );
    }

    private String fullContent(RerankCandidate candidate) {
        var metadata = candidate.metadata();
        var content = firstText(
            textValue(metadata, "sanitizedFullContent"),
            textValue(metadata, "normalizedContent"),
            textValue(metadata, "refinedFullContent"),
            textValue(metadata, "fullContent"),
            candidate.content(),
            candidate.title()
        );
        return sanitize(content);
    }

    private List<String> evidenceSummaries(RerankCandidate candidate) {
        var summaries = new LinkedHashSet<String>();
        var metadata = candidate.metadata();
        collectText(summaries, metadataValue(metadata, "evidenceSummaries"));
        collectText(summaries, metadataValue(metadata, "evidenceSummary"));
        collectEvidenceLedgerSummaries(summaries, metadataValue(metadata, "evidenceLedger"));
        collectText(summaries, metadataValue(metadata, "graphEvidenceSummaries"));
        if (summaries.isEmpty()) {
            collectText(summaries, candidate.title());
        }
        return List.copyOf(summaries);
    }

    private List<String> sourceRefs(RerankCandidate candidate) {
        var refs = new LinkedHashSet<String>();
        var metadata = candidate.metadata();
        collectText(refs, candidate.sourceIdentity().sourceRef());
        collectText(refs, metadataValue(metadata, "sourceRef"));
        collectText(refs, metadataValue(metadata, "sourceRefs"));
        collectText(refs, metadataValue(metadata, "mergedSourceRefs"));
        collectEvidenceLedgerSourceRefs(refs, metadataValue(metadata, "evidenceLedger"));
        collectText(refs, metadataValue(metadata, "graphSourceRefs"));
        return List.copyOf(refs);
    }

    private List<String> mergedSourceRefs(Map<String, Object> metadata, List<String> sourceRefs) {
        var refs = new LinkedHashSet<String>();
        collectText(refs, metadataValue(metadata, "mergedSourceRefs"));
        if (refs.isEmpty()) {
            refs.addAll(sourceRefs);
        }
        return List.copyOf(refs);
    }

    private int evidenceCount(Map<String, Object> metadata, List<String> evidenceSummaries) {
        var configured = integerValue(metadata, "evidenceCount");
        if (configured != null && configured > 0) {
            return configured;
        }
        var ledgerCount = collectionSize(metadataValue(metadata, "evidenceLedger"));
        if (ledgerCount > 0) {
            return ledgerCount;
        }
        return evidenceSummaries.size();
    }

    private MemoryEvidenceIdentityHints identityHints(Map<String, Object> metadata) {
        return new MemoryEvidenceIdentityHints(
            textValue(metadata, "system", "systemName"),
            textValue(metadata, "module", "moduleName"),
            textValue(metadata, "apiPath", "path", "endpoint"),
            textValue(metadata, "errorCode", "error", "code"),
            textValue(metadata, "businessEntity", "businessObject", "entity", "entityName"),
            textValue(metadata, "suiteId", "suiteScope"),
            textValue(metadata, "variableKey", "suiteVariableKey", "targetKey"),
            textValue(metadata, "policyReason", "policyReasonCode", "reasonCode", "approvalReason")
        );
    }

    private MemoryGraphRelationExpansion graphRelation(
        Map<String, Object> metadata,
        MemoryEvidenceAnchor anchor,
        List<String> fallbackSourceRefs
    ) {
        var relationPath = textList(metadataValue(metadata, "graphRelationPath"));
        var relationConfidence = numberValue(metadata, "graphRelationConfidence", "graphConfidence");
        var sourceMemoryIds = textList(metadataValue(metadata, "graphSourceMemoryIds"));
        var graphSourceRefs = textList(metadataValue(metadata, "graphSourceRefs"));
        var factFingerprints = textList(metadataValue(metadata, "graphFactFingerprints"));
        var evidenceSummaries = textList(metadataValue(metadata, "graphEvidenceSummaries"));
        if (relationPath.isEmpty() && relationConfidence == null && sourceMemoryIds.isEmpty()) {
            return null;
        }
        var sourceRefs = graphSourceRefs.isEmpty() ? fallbackSourceRefs : graphSourceRefs;
        var graphEvidenceSummary = evidenceSummaries.isEmpty() ? null : evidenceSummaries.getFirst();
        var explanation = graphExplanation(anchor, relationPath, relationConfidence, sourceMemoryIds, graphEvidenceSummary);
        return new MemoryGraphRelationExpansion(
            relationPath,
            relationConfidence,
            sourceMemoryIds,
            sourceRefs,
            factFingerprints,
            graphEvidenceSummary,
            explanation
        );
    }

    private List<MemoryConflictAuditEvidence> conflictAudit(RerankCandidate candidate, List<String> fallbackSourceRefs) {
        var evidence = new ArrayList<MemoryConflictAuditEvidence>();
        var seen = new LinkedHashSet<String>();
        var metadata = candidate.metadata();
        addAuditEvidence(evidence, seen, metadataValue(metadata, "conflictAudit"), MemoryEvidenceRole.AUDIT, fallbackSourceRefs);
        addAuditEvidence(evidence, seen, metadataValue(metadata, "auditEvidence"), MemoryEvidenceRole.AUDIT, fallbackSourceRefs);
        addAuditEvidence(evidence, seen, metadataValue(metadata, "conflictSignals"), MemoryEvidenceRole.CONFLICT, fallbackSourceRefs);
        addAuditEvidence(evidence, seen, metadataValue(metadata, "conflictReasons"), MemoryEvidenceRole.CONFLICT, fallbackSourceRefs);
        for (var signal : candidate.featureLedger().conflictSignals()) {
            addAuditEvidence(evidence, seen, signal, MemoryEvidenceRole.CONFLICT, fallbackSourceRefs);
        }
        var conflict = metadataValue(metadata, "conflict");
        if (conflict instanceof Boolean bool && bool && evidence.isEmpty()) {
            addAuditEvidence(evidence, seen, "metadata-conflict", MemoryEvidenceRole.CONFLICT, fallbackSourceRefs);
        }
        return List.copyOf(evidence);
    }

    private void addAuditEvidence(
        List<MemoryConflictAuditEvidence> evidence,
        LinkedHashSet<String> seen,
        Object value,
        MemoryEvidenceRole defaultRole,
        List<String> fallbackSourceRefs
    ) {
        if (value instanceof Collection<?> collection) {
            for (var item : collection) {
                addAuditEvidence(evidence, seen, item, defaultRole, fallbackSourceRefs);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            var summary = firstText(
                textValue(map, "summary"),
                textValue(map, "reason"),
                textValue(map, "conflictReason"),
                textValue(map, "description")
            );
            var role = auditRole(textValue(map, "role", "evidenceRole", "type"), defaultRole);
            var refs = textList(metadataValue(map, "sourceRef"));
            if (refs.isEmpty()) {
                refs = textList(metadataValue(map, "sourceRefs"));
            }
            if (refs.isEmpty()) {
                refs = fallbackSourceRefs;
            }
            addAuditEvidenceText(evidence, seen, summary, role, refs);
            return;
        }
        addAuditEvidenceText(evidence, seen, value == null ? null : value.toString(), defaultRole, fallbackSourceRefs);
    }

    private void addAuditEvidenceText(
        List<MemoryConflictAuditEvidence> evidence,
        LinkedHashSet<String> seen,
        String summary,
        MemoryEvidenceRole role,
        List<String> sourceRefs
    ) {
        if (!hasText(summary) || !seen.add(role.name() + ":" + summary)) {
            return;
        }
        evidence.add(new MemoryConflictAuditEvidence(role, summary, sourceRefs));
    }

    private MemoryEvidenceRole auditRole(String role, MemoryEvidenceRole fallback) {
        if (!hasText(role)) {
            return fallback;
        }
        var normalized = role.toLowerCase(Locale.ROOT);
        if (normalized.contains("conflict")) {
            return MemoryEvidenceRole.CONFLICT;
        }
        if (normalized.contains("audit")) {
            return MemoryEvidenceRole.AUDIT;
        }
        return fallback;
    }

    private boolean lowConfidence(RerankCandidate candidate) {
        if (candidate.featureLedger().lowConfidence()) {
            return true;
        }
        var confidence = candidate.featureLedger().memoryConfidence();
        return confidence != null && confidence < 0.50d;
    }

    private String lowConfidenceReason(RerankCandidate candidate) {
        var configured = textValue(candidate.metadata(), "lowConfidenceReason", "confidenceReason");
        if (hasText(configured)) {
            return configured;
        }
        var confidence = candidate.featureLedger().memoryConfidence();
        if (confidence != null && confidence < 0.50d) {
            return "memory confidence " + String.format(Locale.ROOT, "%.2f", confidence) + " is below 0.50";
        }
        return "rerank contract marked memory as low confidence";
    }

    private List<MemoryEvidenceCitation> citations(
        MemoryEvidenceAnchor anchor,
        List<String> sourceRefs,
        MemoryGraphRelationExpansion graphRelation,
        List<MemoryConflictAuditEvidence> conflictAudit
    ) {
        var citations = new ArrayList<MemoryEvidenceCitation>();
        var supportingRefs = sourceRefs.isEmpty() ? List.of("missing-evidence-source") : sourceRefs;
        for (var ref : supportingRefs) {
            citations.add(new MemoryEvidenceCitation(
                anchor.memoryAnchor(),
                anchor.factFingerprint(),
                ref,
                MemoryEvidenceRole.SUPPORTING
            ));
        }
        if (graphRelation != null) {
            var graphRefs = graphRelation.sourceRefs().isEmpty() ? supportingRefs : graphRelation.sourceRefs();
            for (var ref : graphRefs) {
                citations.add(new MemoryEvidenceCitation(
                    anchor.memoryAnchor(),
                    firstText(anchor.factFingerprint(), firstOrNull(graphRelation.factFingerprints())),
                    ref,
                    MemoryEvidenceRole.GRAPH_RELATION
                ));
            }
        }
        for (var audit : conflictAudit) {
            var refs = audit.sourceRefs().isEmpty() ? supportingRefs : audit.sourceRefs();
            for (var ref : refs) {
                citations.add(new MemoryEvidenceCitation(
                    anchor.memoryAnchor(),
                    anchor.factFingerprint(),
                    ref,
                    audit.role()
                ));
            }
        }
        return List.copyOf(citations);
    }

    private String graphExplanation(
        MemoryEvidenceAnchor anchor,
        List<String> relationPath,
        Double relationConfidence,
        List<String> sourceMemoryIds,
        String graphEvidenceSummary
    ) {
        var parts = new ArrayList<String>();
        parts.add("Graph relation expands " + anchor.memoryAnchor());
        if (!relationPath.isEmpty()) {
            parts.add("path=" + String.join(" -> ", relationPath));
        }
        if (relationConfidence != null) {
            parts.add("confidence=" + String.format(Locale.ROOT, "%.2f", relationConfidence));
        }
        if (!sourceMemoryIds.isEmpty()) {
            parts.add("sourceMemoryIds=" + String.join(",", sourceMemoryIds));
        }
        if (hasText(graphEvidenceSummary)) {
            parts.add("evidence=" + graphEvidenceSummary);
        }
        return String.join(" | ", parts);
    }

    private int estimateTokens(MemoryEvidenceExpansionItem item) {
        var tokens = 8;
        tokens += estimateText(item.title());
        tokens += estimateText(item.fullContent());
        for (var summary : item.evidenceSummaries()) {
            tokens += estimateText(summary);
        }
        for (var ref : item.sourceRefs()) {
            tokens += estimateText(ref);
        }
        for (var ref : item.mergedSourceRefs()) {
            tokens += estimateText(ref);
        }
        for (var hint : item.identityHints().asMap().values()) {
            tokens += estimateText(hint);
        }
        if (item.graphRelation() != null) {
            tokens += estimateText(String.join(" ", item.graphRelation().relationPath()));
            tokens += estimateText(item.graphRelation().graphEvidenceSummary());
            tokens += estimateText(item.graphRelation().explanation());
        }
        for (var audit : item.conflictAudit()) {
            tokens += estimateText(audit.summary());
        }
        tokens += estimateText(item.lowConfidenceReason());
        return tokens;
    }

    private int estimateText(String text) {
        if (!hasText(text)) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    private String sanitize(String value) {
        if (!hasText(value)) {
            return null;
        }
        var sanitized = normalizeWhitespace(value);
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1$2[REDACTED]");
        return sanitized;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private String truncateToTokens(String value, int maxTokens) {
        if (!hasText(value)) {
            return null;
        }
        if (maxTokens <= 0) {
            return null;
        }
        var maxCharacters = Math.max(1, maxTokens * 4);
        if (value.length() <= maxCharacters) {
            return value;
        }
        if (maxCharacters <= 3) {
            return value.substring(0, maxCharacters);
        }
        return value.substring(0, maxCharacters - 3) + "...";
    }

    private void collectEvidenceLedgerSummaries(LinkedHashSet<String> target, Object value) {
        if (value instanceof Collection<?> collection) {
            for (var item : collection) {
                if (item instanceof Map<?, ?> map) {
                    collectText(target, textValue(map, "summary"));
                }
            }
        }
    }

    private void collectEvidenceLedgerSourceRefs(LinkedHashSet<String> target, Object value) {
        if (value instanceof Collection<?> collection) {
            for (var item : collection) {
                if (item instanceof Map<?, ?> map) {
                    collectText(target, metadataValue(map, "sourceRef"));
                }
            }
        }
    }

    private void collectText(LinkedHashSet<String> target, Object value) {
        for (var item : textList(value)) {
            if (hasText(item)) {
                target.add(item);
            }
        }
    }

    private List<String> textList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .filter(item -> item != null && hasText(item.toString()))
                .map(item -> normalizeWhitespace(item.toString()))
                .toList();
        }
        var text = normalizeWhitespace(value.toString());
        if (!hasText(text)) {
            return List.of();
        }
        if (text.contains("->")) {
            return splitText(text, "->");
        }
        if (text.contains(",")) {
            return splitText(text, ",");
        }
        return List.of(text);
    }

    private List<String> splitText(String text, String separator) {
        var values = new ArrayList<String>();
        for (var part : text.split(Pattern.quote(separator))) {
            var normalized = normalizeWhitespace(part);
            if (hasText(normalized)) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private Object metadataValue(Map<?, ?> metadata, String alias) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        var normalizedAlias = normalizeKey(alias);
        for (var entry : metadata.entrySet()) {
            if (entry.getKey() != null && normalizeKey(entry.getKey().toString()).equals(normalizedAlias)) {
                return entry.getValue();
            }
        }
        var identityHints = directValue(metadata, "identityHints");
        if (identityHints instanceof Map<?, ?> hints) {
            for (var entry : hints.entrySet()) {
                if (entry.getKey() != null && normalizeKey(entry.getKey().toString()).equals(normalizedAlias)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private Object directValue(Map<?, ?> metadata, String key) {
        for (var entry : metadata.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toString().equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String textValue(Map<?, ?> metadata, String... aliases) {
        for (var alias : aliases) {
            var value = metadataValue(metadata, alias);
            if (value != null && hasText(value.toString())) {
                return normalizeWhitespace(value.toString());
            }
        }
        return null;
    }

    private Integer integerValue(Map<String, Object> metadata, String... aliases) {
        for (var alias : aliases) {
            var value = metadataValue(metadata, alias);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null && hasText(value.toString())) {
                try {
                    return Integer.parseInt(value.toString());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Double numberValue(Map<String, Object> metadata, String... aliases) {
        for (var alias : aliases) {
            var value = metadataValue(metadata, alias);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value != null && hasText(value.toString())) {
                try {
                    return Double.parseDouble(value.toString());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private int collectionSize(Object value) {
        return value instanceof Collection<?> collection ? collection.size() : 0;
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private String firstText(String... values) {
        for (var value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
