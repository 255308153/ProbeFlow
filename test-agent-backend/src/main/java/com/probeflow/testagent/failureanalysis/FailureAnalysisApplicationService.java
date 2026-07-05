package com.probeflow.testagent.failureanalysis;

import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FailureAnalysisApplicationService {

    private final ExecutionRecordRepository executionRecords;

    public FailureAnalysisApplicationService(ExecutionRecordRepository executionRecords) {
        this.executionRecords = executionRecords;
    }

    @Transactional(readOnly = true)
    public FailureAnalysisResult analyzeExecution(FailureAnalysisRequest request) {
        var normalized = normalize(request);
        var record = executionRecords.findById(normalized.executionId())
            .orElseThrow(() -> new IllegalArgumentException("ExecutionRecord not found: " + normalized.executionId()));

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
            failedAssertions(record),
            record.getErrorMessage()
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
}
