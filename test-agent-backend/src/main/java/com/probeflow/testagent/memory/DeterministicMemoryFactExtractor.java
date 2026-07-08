package com.probeflow.testagent.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(
    prefix = "probeflow.memory.fact-extractor",
    name = "mode",
    havingValue = "deterministic",
    matchIfMissing = true
)
public class DeterministicMemoryFactExtractor implements MemoryFactExtractor {

    private static final int SUMMARY_LIMIT = 160;
    private static final int CONTENT_LIMIT = 280;

    @Override
    public MemoryFact extract(MemoryCandidateRequest request) {
        var factType = classify(request);
        var summary = compactSummary(request);
        var content = compactContent(request);
        var fullContent = fullContent(request);
        var identityHints = identityHints(request);
        var tags = tags(request, factType, identityHints);
        var evidence = evidence(request, summary, fullContent, identityHints);
        var confidence = request.confidence();
        var importance = importanceOf(factType, confidence, tags, identityHints);
        var reuseScore = reuseScoreOf(factType, confidence, identityHints, evidence);
        var applicability = applicability(request, identityHints);
        var trigger = trigger(request, factType);
        var fingerprint = fingerprint(factType, summary, content, identityHints);

        return new MemoryFact(
            factType,
            summary,
            content,
            fullContent,
            applicability,
            trigger,
            tags,
            identityHints,
            evidence,
            confidence,
            importance,
            reuseScore,
            MemoryFactQualityStatus.ACCEPTED,
            null,
            fingerprint
        );
    }

    private MemoryFactType classify(MemoryCandidateRequest request) {
        var combined = combinedText(request);
        if (containsTag(request, List.of("dependency-order", "dependency-order-failure"))
            || containsAny(combined, List.of("failureclassification=dependency_order_failure", "failureclassification=dependency-order-failure"))) {
            return MemoryFactType.SUITE_DEPENDENCY_FACT;
        }
        if (containsTag(request, List.of("variable-extraction", "variable-resolution", "variable-extraction-failure", "variable-resolution-failure"))
            || containsAny(combined, List.of("failureclassification=variable_extraction_failure", "failureclassification=variable_resolution_failure"))) {
            return MemoryFactType.VARIABLE_EXTRACTION_FACT;
        }
        if (containsTag(request, List.of("business-precondition", "business-precondition-failure"))
            || containsAny(combined, List.of("failureclassification=business_precondition_failure", "failureclassification=business-precondition-failure"))) {
            return MemoryFactType.BUSINESS_PRECONDITION_FACT;
        }
        if (request.sourceType() == MemorySourceType.USER_FEEDBACK
            || containsAny(combined, List.of("prefer", "preference", "terse", "compact", "concise"))) {
            return MemoryFactType.PREFERENCE;
        }
        if (containsAny(combined, List.of("policy", "planner", "validator", "approval", "whitelist"))) {
            return MemoryFactType.POLICY_LEARNING;
        }
        if (containsAny(combined, List.of("dependency", "upstream", "downstream", "producer", "consumer"))) {
            return MemoryFactType.SUITE_DEPENDENCY_FACT;
        }
        if (containsAny(combined, List.of("variable", "extract", "jsonpath", "writeback"))) {
            return MemoryFactType.VARIABLE_EXTRACTION_FACT;
        }
        if (containsAny(combined, List.of("precondition", "prerequisite", "bootstrap", "must exist before"))) {
            return MemoryFactType.BUSINESS_PRECONDITION_FACT;
        }
        if (request.sourceType() == MemorySourceType.OBSERVATION
            || request.sourceType() == MemorySourceType.EXECUTION_RESULT
            || containsAny(combined, List.of("401", "403", "404", "500", "timeout", "retry", "failed", "failure", "error", "missing", "rejected"))) {
            return MemoryFactType.FAILURE_PATTERN;
        }
        if (containsAny(combined, List.of("assert", "checklist", "setup", "fixture", "test"))) {
            return MemoryFactType.TESTING_PATTERN;
        }
        return MemoryFactType.PROJECT_KNOWLEDGE;
    }

    private List<String> tags(MemoryCandidateRequest request, MemoryFactType factType, Map<String, Object> identityHints) {
        var tags = new LinkedHashSet<String>();
        tags.addAll(request.tags());
        tags.add(factType.metadataValue());

        var combined = combinedText(request);
        addTagWhen(tags, combined.contains("auth"), "auth");
        addTagWhen(tags, combined.contains("tenant"), "tenant");
        addTagWhen(tags, combined.contains("payment"), "payment");
        addTagWhen(tags, combined.contains("order"), "order");
        addTagWhen(tags, combined.contains("retry"), "retry");
        addTagWhen(tags, combined.contains("timeout"), "timeout");
        addTagWhen(tags, combined.contains("assert"), "assertion");

        addMetadataTag(tags, identityHints.get("module"));
        addMetadataTag(tags, identityHints.get("errorCode"));
        return tags.stream().sorted().toList();
    }

    private Map<String, Object> identityHints(MemoryCandidateRequest request) {
        var hints = new TreeMap<String, Object>();
        for (var key : List.of(
            "systemName",
            "module",
            "apiPath",
            "httpMethod",
            "errorCode",
            "businessEntity",
            "failureClassification",
            "classification",
            "policyReason",
            "toolName",
            "suiteId",
            "caseId",
            "executionId",
            "rootStepId",
            "stepId"
        )) {
            var value = request.metadata().get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                hints.put(key, value);
            }
        }
        return new LinkedHashMap<>(hints);
    }

    private List<MemoryFactEvidence> evidence(
        MemoryCandidateRequest request,
        String summary,
        String fullContent,
        Map<String, Object> identityHints
    ) {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.putAll(identityHints);
        if (request.metadata().containsKey("sourceRef")) {
            attributes.put("sourceRef", request.metadata().get("sourceRef"));
        }
        return List.of(new MemoryFactEvidence(
            request.sourceType(),
            request.sourceRef(),
            request.taskId(),
            StringUtils.hasText(summary) ? summary : request.sourceRef(),
            limit(collapseWhitespace(fullContent), CONTENT_LIMIT),
            attributes
        ));
    }

    private float importanceOf(
        MemoryFactType factType,
        float confidence,
        List<String> tags,
        Map<String, Object> identityHints
    ) {
        var base = switch (factType) {
            case FAILURE_PATTERN, POLICY_LEARNING, BUSINESS_PRECONDITION_FACT -> 0.72f;
            case TESTING_PATTERN, SUITE_DEPENDENCY_FACT, VARIABLE_EXTRACTION_FACT -> 0.68f;
            case PROJECT_KNOWLEDGE -> 0.62f;
            case PREFERENCE -> 0.58f;
        };
        var tagBonus = Math.min(0.10f, tags.size() * 0.02f);
        var identityBonus = identityHints.containsKey("errorCode") || identityHints.containsKey("apiPath") ? 0.05f : 0.0f;
        return clamp(base + (confidence - 0.5f) * 0.30f + tagBonus + identityBonus, 0.50f, 0.95f);
    }

    private float reuseScoreOf(
        MemoryFactType factType,
        float confidence,
        Map<String, Object> identityHints,
        List<MemoryFactEvidence> evidence
    ) {
        var base = factType == MemoryFactType.PREFERENCE ? 0.58f : 0.64f;
        var identityBonus = Math.min(0.12f, identityHints.size() * 0.02f);
        var evidenceBonus = evidence.isEmpty() ? 0.0f : 0.06f;
        return clamp(base + (confidence - 0.5f) * 0.25f + identityBonus + evidenceBonus, 0.35f, 0.95f);
    }

    private String applicability(MemoryCandidateRequest request, Map<String, Object> identityHints) {
        var parts = new ArrayList<String>();
        appendPart(parts, "system", identityHints.get("systemName"));
        appendPart(parts, "module", identityHints.get("module"));
        appendPart(parts, "api", identityHints.get("apiPath"));
        appendPart(parts, "error", identityHints.get("errorCode"));
        if (parts.isEmpty() && StringUtils.hasText(request.taskId())) {
            parts.add("cross-task reusable evidence from " + request.taskId());
        }
        return parts.isEmpty() ? "cross-task reusable project context" : String.join("; ", parts);
    }

    private String trigger(MemoryCandidateRequest request, MemoryFactType factType) {
        if (request.metadata().containsKey("errorCode")) {
            return "when " + request.metadata().get("errorCode") + " or similar symptoms appear";
        }
        return switch (factType) {
            case PREFERENCE -> "when generating user-facing test output";
            case TESTING_PATTERN -> "when generating or reviewing API tests";
            case PROJECT_KNOWLEDGE -> "when reasoning about matching project scope";
            default -> "when similar execution evidence appears";
        };
    }

    private String fingerprint(
        MemoryFactType factType,
        String summary,
        String content,
        Map<String, Object> identityHints
    ) {
        var material = factType.metadataValue()
            + "|" + normalizeFingerprintText(summary)
            + "|" + normalizeFingerprintText(content)
            + "|" + identityHints;
        return "fact:" + sha256(material).substring(0, 24);
    }

    private String compactSummary(MemoryCandidateRequest request) {
        var base = StringUtils.hasText(request.summary()) ? request.summary().trim() : request.content().trim();
        return limit(collapseWhitespace(base), SUMMARY_LIMIT);
    }

    private String compactContent(MemoryCandidateRequest request) {
        return limit(collapseWhitespace(request.content()), CONTENT_LIMIT);
    }

    private String fullContent(MemoryCandidateRequest request) {
        if (StringUtils.hasText(request.rawEvidence())) {
            return collapseWhitespace(request.rawEvidence());
        }
        return collapseWhitespace(request.content());
    }

    private String combinedText(MemoryCandidateRequest request) {
        return String.join(
            " ",
            List.of(
                safeLower(request.summary()),
                safeLower(request.content()),
                safeLower(request.rawEvidence()),
                String.join(" ", request.tags())
            )
        );
    }

    private boolean containsAny(String input, List<String> tokens) {
        for (var token : tokens) {
            if (input.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsTag(MemoryCandidateRequest request, List<String> tags) {
        for (var tag : request.tags()) {
            if (tags.contains(tag.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void addTagWhen(LinkedHashSet<String> tags, boolean condition, String tag) {
        if (condition) {
            tags.add(tag);
        }
    }

    private void addMetadataTag(LinkedHashSet<String> tags, Object value) {
        if (value == null) {
            return;
        }
        var normalized = collapseWhitespace(value.toString()).toLowerCase(Locale.ROOT);
        if (StringUtils.hasText(normalized)) {
            tags.add(normalized);
        }
    }

    private void appendPart(List<String> parts, String label, Object value) {
        if (value != null && StringUtils.hasText(value.toString())) {
            parts.add(label + "=" + value.toString().trim());
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeFingerprintText(String value) {
        return collapseWhitespace(value).toLowerCase(Locale.ROOT);
    }

    private String collapseWhitespace(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3).trim() + "...";
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
