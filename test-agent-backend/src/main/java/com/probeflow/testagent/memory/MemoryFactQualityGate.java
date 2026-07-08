package com.probeflow.testagent.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MemoryFactQualityGate {

    private static final float MIN_CONFIDENCE = 0.55f;
    private static final String MASKED_VALUE_PATTERN = "(?:\\S*)?(?:\\*\\*\\*masked\\*\\*\\*|\\[redacted[^\\]]*\\]|<redacted[^>]*>)(?:\\S*)?";
    private static final Pattern SANITIZED_SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
        "\\b(?:authorization|cookie|password|passwd|secret|api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|token)\\s*(?::|=|bearer\\s+)?\\s*"
            + MASKED_VALUE_PATTERN
    );
    private static final Pattern SANITIZED_BEARER_PATTERN = Pattern.compile("\\bbearer\\s+" + MASKED_VALUE_PATTERN);
    private static final Pattern RAW_HEADER_PATTERN = Pattern.compile("\\b(?:authorization|cookie)\\s*[:=]\\s*\\S+");
    private static final Pattern RAW_BEARER_PATTERN = Pattern.compile("\\bbearer\\s+[a-z0-9._~+/=-]{6,}");
    private static final Pattern RAW_SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
        "\\b(?:password|passwd|secret|api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|token)\\s*[:=]\\s*\\S+"
    );

    public MemoryFactQualityDecision evaluate(MemoryCandidateRequest request, MemoryFact fact) {
        var combined = combinedText(request, fact);
        if (!StringUtils.hasText(request.summary()) && !StringUtils.hasText(request.content()) && !StringUtils.hasText(request.rawEvidence())) {
            return MemoryFactQualityDecision.rejected("empty-candidate");
        }
        if (request.confidence() < MIN_CONFIDENCE) {
            return MemoryFactQualityDecision.rejected("low-confidence");
        }
        if (containsAny(combined, List.of("one-off", "single run", "temporary", "scratch note"))) {
            return MemoryFactQualityDecision.rejected("one-off-noise");
        }
        if (containsAny(combined, List.of("task-local only", "for this task only", "do not reuse"))) {
            return MemoryFactQualityDecision.rejected("task-local-only");
        }
        if (containsSensitiveContent(combined)) {
            return MemoryFactQualityDecision.rejected("sensitive-content");
        }
        if (missingEvidence(request, fact)) {
            return MemoryFactQualityDecision.rejected("missing-evidence");
        }
        if (tooGeneric(fact)) {
            return MemoryFactQualityDecision.rejected("too-generic");
        }
        if (noReusableFact(request, fact)) {
            return MemoryFactQualityDecision.rejected("no-reusable-fact");
        }
        return MemoryFactQualityDecision.accepted();
    }

    private boolean missingEvidence(MemoryCandidateRequest request, MemoryFact fact) {
        return !StringUtils.hasText(request.sourceRef())
            && !StringUtils.hasText(request.rawEvidence());
    }

    private boolean tooGeneric(MemoryFact fact) {
        var summary = normalize(fact.summary());
        var content = normalize(fact.content());
        var words = (summary + " " + content).trim().split("\\s+");
        return words.length <= 5
            || List.of(
                "remember this",
                "pay attention",
                "be careful",
                "fix the issue",
                "there was a failure",
                "this is important"
            ).contains(summary);
    }

    private boolean noReusableFact(MemoryCandidateRequest request, MemoryFact fact) {
        var combined = combinedText(request, fact);
        return request.sourceType() == MemorySourceType.TASK_STATE
            && fact.identityHints().isEmpty()
            && !containsAny(combined, List.of("must", "requires", "should", "pattern", "precondition", "preference", "policy"));
    }

    private boolean containsSensitiveContent(String combined) {
        var withoutSanitizedSecrets = SANITIZED_BEARER_PATTERN
            .matcher(SANITIZED_SECRET_ASSIGNMENT_PATTERN.matcher(combined).replaceAll(""))
            .replaceAll("");
        return RAW_HEADER_PATTERN.matcher(withoutSanitizedSecrets).find()
            || RAW_BEARER_PATTERN.matcher(withoutSanitizedSecrets).find()
            || RAW_SECRET_ASSIGNMENT_PATTERN.matcher(withoutSanitizedSecrets).find();
    }

    private String combinedText(MemoryCandidateRequest request, MemoryFact fact) {
        return String.join(
            " ",
            List.of(
                normalize(request.summary()),
                normalize(request.content()),
                normalize(request.rawEvidence()),
                normalize(String.join(" ", request.tags())),
                normalize(fact.summary()),
                normalize(fact.content()),
                normalize(String.valueOf(request.metadata()))
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

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
