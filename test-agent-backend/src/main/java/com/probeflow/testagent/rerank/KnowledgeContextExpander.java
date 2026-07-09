package com.probeflow.testagent.rerank;

import com.probeflow.testagent.knowledge.DocumentType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class KnowledgeContextExpander {

    public KnowledgeExpansionResult expand(KnowledgeExpansionRequest request) {
        var safeRequest = request == null
            ? new KnowledgeExpansionRequest(List.of(), List.of(), 0)
            : request;
        var sourceByChunkId = indexByChunkId(safeRequest.sources());
        var contexts = new ArrayList<KnowledgeExpansionContext>();
        var totalTokens = 0;
        var pruned = false;

        for (var item : safeRequest.rerankedItems()) {
            if (item == null || item.candidate() == null || item.candidate().corpusType() != RerankCorpusType.KNOWLEDGE) {
                continue;
            }
            var anchor = sourceByChunkId.get(anchorChunkId(item.candidate()));
            if (anchor == null || !anchor.usable()) {
                continue;
            }
            var remainingBudget = safeRequest.tokenBudget() <= 0
                ? Integer.MAX_VALUE
                : safeRequest.tokenBudget() - totalTokens;
            if (remainingBudget <= 0) {
                pruned = true;
                continue;
            }
            var context = expandAnchor(anchor, safeRequest.sources(), remainingBudget);
            if (context.sources().isEmpty()) {
                continue;
            }
            pruned = pruned || !context.pruningReasons().isEmpty();
            totalTokens += context.tokenCost();
            contexts.add(context);
        }

        return new KnowledgeExpansionResult(
            contexts,
            safeRequest.tokenBudget(),
            totalTokens,
            pruned
        );
    }

    private KnowledgeExpansionContext expandAnchor(
        KnowledgeExpansionSource anchor,
        List<KnowledgeExpansionSource> allSources,
        int tokenBudget
    ) {
        var reason = reasonFor(anchor.documentType());
        var related = relatedSources(anchor, allSources, reason);
        var selected = new ArrayList<KnowledgeExpansionSource>();
        var pruning = new ArrayList<KnowledgeExpansionPruning>();
        var tokens = 0;

        for (var source : related) {
            if (tokens + source.tokenCost() > tokenBudget) {
                pruning.add(new KnowledgeExpansionPruning(
                    source.chunkId(),
                    KnowledgeExpansionPruningReason.TOKEN_BUDGET_EXCEEDED
                ));
                continue;
            }
            selected.add(source);
            tokens += source.tokenCost();
        }

        if (selected.stream().noneMatch(source -> source.chunkId().equals(anchor.chunkId()))) {
            if (anchor.tokenCost() <= tokenBudget) {
                selected.add(0, anchor);
                tokens += anchor.tokenCost();
            } else {
                pruning.add(new KnowledgeExpansionPruning(
                    anchor.chunkId(),
                    KnowledgeExpansionPruningReason.TOKEN_BUDGET_EXCEEDED
                ));
            }
        }

        return new KnowledgeExpansionContext(
            anchor.chunkId(),
            anchor.parentIdentity(),
            reason,
            anchor.documentRevisionId(),
            tokens,
            selected,
            pruning,
            citations(anchor, selected)
        );
    }

    private List<KnowledgeExpansionSource> relatedSources(
        KnowledgeExpansionSource anchor,
        List<KnowledgeExpansionSource> allSources,
        KnowledgeExpansionReason reason
    ) {
        return allSources.stream()
            .filter(KnowledgeExpansionSource::usable)
            .filter(source -> source.documentType() == anchor.documentType())
            .filter(source -> source.documentRevisionId().equals(anchor.documentRevisionId()))
            .filter(source -> matchesExpansion(anchor, source, reason))
            .sorted(Comparator
                .comparingInt(KnowledgeExpansionSource::chunkOrder)
                .thenComparing(KnowledgeExpansionSource::chunkId))
            .toList();
    }

    private boolean matchesExpansion(
        KnowledgeExpansionSource anchor,
        KnowledgeExpansionSource source,
        KnowledgeExpansionReason reason
    ) {
        return switch (reason) {
            case ERROR_CODE_ENTRY, TEST_SPEC_RULE_GROUP, PARENT_SECTION -> sameParentOrEntry(anchor, source);
            case BUSINESS_FLOW_NEIGHBOR_STEPS -> sameBusinessFlow(anchor, source)
                && Math.abs(source.chunkOrder() - anchor.chunkOrder()) <= 1;
        };
    }

    private boolean sameParentOrEntry(KnowledgeExpansionSource anchor, KnowledgeExpansionSource source) {
        return sameText(anchor.parentIdentity(), source.parentIdentity())
            || (hasText(anchor.entryKey()) && sameText(anchor.entryKey(), source.entryKey()));
    }

    private boolean sameBusinessFlow(KnowledgeExpansionSource anchor, KnowledgeExpansionSource source) {
        return sameParentOrEntry(anchor, source)
            && (!hasText(anchor.businessEntity()) || sameText(anchor.businessEntity(), source.businessEntity()))
            && (!hasText(anchor.flowId()) || sameText(anchor.flowId(), source.flowId()));
    }

    private KnowledgeExpansionReason reasonFor(DocumentType documentType) {
        return switch (documentType) {
            case ERROR_CODE_GUIDE -> KnowledgeExpansionReason.ERROR_CODE_ENTRY;
            case TEST_SPEC -> KnowledgeExpansionReason.TEST_SPEC_RULE_GROUP;
            case BUSINESS_FLOW -> KnowledgeExpansionReason.BUSINESS_FLOW_NEIGHBOR_STEPS;
            case API_NOTE, DOMAIN_RULE, ENV_GUIDE, INCIDENT_POSTMORTEM -> KnowledgeExpansionReason.PARENT_SECTION;
        };
    }

    private List<KnowledgeExpansionCitation> citations(
        KnowledgeExpansionSource anchor,
        List<KnowledgeExpansionSource> selected
    ) {
        return selected.stream()
            .map(source -> new KnowledgeExpansionCitation(
                source.chunkId(),
                source.documentRevisionId(),
                source.sourceRef(),
                source.chunkId().equals(anchor.chunkId())
                    ? KnowledgeExpansionCitationRole.ANCHOR
                    : KnowledgeExpansionCitationRole.EXPANDED_CONTEXT
            ))
            .toList();
    }

    private Map<String, KnowledgeExpansionSource> indexByChunkId(List<KnowledgeExpansionSource> sources) {
        var byChunkId = new LinkedHashMap<String, KnowledgeExpansionSource>();
        for (var source : sources) {
            byChunkId.putIfAbsent(source.chunkId(), source);
        }
        return byChunkId;
    }

    private String anchorChunkId(RerankCandidate candidate) {
        var fromSourceIdentity = candidate.sourceIdentity().sourceIds().get("chunkId");
        if (hasText(fromSourceIdentity)) {
            return fromSourceIdentity;
        }
        return candidate.candidateIdentity().candidateId();
    }

    private boolean sameText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
