package com.probeflow.testagent.failureanalysis;

import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.observation.AnalysisLevel;
import com.probeflow.testagent.observation.Observation;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.observation.ObservationRiskLevel;
import com.probeflow.testagent.observation.ObservationSource;
import com.probeflow.testagent.observation.ObservationType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FailureAnalysisApplicationService {

    private final ExecutionRecordRepository executionRecords;
    private final ObservationRepository observations;

    public FailureAnalysisApplicationService(
        ExecutionRecordRepository executionRecords,
        ObservationRepository observations
    ) {
        this.executionRecords = executionRecords;
        this.observations = observations;
    }

    @Transactional
    public FailureAnalysisResult analyzeExecution(FailureAnalysisRequest request) {
        var normalized = normalize(request);
        var record = executionRecords.findById(normalized.executionId())
            .orElseThrow(() -> new IllegalArgumentException("ExecutionRecord not found: " + normalized.executionId()));

        var failedAssertions = failedAssertions(record);
        var classification = classify(record, failedAssertions);
        var riskLevel = riskLevel(record, classification, failedAssertions);
        var summary = summary(record, classification);
        var failureReason = failureReason(record, classification, failedAssertions);
        var nextSuggestion = "Review deterministic failure analysis evidence.";
        var observationIds = writeObservationIfUseful(
            record,
            classification,
            riskLevel,
            summary,
            failureReason,
            nextSuggestion
        );
        return new FailureAnalysisResult(
            record.getExecutionId(),
            record.getTaskId(),
            record.getCaseId(),
            record.getStepId(),
            normalized.mode(),
            record.getOverallStatus(),
            record.getStatusCode(),
            record.getDurationMs(),
            record.getEnvironment(),
            requestFacts(record),
            responseFacts(record),
            failedAssertions,
            record.getErrorMessage(),
            classification,
            evidence(record, failedAssertions, classification),
            observationIds,
            riskLevel.name(),
            summary,
            failureReason,
            nextSuggestion
        );
    }

    private FailureAnalysisRequest normalize(FailureAnalysisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("FailureAnalysisRequest is required");
        }
        var executionId = clean(request.executionId());
        if (executionId == null) {
            throw new IllegalArgumentException("executionId is required");
        }
        var mode = request.mode() == null ? FailureAnalysisMode.BASIC : request.mode();
        if (mode == FailureAnalysisMode.DEEP) {
            throw new IllegalArgumentException("DEEP failure analysis is reserved for a future phase");
        }
        return new FailureAnalysisRequest(executionId, mode);
    }

    private RequestFacts requestFacts(ExecutionRecord record) {
        var snapshot = safeMap(record.getRequestSnapshot());
        return new RequestFacts(
            stringValue(snapshot.get("method")),
            stringValue(snapshot.get("path")),
            stringValue(snapshot.get("url")),
            nestedMap(snapshot.get("headers"))
        );
    }

    private ResponseFacts responseFacts(ExecutionRecord record) {
        var snapshot = safeMap(record.getResponseSnapshot());
        return new ResponseFacts(
            intValue(snapshot.get("statusCode")),
            stringValue(snapshot.get("failureType")),
            stringValue(snapshot.get("errorType")),
            stringValue(snapshot.get("bodyType")),
            booleanValue(snapshot.get("bodyTruncated")),
            longValue(snapshot.get("bodySizeBytes")),
            nestedMap(snapshot.get("headers"))
        );
    }

    private List<FailedAssertionSummary> failedAssertions(ExecutionRecord record) {
        return record.getAssertionResults().stream()
            .filter(assertion -> "FAILED".equals(stringValue(assertion.get("status"))))
            .map(assertion -> new FailedAssertionSummary(
                stringValue(assertion.get("name")),
                stringValue(assertion.get("type")),
                assertion.get("expected"),
                assertion.get("actual"),
                stringValue(assertion.get("path")),
                booleanValue(assertion.get("critical")) == null || Boolean.TRUE.equals(booleanValue(assertion.get("critical"))),
                stringValue(assertion.get("message"))
            ))
            .toList();
    }

    private FailureClassification classify(ExecutionRecord record, List<FailedAssertionSummary> failedAssertions) {
        var response = safeMap(record.getResponseSnapshot());
        var errorType = stringValue(response.get("errorType"));
        var statusCode = record.getStatusCode();

        if (record.getOverallStatus() == OverallStatus.PASSED) {
            return FailureClassification.NONE;
        }
        if (record.getOverallStatus() == OverallStatus.SKIPPED) {
            return FailureClassification.SKIPPED;
        }
        if (record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS) {
            return warningClassification(failedAssertions);
        }
        if (record.getOverallStatus() == OverallStatus.BLOCKED) {
            return blockedClassification(errorType, record.getErrorMessage());
        }
        if (record.getOverallStatus() == OverallStatus.ERROR) {
            if ("TIMEOUT".equals(errorType) || contains(record.getErrorMessage(), "timeout") || contains(record.getErrorMessage(), "timed out")) {
                return FailureClassification.TIMEOUT;
            }
            return FailureClassification.TRANSPORT_ERROR;
        }
        if (statusCode != null) {
            if (statusCode == 401 || statusCode == 403) {
                return FailureClassification.AUTH_ISSUE;
            }
            if (statusCode == 400 || statusCode == 422) {
                return FailureClassification.VALIDATION_ISSUE;
            }
            if (statusCode >= 500) {
                return FailureClassification.SERVER_ERROR;
            }
        }
        if (!failedAssertions.isEmpty()) {
            return failedAssertionClassification(failedAssertions);
        }
        return FailureClassification.UNKNOWN;
    }

    private FailureClassification warningClassification(List<FailedAssertionSummary> failedAssertions) {
        var failedClassification = failedAssertionClassification(failedAssertions);
        return failedClassification == FailureClassification.UNKNOWN
            ? FailureClassification.PASSED_WITH_WARNING
            : failedClassification;
    }

    private FailureClassification blockedClassification(String errorType, String errorMessage) {
        if ("BLOCKED_HOST".equals(errorType) || "INVALID_REQUEST".equals(errorType) || contains(errorMessage, "Invalid URL")
            || contains(errorMessage, "Unsupported protocol") || contains(errorMessage, "Unresolved variable")) {
            return FailureClassification.ENVIRONMENT_ISSUE;
        }
        return FailureClassification.BLOCKED_REQUEST;
    }

    private FailureClassification failedAssertionClassification(List<FailedAssertionSummary> failedAssertions) {
        if (failedAssertions.stream().anyMatch(assertion -> "STATUS_CODE".equals(assertion.type()))) {
            return FailureClassification.STATUS_MISMATCH;
        }
        if (failedAssertions.stream().anyMatch(assertion -> "JSON_FIELD_EXISTS".equals(assertion.type()))) {
            return FailureClassification.RESPONSE_SHAPE_MISMATCH;
        }
        if (failedAssertions.stream().anyMatch(assertion -> "JSON_FIELD_EQUALS".equals(assertion.type()))) {
            return FailureClassification.RESPONSE_VALUE_MISMATCH;
        }
        if (failedAssertions.stream().anyMatch(assertion -> "BODY_PRESENT".equals(assertion.type()))) {
            return FailureClassification.BODY_PRESENCE_FAILURE;
        }
        if (failedAssertions.stream().anyMatch(assertion -> "DURATION_LESS_THAN_MS".equals(assertion.type()))) {
            return FailureClassification.DURATION_REGRESSION;
        }
        if (!failedAssertions.isEmpty()) {
            return FailureClassification.ASSERTION_FAILURE;
        }
        return FailureClassification.UNKNOWN;
    }

    private List<String> evidence(
        ExecutionRecord record,
        List<FailedAssertionSummary> failedAssertions,
        FailureClassification classification
    ) {
        var evidence = new ArrayList<String>();
        evidence.add("overallStatus=" + record.getOverallStatus());
        if (record.getStatusCode() != null) {
            evidence.add("statusCode=" + record.getStatusCode());
        }
        if (record.getDurationMs() != null) {
            evidence.add("durationMs=" + record.getDurationMs());
        }
        var response = safeMap(record.getResponseSnapshot());
        var failureType = stringValue(response.get("failureType"));
        if (failureType != null) {
            evidence.add("failureType=" + failureType);
        }
        var errorType = stringValue(response.get("errorType"));
        if (errorType != null) {
            evidence.add("errorType=" + errorType);
        }
        if (record.getErrorMessage() != null) {
            evidence.add("errorMessage=" + record.getErrorMessage());
        }
        failedAssertions.forEach(assertion -> evidence.add(
            "failedAssertion=" + assertion.type()
                + (assertion.path() == null ? "" : " path=" + assertion.path())
                + " expected=" + assertion.expected()
                + " actual=" + assertion.actual()
        ));
        evidence.add("classification=" + classification);
        return List.copyOf(evidence);
    }

    private List<String> writeObservationIfUseful(
        ExecutionRecord record,
        FailureClassification classification,
        ObservationRiskLevel riskLevel,
        String summary,
        String failureReason,
        String nextSuggestion
    ) {
        if (!shouldWriteObservation(record)) {
            return List.of();
        }
        var existing = observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            record.getExecutionId(),
            AnalysisLevel.BASIC
        );
        if (!existing.isEmpty()) {
            return existing.stream().map(Observation::getObservationId).toList();
        }

        var observation = new Observation();
        observation.setTaskId(record.getTaskId());
        observation.setExecutionId(record.getExecutionId());
        observation.setObservationType(observationType(classification));
        observation.setAnalysisLevel(AnalysisLevel.BASIC);
        observation.setSummary(summary);
        observation.setFailureReason(failureReason);
        observation.setRiskLevel(riskLevel);
        observation.setNextSuggestion(nextSuggestion);
        observation.setSource(ObservationSource.SYSTEM);
        var saved = observations.save(observation);
        return List.of(saved.getObservationId());
    }

    private boolean shouldWriteObservation(ExecutionRecord record) {
        return record.getOverallStatus() == OverallStatus.FAILED
            || record.getOverallStatus() == OverallStatus.ERROR
            || record.getOverallStatus() == OverallStatus.BLOCKED
            || record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS;
    }

    private ObservationType observationType(FailureClassification classification) {
        return switch (classification) {
            case AUTH_ISSUE, VALIDATION_ISSUE, SERVER_ERROR, DURATION_REGRESSION -> ObservationType.RISK_EVALUATION;
            case UNKNOWN, NONE, SKIPPED -> ObservationType.GENERAL_COMMENT;
            default -> ObservationType.ASSERTION_FAILURE_ANALYSIS;
        };
    }

    private ObservationRiskLevel riskLevel(
        ExecutionRecord record,
        FailureClassification classification,
        List<FailedAssertionSummary> failedAssertions
    ) {
        if (record.isCriticalFailed() || classification == FailureClassification.SERVER_ERROR) {
            return ObservationRiskLevel.HIGH;
        }
        if (classification == FailureClassification.AUTH_ISSUE
            || classification == FailureClassification.ENVIRONMENT_ISSUE
            || classification == FailureClassification.TIMEOUT
            || classification == FailureClassification.TRANSPORT_ERROR) {
            return ObservationRiskLevel.MEDIUM;
        }
        if (record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS
            || failedAssertions.stream().noneMatch(FailedAssertionSummary::critical)) {
            return ObservationRiskLevel.LOW;
        }
        return ObservationRiskLevel.MEDIUM;
    }

    private String summary(ExecutionRecord record, FailureClassification classification) {
        return "BASIC failure analysis classified execution " + record.getExecutionId()
            + " as " + classification
            + " with status " + record.getOverallStatus();
    }

    private String failureReason(
        ExecutionRecord record,
        FailureClassification classification,
        List<FailedAssertionSummary> failedAssertions
    ) {
        if (record.getErrorMessage() != null && !record.getErrorMessage().isBlank()) {
            return record.getErrorMessage();
        }
        if (!failedAssertions.isEmpty()) {
            var first = failedAssertions.getFirst();
            return first.type() + " expected " + first.expected() + " but got " + first.actual();
        }
        return "Execution evidence indicates " + classification;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        return Map.of();
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle.toLowerCase());
    }
}
