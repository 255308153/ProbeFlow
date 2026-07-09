package com.probeflow.testagent.rerank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class CrossEncoderRerankService {

    private final CrossEncoderRerankProvider provider;
    private final DeterministicRerankEngine fallback;
    private final RerankCandidateContractService contractService;

    public CrossEncoderRerankService(CrossEncoderRerankProvider provider) {
        this(provider, new DeterministicRerankEngine(), new RerankCandidateContractService());
    }

    CrossEncoderRerankService(
        CrossEncoderRerankProvider provider,
        DeterministicRerankEngine fallback,
        RerankCandidateContractService contractService
    ) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
        this.contractService = Objects.requireNonNull(contractService, "contractService must not be null");
    }

    public CrossEncoderRerankResult rerank(CrossEncoderRerankRequest request) {
        var safeRequest = request == null ? new CrossEncoderRerankRequest(null, null, null) : request;
        var candidates = safeRequest.candidates();
        if (candidates.isEmpty()) {
            return new CrossEncoderRerankResult(new RerankOutput(List.of()), false, false, null);
        }

        try {
            var providerRequest = providerRequest(safeRequest.query(), candidates);
            var response = provider.rerank(providerRequest);
            var output = applyProviderResponse(candidates, response);
            return new CrossEncoderRerankResult(output, true, false, null);
        } catch (CrossEncoderRerankProviderException exception) {
            return fallback(candidates, exception.type());
        } catch (IllegalArgumentException exception) {
            return fallback(candidates, classifyValidationFailure(exception));
        } catch (RuntimeException exception) {
            return fallback(candidates, CrossEncoderRerankErrorType.REMOTE_ERROR);
        }
    }

    private CrossEncoderRerankProviderRequest providerRequest(
        String query,
        List<RerankCandidate> candidates
    ) {
        var providerCandidates = candidates.stream()
            .map(candidate -> new CrossEncoderRerankProviderCandidate(
                candidate.candidateIdentity().stableKey(),
                candidateText(candidate),
                safeMetadata(candidate)
            ))
            .toList();
        return new CrossEncoderRerankProviderRequest(query, providerCandidates);
    }

    private RerankOutput applyProviderResponse(
        List<RerankCandidate> candidates,
        CrossEncoderRerankProviderResponse response
    ) {
        if (response == null) {
            throw new IllegalArgumentException("invalid response");
        }
        var candidatesByKey = new LinkedHashMap<String, RerankCandidate>();
        for (var candidate : candidates) {
            candidatesByKey.put(candidate.candidateIdentity().stableKey(), candidate);
        }

        var seen = new LinkedHashMap<String, CrossEncoderRerankProviderResult>();
        var results = response.results();
        for (var i = 0; i < results.size(); i++) {
            var result = results.get(i);
            validateResult(result, candidatesByKey, seen);
            seen.put(result.candidateId(), result);
        }
        if (seen.size() < candidatesByKey.size()) {
            throw new IllegalArgumentException("partial result");
        }

        var assignments = new ArrayList<RerankScoreAssignment>();
        seen.values().stream()
            .sorted(providerOrder(candidatesByKey))
            .forEach(result -> assignments.add(new RerankScoreAssignment(
                candidatesByKey.get(result.candidateId()).candidateIdentity(),
                roundScore(clamp(result.modelScore())),
                reasons(result),
                List.of()
            )));

        return contractService.recordOutput(candidates, assignments);
    }

    private void validateResult(
        CrossEncoderRerankProviderResult result,
        Map<String, RerankCandidate> candidatesByKey,
        Map<String, CrossEncoderRerankProviderResult> seen
    ) {
        if (result == null || !hasText(result.candidateId())) {
            throw new IllegalArgumentException("invalid response");
        }
        if (!candidatesByKey.containsKey(result.candidateId())) {
            throw new IllegalArgumentException("unknown candidate id");
        }
        if (seen.containsKey(result.candidateId())) {
            throw new IllegalArgumentException("duplicate candidate id");
        }
        if (result.modelScore() == null || !Double.isFinite(result.modelScore())) {
            throw new IllegalArgumentException("missing score");
        }
    }

    private Comparator<CrossEncoderRerankProviderResult> providerOrder(
        Map<String, RerankCandidate> candidatesByKey
    ) {
        return Comparator
            .comparingInt((CrossEncoderRerankProviderResult result) -> effectiveRank(result))
            .thenComparing(Comparator.comparingDouble(CrossEncoderRerankProviderResult::modelScore).reversed())
            .thenComparingInt(result -> beforeRank(candidatesByKey.get(result.candidateId())))
            .thenComparing(CrossEncoderRerankProviderResult::candidateId);
    }

    private int effectiveRank(CrossEncoderRerankProviderResult result) {
        return result.rank() == null || result.rank() <= 0 ? Integer.MAX_VALUE : result.rank();
    }

    private int beforeRank(RerankCandidate candidate) {
        return candidate.beforeRank() > 0 ? candidate.beforeRank() : Integer.MAX_VALUE;
    }

    private List<String> reasons(CrossEncoderRerankProviderResult result) {
        var reasons = new ArrayList<String>();
        reasons.add("cross-encoder-model-score");
        reasons.add("cross-encoder-rank:" + effectiveRank(result));
        if (hasText(result.reason())) {
            reasons.add(result.reason());
        }
        return List.copyOf(reasons);
    }

    private CrossEncoderRerankResult fallback(
        List<RerankCandidate> candidates,
        CrossEncoderRerankErrorType type
    ) {
        return new CrossEncoderRerankResult(
            fallback.rerank(candidates),
            false,
            true,
            new CrossEncoderRerankError(type, diagnostic(type))
        );
    }

    private CrossEncoderRerankErrorType classifyValidationFailure(IllegalArgumentException exception) {
        var message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("partial result")) {
            return CrossEncoderRerankErrorType.PARTIAL_RESULT;
        }
        if (message.contains("missing score")) {
            return CrossEncoderRerankErrorType.MISSING_SCORE;
        }
        return CrossEncoderRerankErrorType.INVALID_RESPONSE;
    }

    private String diagnostic(CrossEncoderRerankErrorType type) {
        return switch (type) {
            case TIMEOUT -> "Cross Encoder provider failed with TIMEOUT; deterministic fallback used.";
            case REMOTE_ERROR -> "Cross Encoder provider failed with REMOTE_ERROR; deterministic fallback used.";
            case INVALID_RESPONSE -> "Cross Encoder provider returned an invalid response with unknown candidate id; deterministic fallback used.";
            case MISSING_SCORE -> "Cross Encoder provider returned a result without model score; deterministic fallback used.";
            case PARTIAL_RESULT -> "Cross Encoder provider returned a partial result; deterministic fallback used.";
        };
    }

    private String candidateText(RerankCandidate candidate) {
        if (hasText(candidate.content())) {
            return candidate.content().trim();
        }
        if (hasText(candidate.title())) {
            return candidate.title().trim();
        }
        return candidate.candidateIdentity().stableKey();
    }

    private Map<String, Object> safeMetadata(RerankCandidate candidate) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("corpusType", candidate.corpusType().name());
        if (hasText(candidate.sourceIdentity().sourceType())) {
            metadata.put("sourceType", candidate.sourceIdentity().sourceType());
        }
        metadata.put("beforeRank", candidate.beforeRank());
        metadata.put("fusedScore", candidate.fusedScore());
        metadata.put("matchedRoutes", candidate.routeEvidence().matchedRoutes().stream()
            .map(RerankMatchedRoute::routeName)
            .toList());
        metadata.put("queryVariants", candidate.routeEvidence().queryVariants());
        return Map.copyOf(metadata);
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double roundScore(double score) {
        return Math.round(score * 10_000.0d) / 10_000.0d;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
