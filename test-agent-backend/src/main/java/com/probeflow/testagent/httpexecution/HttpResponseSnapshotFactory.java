package com.probeflow.testagent.httpexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.suiteruntime.RuntimeRedactor;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HttpResponseSnapshotFactory {

    private static final int TEXT_EXCERPT_LIMIT = 1_024;

    private final ObjectMapper objectMapper;

    public HttpResponseSnapshotFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> success(HttpClientResponse response, long durationMs) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("statusCode", response.statusCode());
        snapshot.put("headers", response.headers() == null ? Map.of() : RuntimeRedactor.redact(response.headers(), "headers"));
        snapshot.put("durationMs", durationMs);
        putBodyMetadata(snapshot, response.body());
        return snapshot;
    }

    public Map<String, Object> dryRun(String reason) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("dryRun", true);
        snapshot.put("reason", reason);
        snapshot.put("durationMs", 0L);
        return snapshot;
    }

    public Map<String, Object> skipped(String reason) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("skipReason", reason);
        snapshot.put("durationMs", 0L);
        return snapshot;
    }

    public Map<String, Object> blocked(String message) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("errorType", blockedErrorType(message));
        snapshot.put("errorMessage", message);
        snapshot.put("durationMs", 0L);
        return snapshot;
    }

    public Map<String, Object> error(String errorType, String message, long durationMs) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("errorType", errorType);
        snapshot.put("errorMessage", message);
        snapshot.put("durationMs", Math.max(0L, durationMs));
        return snapshot;
    }

    public Map<String, Object> transportError(HttpTransportException exception) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("errorType", exception.errorType());
        snapshot.put("errorMessage", exception.getMessage());
        snapshot.put("durationMs", exception.durationMs());
        return snapshot;
    }

    public Map<String, Object> transportError(String message, long durationMs) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("errorType", "TRANSPORT_ERROR");
        snapshot.put("errorMessage", message);
        snapshot.put("durationMs", Math.max(0L, durationMs));
        return snapshot;
    }

    private void putBodyMetadata(Map<String, Object> snapshot, Object body) {
        if (body == null) {
            snapshot.put("bodyType", "empty");
            snapshot.put("bodySizeBytes", 0);
            snapshot.put("bodyTruncated", false);
            return;
        }

        if (body instanceof byte[] bytes) {
            snapshot.put("bodyType", "binary");
            snapshot.put("bodySizeBytes", bytes.length);
            snapshot.put("bodyTruncated", false);
            return;
        }

        if (body instanceof CharSequence text) {
            putTextBody(snapshot, text.toString());
            return;
        }

        snapshot.put("bodyType", "json");
        snapshot.put("body", RuntimeRedactor.redact(body, "body"));
        snapshot.put("bodySizeBytes", jsonSizeBytes(body));
        snapshot.put("bodyTruncated", false);
    }

    private void putTextBody(Map<String, Object> snapshot, String body) {
        snapshot.put("bodyType", "text");
        snapshot.put("bodySizeBytes", body.getBytes(StandardCharsets.UTF_8).length);
        var truncated = body.length() > TEXT_EXCERPT_LIMIT;
        snapshot.put("bodyTruncated", truncated);
        snapshot.put("bodyExcerpt", truncated ? body.substring(0, TEXT_EXCERPT_LIMIT) : body);
    }

    private int jsonSizeBytes(Object body) {
        try {
            return objectMapper.writeValueAsBytes(body).length;
        } catch (JsonProcessingException exception) {
            return body.toString().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private String blockedErrorType(String message) {
        if (message != null && message.startsWith("Invalid URL:")) {
            return "INVALID_REQUEST";
        }
        if (message != null && message.startsWith("Unsupported protocol:")) {
            return "UNSUPPORTED_PROTOCOL";
        }
        if (message != null && message.startsWith("Blocked host:")) {
            return "BLOCKED_HOST";
        }
        return "BLOCKED_REQUEST";
    }
}
