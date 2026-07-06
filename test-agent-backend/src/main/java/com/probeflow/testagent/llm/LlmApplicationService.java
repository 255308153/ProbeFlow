package com.probeflow.testagent.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LlmApplicationService {

    private static final String DEFAULT_PROVIDER = FakeLlmProvider.PROVIDER_NAME;
    private static final String DEFAULT_MODEL = "fake-model";

    private final PromptTemplateRegistry templates;
    private final List<LlmProvider> providers;
    private final LlmCallLogRepository logs;
    private final LlmPolicy policy;

    public LlmApplicationService(
        PromptTemplateRegistry templates,
        List<LlmProvider> providers,
        LlmCallLogRepository logs,
        LlmPolicy policy
    ) {
        this.templates = templates;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.logs = logs;
        this.policy = policy;
    }

    @Transactional
    public LlmApplicationResult call(LlmCallRequest request) {
        var normalized = normalize(request);
        var options = normalized.executionOptions().withFallbacks(DEFAULT_PROVIDER, DEFAULT_MODEL);
        var providerName = options.provider();
        var model = options.model();
        var render = renderPrompt(normalized);
        var requestHash = requestHash(providerName, model, normalized, render);

        if (!render.success()) {
            var result = LlmCallResult.failure(render.errorType(), render.errorMessage());
            var saved = logs.save(logFor(normalized, render, providerName, model, requestHash, result, 0L));
            return new LlmApplicationResult(saved.getLlmCallId(), result, requestHash, templateId(render, normalized), templateVersion(render));
        }

        var policyDecision = policy.evaluate(options);
        if (!policyDecision.allowed()) {
            var result = LlmCallResult.blocked(policyDecision.errorType(), policyDecision.message());
            var saved = logs.save(logFor(normalized, render, providerName, model, requestHash, result, 0L));
            return new LlmApplicationResult(saved.getLlmCallId(), result, requestHash, templateId(render, normalized), templateVersion(render));
        }

        var provider = provider(providerName);
        if (provider == null) {
            var result = LlmCallResult.failure(LlmErrorType.PROVIDER_ERROR, "LLM provider not found: " + providerName);
            var saved = logs.save(logFor(normalized, render, providerName, model, requestHash, result, 0L));
            return new LlmApplicationResult(saved.getLlmCallId(), result, requestHash, templateId(render, normalized), templateVersion(render));
        }

        var started = System.nanoTime();
        try {
            var response = provider.generate(new LlmRequest(
                providerName,
                model,
                purpose(normalized, render),
                render.renderedPrompt(),
                normalized.taskId(),
                normalized.planStepId(),
                requestMetadata(normalized, render)
            ));
            var latencyMs = elapsedMs(started);
            var result = LlmCallResult.success(response);
            var saved = logs.save(logFor(normalized, render, providerName, model, requestHash, result, latencyMs));
            return new LlmApplicationResult(saved.getLlmCallId(), result, requestHash, templateId(render, normalized), templateVersion(render));
        } catch (LlmProviderException exception) {
            var latencyMs = elapsedMs(started);
            var result = LlmCallResult.failure(exception.errorType(), exception.getMessage());
            var log = logFor(normalized, render, providerName, model, requestHash, result, latencyMs);
            log.setProviderTraceId(exception.providerTraceId());
            var saved = logs.save(log);
            return new LlmApplicationResult(saved.getLlmCallId(), result, requestHash, templateId(render, normalized), templateVersion(render));
        } catch (RuntimeException exception) {
            var latencyMs = elapsedMs(started);
            var result = LlmCallResult.failure(LlmErrorType.PROVIDER_ERROR, exception.getMessage());
            var saved = logs.save(logFor(normalized, render, providerName, model, requestHash, result, latencyMs));
            return new LlmApplicationResult(saved.getLlmCallId(), result, requestHash, templateId(render, normalized), templateVersion(render));
        }
    }

    private LlmCallRequest normalize(LlmCallRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("LlmCallRequest is required");
        }
        if (request.templateId() == null && request.purpose() == null) {
            throw new IllegalArgumentException("templateId or purpose is required");
        }
        return request;
    }

    private PromptRenderResult renderPrompt(LlmCallRequest request) {
        if (request.templateId() != null) {
            return templates.renderById(request.templateId(), request.variables());
        }
        return templates.renderLatestByPurpose(request.purpose(), request.variables());
    }

    private LlmProvider provider(String providerName) {
        return providers.stream()
            .filter(provider -> providerName.equals(provider.providerName()))
            .min(Comparator.comparing(provider -> provider.getClass().getName()))
            .orElse(null);
    }

    private LlmCallLog logFor(
        LlmCallRequest request,
        PromptRenderResult render,
        String providerName,
        String model,
        String requestHash,
        LlmCallResult result,
        long latencyMs
    ) {
        var response = result.response();
        var tokenUsage = response == null ? LlmTokenUsage.zero() : response.tokenUsage();
        var log = new LlmCallLog();
        log.setTaskId(request.taskId());
        log.setPlanStepId(request.planStepId());
        log.setPurpose(purpose(request, render));
        log.setProvider(providerName);
        log.setModel(model);
        log.setTemplateId(templateId(render, request));
        log.setTemplateVersion(templateVersion(render));
        log.setRequestHash(requestHash);
        log.setStatus(result.status());
        log.setErrorType(result.errorType());
        log.setErrorMessage(result.errorMessage());
        log.setLatencyMs(latencyMs);
        log.setPromptTokens(tokenUsage.promptTokens());
        log.setCompletionTokens(tokenUsage.completionTokens());
        log.setTotalTokens(tokenUsage.totalTokens());
        log.setPromptSummary(render.renderedPrompt());
        log.setResponseSummary(response == null ? null : response.text());
        log.setProviderTraceId(response == null ? null : response.providerTraceId());
        log.setFakeProvider(result.fakeProvider());
        log.setMetadata(logMetadata(request, render, providerName));
        return log;
    }

    private Map<String, Object> logMetadata(LlmCallRequest request, PromptRenderResult render, String providerName) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("templateRenderSuccess", render.success());
        metadata.put("providerSelected", providerName);
        putIfPresent(metadata, "temperature", request.executionOptions().temperature());
        putIfPresent(metadata, "maxTokens", request.executionOptions().maxTokens());
        putIfPresent(metadata, "timeoutMs", request.executionOptions().timeoutMs());
        putIfPresent(metadata, "retryAttempts", request.executionOptions().retryAttempts());
        return Map.copyOf(metadata);
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private Map<String, Object> requestMetadata(LlmCallRequest request, PromptRenderResult render) {
        var metadata = new TreeMap<String, Object>();
        metadata.putAll(request.metadata());
        metadata.put("templateId", render.template().templateId());
        metadata.put("templateVersion", render.template().version());
        metadata.put(FakeLlmProvider.RESPONSE_KEY_METADATA, render.template().templateId());
        return Map.copyOf(metadata);
    }

    private String requestHash(
        String providerName,
        String model,
        LlmCallRequest request,
        PromptRenderResult render
    ) {
        return sha256(
            "provider=" + providerName
                + "\nmodel=" + model
                + "\ntemplate=" + templateId(render, request)
                + "\nversion=" + templateVersion(render)
                + "\npurpose=" + purpose(request, render)
                + "\nvariables=" + canonicalMap(request.variables())
                + "\nprompt=" + render.renderedPrompt()
        );
    }

    private String purpose(LlmCallRequest request, PromptRenderResult render) {
        if (render.template() != null) {
            return render.template().purpose();
        }
        return request.purpose() == null ? "UNKNOWN" : request.purpose();
    }

    private String templateId(PromptRenderResult render, LlmCallRequest request) {
        if (render.template() != null) {
            return render.template().templateId();
        }
        return request.templateId();
    }

    private String templateVersion(PromptRenderResult render) {
        return render.template() == null ? null : render.template().version();
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private String defaulted(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private String canonicalMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        var sorted = new TreeMap<String, Object>(map);
        var builder = new StringBuilder("{");
        var first = true;
        for (var entry : sorted.entrySet()) {
            if (!first) {
                builder.append(",");
            }
            first = false;
            builder.append(entry.getKey()).append("=").append(String.valueOf(entry.getValue()));
        }
        return builder.append("}").toString();
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
