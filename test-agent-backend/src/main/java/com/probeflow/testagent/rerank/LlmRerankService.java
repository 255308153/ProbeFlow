package com.probeflow.testagent.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.llm.LlmErrorType;
import com.probeflow.testagent.llm.LlmProvider;
import com.probeflow.testagent.llm.LlmProviderException;
import com.probeflow.testagent.llm.LlmRequest;
import com.probeflow.testagent.llm.LlmResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public class LlmRerankService {

    private static final String PURPOSE = "V5_5_LLM_RERANK";
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
        "(?i)" + "authori" + "zation" + "\\s*[:=]\\s*[^\\s,;]+(?:\\s+[^\\s,;]+)?"
    );
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_FIELD_PATTERN = Pattern.compile(
        "(?i)(api[-_ ]?key|secret|password|cookie|token|raw[-_ ]?request[-_ ]?body)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final Function<LlmRequest, LlmResponse> generate;
    private final LlmRerankProfile profile;
    private final DeterministicRerankEngine fallback;
    private final RerankCandidateContractService contractService;
    private final ObjectMapper objectMapper;

    public LlmRerankService(LlmProvider provider, LlmRerankProfile profile) {
        this(
            Objects.requireNonNull(provider, "provider must not be null")::generate,
            profile,
            new DeterministicRerankEngine(),
            new RerankCandidateContractService(),
            new ObjectMapper()
        );
    }

    public LlmRerankService(Function<LlmRequest, LlmResponse> generate, LlmRerankProfile profile) {
        this(
            generate,
            profile,
            new DeterministicRerankEngine(),
            new RerankCandidateContractService(),
            new ObjectMapper()
        );
    }

    LlmRerankService(
        Function<LlmRequest, LlmResponse> generate,
        LlmRerankProfile profile,
        DeterministicRerankEngine fallback,
        RerankCandidateContractService contractService,
        ObjectMapper objectMapper
    ) {
        this.generate = Objects.requireNonNull(generate, "generate must not be null");
        this.profile = profile == null ? LlmRerankProfile.disabled() : profile;
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
        this.contractService = Objects.requireNonNull(contractService, "contractService must not be null");
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public LlmRerankResult rerank(LlmRerankRequest request) {
        var safeRequest = request == null ? new LlmRerankRequest(null, null, null) : request;
        var candidates = safeRequest.candidates();
        if (candidates.isEmpty()) {
            return new LlmRerankResult(new RerankOutput(List.of()), false, false, null);
        }
        if (!profile.enabled()) {
            return fallback(candidates, LlmRerankErrorType.DISABLED);
        }

        try {
            var llmRequest = new LlmRequest(
                profile.provider(),
                profile.model(),
                PURPOSE,
                prompt(safeRequest.query(), candidates),
                null,
                null,
                safeMetadata(candidates)
            );
            var response = generate.apply(llmRequest);
            var output = applyResponse(candidates, response == null ? "" : response.text());
            return new LlmRerankResult(output, true, false, null);
        } catch (LlmProviderException exception) {
            return fallback(candidates, providerFailureType(exception.errorType()));
        } catch (ValidationFailure failure) {
            return fallback(candidates, failure.type());
        } catch (RuntimeException exception) {
            return fallback(candidates, LlmRerankErrorType.REMOTE_ERROR);
        }
    }

    private RerankOutput applyResponse(List<RerankCandidate> candidates, String text) {
        var candidatesByKey = new LinkedHashMap<String, RerankCandidate>();
        for (var candidate : candidates) {
            candidatesByKey.put(candidate.candidateIdentity().stableKey(), candidate);
        }

        var root = parseJson(text);
        var results = root.get("results");
        if (results == null || !results.isArray()) {
            throw invalidOutput();
        }

        var seen = new LinkedHashMap<String, LlmRerankModelResult>();
        for (var result : results) {
            var parsed = parseResult(result, candidatesByKey);
            if (seen.containsKey(parsed.candidateId())) {
                throw invalidOutput();
            }
            seen.put(parsed.candidateId(), parsed);
        }
        if (seen.size() != candidatesByKey.size()) {
            throw invalidOutput();
        }

        var assignments = seen.values().stream()
            .sorted(modelOrder(candidatesByKey))
            .map(result -> new RerankScoreAssignment(
                candidatesByKey.get(result.candidateId()).candidateIdentity(),
                roundScore(clamp(result.confidence())),
                reasons(result),
                List.of()
            ))
            .toList();

        return contractService.recordOutput(candidates, assignments);
    }

    private JsonNode parseJson(String text) {
        try {
            return objectMapper.readTree(text == null ? "" : text);
        } catch (Exception exception) {
            throw invalidOutput();
        }
    }

    private LlmRerankModelResult parseResult(JsonNode node, Map<String, RerankCandidate> candidatesByKey) {
        if (node == null || !node.isObject()) {
            throw invalidOutput();
        }
        var candidateId = requiredText(node, "candidateId");
        if (!candidatesByKey.containsKey(candidateId)) {
            throw new ValidationFailure(LlmRerankErrorType.UNKNOWN_CANDIDATE);
        }
        var rank = requiredPositiveRank(node.get("rank"));
        var reason = requiredText(node, "reason");
        var confidence = requiredConfidence(node.get("confidence"));
        validateEvidenceRefs(node.get("evidenceRefs"), candidatesByKey.get(candidateId));
        return new LlmRerankModelResult(candidateId, rank, reason, confidence);
    }

    private void validateEvidenceRefs(JsonNode evidenceRefs, RerankCandidate candidate) {
        if (evidenceRefs == null || evidenceRefs.isNull()) {
            return;
        }
        if (!evidenceRefs.isArray()) {
            throw new ValidationFailure(LlmRerankErrorType.INVALID_EVIDENCE);
        }
        var allowed = allowedEvidenceRefs(candidate);
        for (var evidenceRef : evidenceRefs) {
            if (!evidenceRef.isTextual() || !allowed.contains(evidenceRef.asText())) {
                throw new ValidationFailure(LlmRerankErrorType.INVALID_EVIDENCE);
            }
        }
    }

    private Set<String> allowedEvidenceRefs(RerankCandidate candidate) {
        var refs = new LinkedHashSet<String>();
        for (var route : candidate.routeEvidence().matchedRoutes()) {
            refs.add("route:" + route.routeName());
        }
        for (var queryVariant : candidate.routeEvidence().queryVariants()) {
            refs.add("queryVariant:" + queryVariant);
        }
        return Set.copyOf(refs);
    }

    private Comparator<LlmRerankModelResult> modelOrder(Map<String, RerankCandidate> candidatesByKey) {
        return Comparator
            .comparingInt(LlmRerankModelResult::rank)
            .thenComparing(Comparator.comparingDouble(LlmRerankModelResult::confidence).reversed())
            .thenComparingInt(result -> beforeRank(candidatesByKey.get(result.candidateId())))
            .thenComparing(LlmRerankModelResult::candidateId);
    }

    private List<String> reasons(LlmRerankModelResult result) {
        var reasons = new ArrayList<String>();
        reasons.add("llm-rank:" + result.rank());
        reasons.add("llm-confidence:" + formatScore(result.confidence()));
        reasons.add(result.reason());
        return List.copyOf(reasons);
    }

    private String prompt(String query, List<RerankCandidate> candidates) {
        var builder = new StringBuilder();
        builder.append("You rerank ProbeFlow V5-5 candidates from an existing candidate list.\n")
            .append("Return only JSON with this shape: ")
            .append("{\"results\":[{\"candidateId\":\"...\",\"rank\":1,\"reason\":\"...\",")
            .append("\"confidence\":0.0,\"evidenceRefs\":[\"route:...\"]}]}.\n")
            .append("Rules:\n")
            .append("- Use every input candidate exactly once.\n")
            .append("- Do not create new candidate ids.\n")
            .append("- Do not create new citations.\n")
            .append("- Evidence refs, if any, must be copied from that candidate's allowed evidence refs.\n")
            .append("- Do not recall more data or rely on outside context.\n\n")
            .append("Query: ").append(safeText(query, 400)).append("\n\n")
            .append("Candidates:\n");

        for (var candidate : candidates) {
            builder.append("- candidateId: ").append(candidate.candidateIdentity().stableKey()).append("\n")
                .append("  corpusType: ").append(candidate.corpusType().name()).append("\n")
                .append("  title: ").append(safeText(candidate.title(), 180)).append("\n")
                .append("  summary: ").append(safeText(candidate.content(), profile.maxSummaryChars())).append("\n")
                .append("  sourceType: ").append(safeText(candidate.sourceIdentity().sourceType(), 120)).append("\n")
                .append("  beforeRank: ").append(candidate.beforeRank()).append("\n")
                .append("  fusedScore: ").append(formatScore(candidate.fusedScore())).append("\n")
                .append("  matchedRoutes: ").append(safeList(candidate.routeEvidence().matchedRoutes().stream()
                    .map(RerankMatchedRoute::routeName)
                    .toList())).append("\n")
                .append("  queryVariants: ").append(safeList(candidate.routeEvidence().queryVariants())).append("\n")
                .append("  allowedEvidenceRefs: ").append(safeList(new ArrayList<>(allowedEvidenceRefs(candidate))))
                .append("\n");
        }
        return builder.toString();
    }

    private Map<String, Object> safeMetadata(List<RerankCandidate> candidates) {
        return Map.of(
            "profileId", profile.profileId(),
            "candidateCount", candidates.size()
        );
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(this::hasText)
            .map(value -> safeText(value, 120))
            .toList();
    }

    private String safeText(String value, int maxChars) {
        if (!hasText(value)) {
            return "";
        }
        var redacted = AUTH_HEADER_PATTERN.matcher(value).replaceAll("[redacted]");
        redacted = BEARER_PATTERN.matcher(redacted).replaceAll("[redacted]");
        redacted = SECRET_FIELD_PATTERN.matcher(redacted).replaceAll("[redacted]");
        redacted = redacted.replaceAll("\\s+", " ").trim();
        if (redacted.length() <= maxChars) {
            return redacted;
        }
        return redacted.substring(0, maxChars).trim();
    }

    private String requiredText(JsonNode node, String fieldName) {
        var field = node.get(fieldName);
        if (field == null || !field.isTextual() || !hasText(field.asText())) {
            throw invalidOutput();
        }
        return field.asText().trim();
    }

    private int requiredPositiveRank(JsonNode node) {
        if (node == null || !node.canConvertToInt() || node.asInt() <= 0) {
            throw invalidOutput();
        }
        return node.asInt();
    }

    private double requiredConfidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw invalidOutput();
        }
        var confidence = node.asDouble();
        if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw invalidOutput();
        }
        return confidence;
    }

    private ValidationFailure invalidOutput() {
        return new ValidationFailure(LlmRerankErrorType.INVALID_OUTPUT);
    }

    private LlmRerankResult fallback(List<RerankCandidate> candidates, LlmRerankErrorType type) {
        return new LlmRerankResult(
            fallback.rerank(candidates),
            false,
            true,
            new LlmRerankError(type, diagnostic(type))
        );
    }

    private LlmRerankErrorType providerFailureType(LlmErrorType type) {
        return type == LlmErrorType.TIMEOUT ? LlmRerankErrorType.TIMEOUT : LlmRerankErrorType.REMOTE_ERROR;
    }

    private String diagnostic(LlmRerankErrorType type) {
        return switch (type) {
            case DISABLED -> "LLM rerank disabled; deterministic fallback used.";
            case INVALID_OUTPUT -> "LLM rerank returned invalid structured output; deterministic fallback used.";
            case UNKNOWN_CANDIDATE -> "LLM rerank returned unknown candidate id; deterministic fallback used.";
            case INVALID_EVIDENCE -> "LLM rerank returned unknown evidence reference; deterministic fallback used.";
            case TIMEOUT -> "LLM provider failed with TIMEOUT; deterministic fallback used.";
            case REMOTE_ERROR -> "LLM provider failed with REMOTE_ERROR; deterministic fallback used.";
        };
    }

    private int beforeRank(RerankCandidate candidate) {
        return candidate.beforeRank() > 0 ? candidate.beforeRank() : Integer.MAX_VALUE;
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double roundScore(double score) {
        return Math.round(score * 10_000.0d) / 10_000.0d;
    }

    private String formatScore(double score) {
        return String.format(java.util.Locale.ROOT, "%.4f", score);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record LlmRerankModelResult(
        String candidateId,
        int rank,
        String reason,
        double confidence
    ) {
    }

    private static final class ValidationFailure extends RuntimeException {
        private final LlmRerankErrorType type;

        private ValidationFailure(LlmRerankErrorType type) {
            this.type = type;
        }

        private LlmRerankErrorType type() {
            return type;
        }
    }
}
