package com.probeflow.testagent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.llm.LlmApplicationService;
import com.probeflow.testagent.llm.LlmCallRequest;
import com.probeflow.testagent.llm.LlmExecutionOptions;
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
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "probeflow.memory.fact-extractor", name = "mode", havingValue = "llm-assisted")
public class LlmAssistedMemoryFactExtractor implements MemoryFactExtractor {

    private static final int SUMMARY_LIMIT = 160;
    private static final int CONTENT_LIMIT = 280;
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)\\bauthorization\\b\\s*[:=]\\s*[^\\r\\n,;]+");
    private static final Pattern COOKIE = Pattern.compile("(?i)\\b(set-cookie|cookie)\\b\\s*[:=]\\s*[^\\r\\n,;]+");
    private static final Pattern API_KEY = Pattern.compile("(?i)\\b(api[-_ ]?key|x-api-key|apikey)\\b\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern TOKEN = Pattern.compile("(?i)\\b(access_token|refresh_token|token)\\b\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern PASSWORD = Pattern.compile("(?i)\\b(password|passwd|secret)\\b\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");

    private final LlmApplicationService llm;
    private final ObjectMapper objectMapper;
    private final String provider;
    private final String model;
    private final String templateId;

    public LlmAssistedMemoryFactExtractor(
        LlmApplicationService llm,
        ObjectMapper objectMapper,
        @Value("${probeflow.memory.fact-extractor.provider:fake}") String provider,
        @Value("${probeflow.memory.fact-extractor.model:fake-model}") String model,
        @Value("${probeflow.memory.fact-extractor.template-id:v5.memory-fact-extraction.v1}") String templateId
    ) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.provider = clean(provider, "fake");
        this.model = clean(model, "fake-model");
        this.templateId = clean(templateId, "v5.memory-fact-extraction.v1");
    }

    @Override
    public MemoryFact extract(MemoryCandidateRequest request) {
        var result = llm.call(LlmCallRequest.forTemplate(
            request.taskId(),
            null,
            templateId,
            Map.of("candidateJson", candidateJson(request)),
            new LlmExecutionOptions(provider, model, 0.0d, 1_200, 3_000, 0)
        ));
        if (!result.succeeded()) {
            throw new IllegalArgumentException("llm-fact-extraction-failed: " + safeReason(result.callResult().errorMessage()));
        }
        var responseText = result.callResult().response().text();
        var schema = parse(responseText);
        validate(schema);
        return toMemoryFact(request, schema);
    }

    private MemoryFact toMemoryFact(MemoryCandidateRequest request, LlmFactSchema schema) {
        var factType = factType(schema.factType());
        var summary = limit(sanitize(schema.summary()), SUMMARY_LIMIT);
        var content = limit(sanitize(requiredText("content", schema.content())), CONTENT_LIMIT);
        var fullContent = sanitize(firstText(schema.fullContent(), schema.content()));
        var applicability = sanitize(firstText(schema.applicability(), "cross-task reusable project context"));
        var trigger = sanitize(firstText(schema.trigger(), "when similar execution evidence appears"));
        var identityHints = sanitizedMap(schema.identityHints());
        var tags = tags(schema.tags(), factType, identityHints);
        var evidenceEntries = evidenceEntries(request, schema.evidence());
        var confidence = floatValue("confidence", schema.confidence(), request.confidence());
        var importance = floatValue("importance", schema.importance(), 0.70f);
        var reuseScore = floatValue("reuseScore", schema.reuseScore(), 0.70f);
        var fingerprint = StringUtils.hasText(schema.fingerprint())
            ? sanitize(schema.fingerprint())
            : fingerprint(factType, summary, content, identityHints);

        return new MemoryFact(
            factType,
            summary,
            content,
            fullContent,
            applicability,
            trigger,
            tags,
            identityHints,
            evidenceEntries,
            confidence,
            importance,
            reuseScore,
            MemoryFactQualityStatus.ACCEPTED,
            null,
            fingerprint
        );
    }

    private LlmFactSchema parse(String responseText) {
        try {
            return objectMapper.readValue(responseText, LlmFactSchema.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("llm-fact-schema-invalid: invalid-json");
        }
    }

    private void validate(LlmFactSchema schema) {
        if (schema == null) {
            throw new IllegalArgumentException("llm-fact-schema-invalid: empty-response");
        }
        if (!StringUtils.hasText(schema.factType())) {
            throw new IllegalArgumentException("llm-fact-schema-invalid: missing-fact-type");
        }
        factType(schema.factType());
        if (!StringUtils.hasText(schema.summary())) {
            throw new IllegalArgumentException("llm-fact-schema-invalid: missing-summary");
        }
        if (schema.evidence() == null || schema.evidence().isEmpty()) {
            throw new IllegalArgumentException("llm-fact-schema-invalid: missing-evidence");
        }
        for (var evidence : schema.evidence()) {
            if (evidence == null || (!StringUtils.hasText(evidence.summary()) && !StringUtils.hasText(evidence.sanitizedEvidence()))) {
                throw new IllegalArgumentException("llm-fact-schema-invalid: missing-evidence");
            }
        }
    }

    private MemoryFactType factType(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return MemoryFactType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("llm-fact-schema-invalid: unsupported-fact-type");
        }
    }

    private List<MemoryFactEvidence> evidenceEntries(MemoryCandidateRequest request, List<LlmEvidenceSchema> evidence) {
        var entries = new ArrayList<MemoryFactEvidence>();
        for (var item : evidence) {
            var sourceType = sourceType(item.sourceType(), request.sourceType());
            var sourceRef = StringUtils.hasText(item.sourceRef()) ? sanitize(item.sourceRef()) : request.sourceRef();
            var taskId = StringUtils.hasText(item.taskId()) ? sanitize(item.taskId()) : request.taskId();
            var summary = sanitize(firstText(item.summary(), request.summary(), sourceRef));
            var sanitizedEvidence = sanitize(firstText(item.sanitizedEvidence(), item.summary(), request.rawEvidence(), request.content()));
            entries.add(new MemoryFactEvidence(
                sourceType,
                sourceRef,
                taskId,
                limit(summary, SUMMARY_LIMIT),
                limit(sanitizedEvidence, CONTENT_LIMIT),
                sanitizedMap(item.attributes())
            ));
        }
        return List.copyOf(entries);
    }

    private MemorySourceType sourceType(String value, MemorySourceType fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return MemorySourceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private Map<String, Object> sanitizedMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        var sorted = new TreeMap<String, Object>();
        input.forEach((key, value) -> {
            if (StringUtils.hasText(key)) {
                sorted.put(key.trim(), sanitizeValue(value));
            }
        });
        return new LinkedHashMap<>(sorted);
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof String stringValue) {
            return sanitize(stringValue);
        }
        if (value instanceof Map<?, ?> mapValue) {
            var nested = new TreeMap<String, Object>();
            mapValue.forEach((key, nestedValue) -> {
                if (key != null && StringUtils.hasText(key.toString())) {
                    nested.put(key.toString().trim(), sanitizeValue(nestedValue));
                }
            });
            return new LinkedHashMap<>(nested);
        }
        if (value instanceof List<?> listValue) {
            return listValue.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }

    private List<String> tags(List<String> schemaTags, MemoryFactType factType, Map<String, Object> identityHints) {
        var tags = new LinkedHashSet<String>();
        tags.add(factType.metadataValue());
        if (schemaTags != null) {
            schemaTags.stream()
                .map(this::sanitize)
                .filter(StringUtils::hasText)
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .forEach(tags::add);
        }
        addTag(tags, identityHints.get("module"));
        addTag(tags, identityHints.get("errorCode"));
        return tags.stream().sorted().toList();
    }

    private void addTag(LinkedHashSet<String> tags, Object raw) {
        if (raw != null && StringUtils.hasText(raw.toString())) {
            tags.add(raw.toString().trim().toLowerCase(Locale.ROOT));
        }
    }

    private String candidateJson(MemoryCandidateRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "summary", safeString(request.summary()),
                "content", safeString(request.content()),
                "sourceType", request.sourceType().name(),
                "sourceRef", safeString(request.sourceRef()),
                "taskId", safeString(request.taskId()),
                "tags", request.tags() == null ? List.of() : request.tags(),
                "confidence", request.confidence(),
                "rawEvidence", safeString(request.rawEvidence()),
                "metadata", request.metadata() == null ? Map.of() : request.metadata()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("llm-fact-extraction-failed: candidate-json");
        }
    }

    private Float floatValue(String field, Float value, Float fallback) {
        var selected = value == null ? fallback : value;
        if (selected == null || selected < 0.0f || selected > 1.0f) {
            throw new IllegalArgumentException("llm-fact-schema-invalid: invalid-" + field);
        }
        return selected;
    }

    private String requiredText(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("llm-fact-schema-invalid: missing-" + field);
        }
        return value;
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        var sanitized = AUTHORIZATION.matcher(value).replaceAll("[REDACTED_AUTH_HEADER]");
        sanitized = COOKIE.matcher(sanitized).replaceAll("[REDACTED_COOKIE]");
        sanitized = API_KEY.matcher(sanitized).replaceAll("[REDACTED_API_KEY]");
        sanitized = TOKEN.matcher(sanitized).replaceAll("[REDACTED_TOKEN]");
        sanitized = PASSWORD.matcher(sanitized).replaceAll("[REDACTED_SECRET]");
        sanitized = BEARER.matcher(sanitized).replaceAll("[REDACTED_BEARER]");
        return sanitized.trim().replaceAll("\\s+", " ");
    }

    private String safeReason(String value) {
        if (!StringUtils.hasText(value)) {
            return "provider-error";
        }
        return sanitize(value).replaceAll("[\\r\\n]+", " ");
    }

    private String firstText(String... values) {
        for (var value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String limit(String value, int maxLength) {
        var safe = value == null ? "" : value;
        if (safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, maxLength - 3).trim() + "...";
    }

    private String fingerprint(MemoryFactType factType, String summary, String content, Map<String, Object> identityHints) {
        var material = factType.metadataValue()
            + "|" + summary.toLowerCase(Locale.ROOT)
            + "|" + content.toLowerCase(Locale.ROOT)
            + "|" + identityHints;
        return "llm-fact:" + sha256(material).substring(0, 24);
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private String clean(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private record LlmFactSchema(
        String factType,
        String summary,
        String content,
        String fullContent,
        String applicability,
        String trigger,
        List<String> tags,
        Map<String, Object> identityHints,
        List<LlmEvidenceSchema> evidence,
        Float confidence,
        Float importance,
        Float reuseScore,
        String fingerprint
    ) {
    }

    private record LlmEvidenceSchema(
        String sourceType,
        String sourceRef,
        String taskId,
        String summary,
        String sanitizedEvidence,
        Map<String, Object> attributes
    ) {
    }
}
