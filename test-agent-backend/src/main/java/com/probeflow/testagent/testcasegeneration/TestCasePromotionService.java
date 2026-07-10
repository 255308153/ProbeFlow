package com.probeflow.testagent.testcasegeneration;

import com.probeflow.testagent.testcase.CaseCategory;
import com.probeflow.testagent.testcase.CasePriority;
import com.probeflow.testagent.testcase.CaseRiskLevel;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcase.CaseStatus;
import com.probeflow.testagent.testcase.DetailType;
import com.probeflow.testagent.testcase.StaleStatus;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcase.TestCaseRepository;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TestCasePromotionService {

    private final TestCaseDraftRepository drafts;
    private final TestCaseRepository testCases;

    public TestCasePromotionService(TestCaseDraftRepository drafts, TestCaseRepository testCases) {
        this.drafts = drafts;
        this.testCases = testCases;
    }

    @Transactional
    public TestCasePromotionResult promote(TestCasePromotionRequest request) {
        if (request == null || request.draftIds() == null || request.draftIds().isEmpty()) {
            throw new IllegalArgumentException("draftIds are required for promotion");
        }
        var promotedCaseIds = new ArrayList<String>();
        var skippedDraftIds = new ArrayList<String>();
        var diagnostics = new LinkedHashMap<String, String>();
        for (var draftId : request.draftIds()) {
            var draft = drafts.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("TestCaseDraft not found: " + draftId));
            if (StringUtils.hasText(draft.getPromotedCaseId())) {
                promotedCaseIds.add(draft.getPromotedCaseId());
                diagnostics.put(draftId, "Draft already promoted");
                continue;
            }
            var testCase = testCaseFromDraft(draft, request.promotedBy());
            var saved = testCases.save(testCase);
            draft.setPromotedCaseId(saved.getCaseId());
            draft.setStatus(DraftStatus.PROMOTED);
            drafts.save(draft);
            promotedCaseIds.add(saved.getCaseId());
        }
        return new TestCasePromotionResult(
            List.copyOf(promotedCaseIds),
            List.copyOf(skippedDraftIds),
            Map.copyOf(diagnostics)
        );
    }

    private TestCase testCaseFromDraft(TestCaseDraft draft, String promotedBy) {
        var content = draft.getDraftContent();
        var testCase = new TestCase();
        testCase.setPrimaryApiSpecId(draft.getTargetApiSpecId());
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(modeFrom(content));
        testCase.setTitle(stringValue(content.get("title"), "Generated API test case"));
        testCase.setDescription(stringValue(content.get("description"), ""));
        testCase.setPreconditions(stringList(content.get("preconditions")));
        testCase.setExpectedResult(stringValue(content.get("expectedResult"), ""));
        testCase.setPriority(priorityFrom(content.get("priorityHint")));
        testCase.setRiskLevel(riskFrom(content.get("riskHint")));
        testCase.setTags(stringList(content.get("tags")));
        testCase.setScenarioName(stringValue(content.get("scenarioName"), ""));
        testCase.setModuleName(stringValue(content.get("moduleName"), ""));
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(draft.getSource() == null ? CaseSource.STRUCTURE : draft.getSource());
        testCase.setManualEdited(false);
        testCase.setLocked(false);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(detailFromDraft(draft));
        testCase.setSteps(stepList(content.get("steps")));
        testCase.setStaleStatus(StaleStatus.FRESH);
        testCase.setBasedOnApiSpecVersions(basedOnApiSpecVersions(content, draft));
        testCase.setGeneratedFromSingleCaseIds(List.of(draft.getDraftId()));
        testCase.setGeneratedAt(Instant.now());
        testCase.setUpdatedBy(StringUtils.hasText(promotedBy) ? promotedBy : "phase5-promotion");
        return testCase;
    }

    private Map<String, Object> detailFromDraft(TestCaseDraft draft) {
        var content = draft.getDraftContent();
        var detail = new LinkedHashMap<String, Object>();
        detail.put("draftId", draft.getDraftId());
        detail.put("taskId", draft.getTaskId());
        detail.put("targetApiSpecId", draft.getTargetApiSpecId());
        detail.put("dedupKey", draft.getDedupKey());
        detail.put("draftSource", draft.getSource() == null ? null : draft.getSource().name());
        detail.put("expectedStatus", draft.getExpectedStatusCode());
        detail.put("requestShape", content.getOrDefault("requestShape", Map.of()));
        detail.put("scenarioCategory", content.get("scenarioCategory"));
        detail.put("constraintSource", content.get("constraintSource"));
        detail.put("contextCitations", content.getOrDefault("contextCitations", List.of()));
        detail.put("generationMetadata", content.getOrDefault("generationMetadata", Map.of()));
        if (content.containsKey("assertions")) {
            detail.put("assertions", content.get("assertions"));
        }
        if (content.containsKey("contractOrigin")) {
            detail.put("contractOrigin", content.get("contractOrigin"));
        }
        return detail;
    }

    private Map<String, Object> basedOnApiSpecVersions(Map<String, Object> content, TestCaseDraft draft) {
        var metadata = objectMap(content.get("generationMetadata"));
        if (metadata.get("apiSpecVersions") instanceof Map<?, ?> apiSpecVersions) {
            return toStringObjectMap(apiSpecVersions);
        }
        var versions = new LinkedHashMap<String, Object>();
        versions.put(draft.getTargetApiSpecId(), metadata.getOrDefault("apiSpecVersion", "unknown"));
        return versions;
    }

    private TestCaseMode modeFrom(Map<String, Object> content) {
        if (ScenarioCategory.BUSINESS_FLOW.name().equals(content.get("scenarioCategory"))) {
            return TestCaseMode.SUITE;
        }
        return TestCaseMode.SINGLE;
    }

    private CasePriority priorityFrom(Object value) {
        return switch (String.valueOf(value).toUpperCase(Locale.ROOT)) {
            case "P0", "P1", "HIGH" -> CasePriority.HIGH;
            case "P3", "LOW" -> CasePriority.LOW;
            default -> CasePriority.MEDIUM;
        };
    }

    private CaseRiskLevel riskFrom(Object value) {
        return switch (String.valueOf(value).toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> CaseRiskLevel.CRITICAL;
            case "HIGH" -> CaseRiskLevel.HIGH;
            case "LOW" -> CaseRiskLevel.LOW;
            default -> CaseRiskLevel.MEDIUM;
        };
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        var string = String.valueOf(value);
        return StringUtils.hasText(string) ? string : fallback;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private List<Map<String, Object>> stepList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var steps = new ArrayList<Map<String, Object>>();
        for (var item : list) {
            if (item instanceof Map<?, ?> map) {
                steps.add(toStringObjectMap(map));
            }
        }
        return List.copyOf(steps);
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringObjectMap(map);
        }
        return Map.of();
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> source) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }
}
