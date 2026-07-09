package com.probeflow.testagent.retrieval;

import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.memory.MemoryFactType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class DeterministicQueryRewriteService {

    private static final String DEFAULT_STAGE = "default";

    public QueryRewriteResult rewrite(QueryRewriteRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        var normalized = normalize(request);
        requireNonBlank(normalized.rawQuery(), "rawQuery must not be blank");

        var variants = new ArrayList<QueryVariant>();
        variants.add(variant(
            normalized,
            QueryIntent.RAW_TASK,
            QueryTargetCorpus.ALL,
            normalized.rawQuery(),
            baseFilters(normalized, List.of(), List.of()),
            100,
            "Preserve the original task query as the stable fallback variant."
        ));

        var diagnostics = new ArrayList<String>();
        if (hasLowContext(normalized)) {
            diagnostics.add("low-context: only raw query is available");
            return new QueryRewriteResult(variants, diagnostics);
        }

        switch (normalized.stageProfile()) {
            case "api_analysis" -> addApiAnalysisVariants(variants, normalized);
            case "case_generation" -> addCaseGenerationVariants(variants, normalized);
            case "failure_analysis" -> addFailureAnalysisVariants(variants, normalized);
            case "suite_generation", "suite_recovery" -> addSuiteVariants(variants, normalized);
            case "planner", "planner_policy", "policy" -> addPolicyVariants(variants, normalized);
            default -> addDefaultVariants(variants, normalized);
        }
        return new QueryRewriteResult(variants, diagnostics);
    }

    private void addApiAnalysisVariants(List<QueryVariant> variants, QueryRewriteRequest request) {
        variants.add(variant(
            request,
            QueryIntent.API_STRUCTURE,
            QueryTargetCorpus.KNOWLEDGE,
            joinNonBlank(request.httpMethod(), request.apiPath(), request.moduleName(), "api note structure"),
            baseFilters(request, List.of(DocumentType.API_NOTE, DocumentType.BUSINESS_FLOW), List.of()),
            90,
            "API analysis needs interface notes, business flow, and structure metadata."
        ));
        variants.add(variant(
            request,
            QueryIntent.BUSINESS_RULE,
            QueryTargetCorpus.KNOWLEDGE,
            joinNonBlank(request.businessEntity(), request.moduleName(), "business flow domain rule"),
            baseFilters(request, List.of(DocumentType.BUSINESS_FLOW, DocumentType.DOMAIN_RULE), List.of()),
            80,
            "API structure is clarified by business flow and domain rules."
        ));
    }

    private void addCaseGenerationVariants(List<QueryVariant> variants, QueryRewriteRequest request) {
        variants.add(variant(
            request,
            QueryIntent.TEST_STRATEGY,
            QueryTargetCorpus.ALL,
            joinNonBlank(request.businessEntity(), request.apiPath(), "test spec boundary testing pattern"),
            baseFilters(
                request,
                List.of(DocumentType.TEST_SPEC, DocumentType.DOMAIN_RULE),
                List.of(MemoryFactType.TESTING_PATTERN)
            ),
            90,
            "Case generation prioritizes test specifications, domain rules, and reusable testing patterns."
        ));
        variants.add(variant(
            request,
            QueryIntent.BUSINESS_RULE,
            QueryTargetCorpus.KNOWLEDGE,
            joinNonBlank(request.businessEntity(), request.moduleName(), "business flow domain rule"),
            baseFilters(request, List.of(DocumentType.BUSINESS_FLOW, DocumentType.DOMAIN_RULE), List.of()),
            75,
            "Business rules help generated cases cover meaningful domain paths."
        ));
    }

    private void addFailureAnalysisVariants(List<QueryVariant> variants, QueryRewriteRequest request) {
        variants.add(variant(
            request,
            QueryIntent.ERROR_CODE,
            QueryTargetCorpus.KNOWLEDGE,
            joinNonBlank(request.errorCode(), request.apiPath(), "error code guide incident postmortem"),
            baseFilters(
                request,
                List.of(DocumentType.ERROR_CODE_GUIDE, DocumentType.INCIDENT_POSTMORTEM),
                List.of()
            ),
            95,
            "Failure analysis should first inspect error code guidance and incident postmortems."
        ));
        variants.add(variant(
            request,
            QueryIntent.FAILURE_REASON,
            QueryTargetCorpus.MEMORY,
            joinNonBlank(request.errorCode(), request.failureClassification(), request.moduleName(), "failure pattern"),
            baseFilters(request, List.of(), List.of(MemoryFactType.FAILURE_PATTERN)),
            85,
            "Historical failure patterns can explain recurring symptoms."
        ));
    }

    private void addSuiteVariants(List<QueryVariant> variants, QueryRewriteRequest request) {
        variants.add(variant(
            request,
            QueryIntent.SUITE_VARIABLE,
            QueryTargetCorpus.ALL,
            joinNonBlank(request.suiteId(), request.variableKey(), request.businessEntity(), "suite variable dependency"),
            baseFilters(
                request,
                List.of(DocumentType.BUSINESS_FLOW),
                List.of(MemoryFactType.SUITE_DEPENDENCY_FACT, MemoryFactType.VARIABLE_EXTRACTION_FACT)
            ),
            90,
            "Suite work needs variable dependency and business chain evidence."
        ));
        variants.add(variant(
            request,
            QueryIntent.BUSINESS_RULE,
            QueryTargetCorpus.KNOWLEDGE,
            joinNonBlank(request.businessEntity(), request.moduleName(), "business flow upstream downstream"),
            baseFilters(request, List.of(DocumentType.BUSINESS_FLOW, DocumentType.DOMAIN_RULE), List.of()),
            75,
            "Suite flow depends on upstream and downstream business context."
        ));
    }

    private void addPolicyVariants(List<QueryVariant> variants, QueryRewriteRequest request) {
        variants.add(variant(
            request,
            QueryIntent.POLICY_LEARNING,
            QueryTargetCorpus.MEMORY,
            joinNonBlank(request.toolName(), request.policyReason(), request.userGoal(), "policy learning"),
            baseFilters(request, List.of(), List.of(MemoryFactType.POLICY_LEARNING)),
            90,
            "Planner policy decisions should reuse high-confidence policy learning memory."
        ));
    }

    private void addDefaultVariants(List<QueryVariant> variants, QueryRewriteRequest request) {
        variants.add(variant(
            request,
            QueryIntent.BUSINESS_RULE,
            QueryTargetCorpus.ALL,
            joinNonBlank(request.userGoal(), request.businessEntity(), request.moduleName(), "domain rule"),
            baseFilters(request, List.of(DocumentType.DOMAIN_RULE), List.of(MemoryFactType.PROJECT_KNOWLEDGE)),
            70,
            "Default routing keeps a broad but explainable domain-rule variant."
        ));
    }

    private QueryVariant variant(
        QueryRewriteRequest request,
        QueryIntent intent,
        QueryTargetCorpus targetCorpus,
        String queryText,
        QueryFilters filters,
        int priority,
        String reason
    ) {
        var safeText = hasText(queryText) ? queryText.trim() : request.rawQuery();
        var id = deterministicId(request.stageProfile(), intent.name(), targetCorpus.name(), safeText, filters);
        return new QueryVariant(id, safeText, intent, targetCorpus, request.stageProfile(), filters, priority, reason);
    }

    private QueryFilters baseFilters(
        QueryRewriteRequest request,
        List<DocumentType> documentTypes,
        List<MemoryFactType> factTypes
    ) {
        return new QueryFilters(
            request.systemName(),
            request.moduleName(),
            request.apiPath(),
            request.httpMethod(),
            request.businessEntity(),
            request.errorCode(),
            request.failureClassification(),
            request.suiteId(),
            request.variableKey(),
            request.policyReason(),
            request.toolName(),
            request.tags(),
            documentTypes,
            factTypes
        );
    }

    private QueryRewriteRequest normalize(QueryRewriteRequest request) {
        return new QueryRewriteRequest(
            normalizeStage(request.stageProfile()),
            normalizeNullable(request.rawQuery()),
            normalizeNullable(request.userGoal()),
            normalizeNullable(request.systemName()),
            normalizeNullable(request.moduleName()),
            normalizeNullable(request.apiPath()),
            normalizeUpper(request.httpMethod()),
            normalizeNullable(request.businessEntity()),
            normalizeNullable(request.errorCode()),
            normalizeNullable(request.failureClassification()),
            normalizeNullable(request.suiteId()),
            normalizeNullable(request.variableKey()),
            normalizeNullable(request.policyReason()),
            normalizeNullable(request.toolName()),
            normalizeTags(request.tags())
        );
    }

    private String normalizeStage(String value) {
        var normalized = normalizeNullable(value);
        if (!hasText(normalized)) {
            return DEFAULT_STAGE;
        }
        return normalized.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String normalizeUpper(String value) {
        var normalized = normalizeNullable(value);
        return hasText(normalized) ? normalized.toUpperCase(Locale.ROOT) : null;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (var tag : tags) {
            var value = normalizeNullable(tag);
            if (hasText(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean hasLowContext(QueryRewriteRequest request) {
        return !hasText(request.systemName())
            && !hasText(request.moduleName())
            && !hasText(request.apiPath())
            && !hasText(request.businessEntity())
            && !hasText(request.errorCode())
            && !hasText(request.failureClassification())
            && !hasText(request.suiteId())
            && !hasText(request.variableKey())
            && !hasText(request.policyReason())
            && !hasText(request.toolName())
            && request.tags().isEmpty();
    }

    private String deterministicId(String stageProfile, String intent, String targetCorpus, String queryText, QueryFilters filters) {
        var fingerprint = String.join("|",
            stageProfile,
            intent,
            targetCorpus,
            queryText,
            String.valueOf(filters)
        );
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return "qv-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private String joinNonBlank(String... parts) {
        var values = new ArrayList<String>();
        for (var part : parts) {
            if (hasText(part)) {
                values.add(part.trim());
            }
        }
        return String.join(" ", values);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void requireNonBlank(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
