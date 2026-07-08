package com.probeflow.testagent.demorun;

import com.probeflow.testagent.llm.LlmApplicationResult;
import com.probeflow.testagent.llm.LlmApplicationService;
import com.probeflow.testagent.llm.LlmCallRequest;
import com.probeflow.testagent.llm.LlmExecutionOptions;
import com.probeflow.testagent.llm.LlmTokenUsage;
import com.probeflow.testagent.llm.ManualRealLlmProviderConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DemoRunLlmApplicationGateway implements DemoRunRealLlmGateway {

    private final LlmApplicationService llm;
    private final ManualRealLlmProviderConfig config;

    public DemoRunLlmApplicationGateway(LlmApplicationService llm, ManualRealLlmProviderConfig config) {
        this.llm = llm;
        this.config = config == null
            ? new ManualRealLlmProviderConfig(null, null, null, null, null, null, null)
            : config;
    }

    @Override
    public DemoRunRealLlmProbeResult probe(DemoRunRequest request, Map<String, Object> inputSummary) {
        var missing = config.missingRequiredFields();
        if (!missing.isEmpty()) {
            return DemoRunRealLlmProbeResult.blocked(
                "Manual real LLM configuration is incomplete: " + missing,
                Map.of("missingFields", missing)
            );
        }
        var result = llm.call(new LlmCallRequest(
            "v4-demo-run:" + request.fixtureId(),
            "v4-real-llm-probe",
            null,
            "v2.report-narrative.v1",
            Map.of(
                "taskId", "v4-demo-run:" + request.fixtureId(),
                "summary", "ProbeFlow V4 manual real LLM demo preflight for fixture "
                    + request.fixtureId()
                    + " with input "
                    + inputSummary
            ),
            null,
            null,
            Map.of(
                "fixtureId", request.fixtureId(),
                "runProfile", request.runProfile(),
                "demoProviderMode", request.providerMode().name()
            ),
            new LlmExecutionOptions(
                config.providerName(),
                config.model(),
                0.2d,
                config.maxTokens(),
                config.timeoutMs(),
                0
            )
        ));
        var summary = callSummary(result, config.providerName(), config.model());
        var status = result.succeeded()
            ? DemoRunRealLlmProbeStatus.SUCCESS
            : "POLICY_BLOCKED".equals(summary.errorType())
                ? DemoRunRealLlmProbeStatus.BLOCKED
                : DemoRunRealLlmProbeStatus.FAILED;
        return new DemoRunRealLlmProbeResult(
            status,
            result.succeeded() ? "Manual real LLM preflight succeeded." : summary.errorMessage(),
            List.of(summary),
            Map.of("requestHash", result.requestHash(), "templateId", result.templateId())
        );
    }

    private DemoRunLlmCallSummary callSummary(
        LlmApplicationResult result,
        String provider,
        String model
    ) {
        var call = result.callResult();
        var response = call.response();
        var usage = response == null ? LlmTokenUsage.zero() : response.tokenUsage();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("llmCallId", result.llmCallId());
        metadata.put("requestHash", result.requestHash());
        metadata.put("templateId", result.templateId());
        metadata.put("templateVersion", result.templateVersion());
        if (response != null) {
            metadata.put("providerTraceId", response.providerTraceId());
        }
        return new DemoRunLlmCallSummary(
            "V4_DEMO_REAL_LLM_PREFLIGHT",
            call.status().name(),
            response == null ? provider : response.provider(),
            response == null ? model : response.model(),
            usage.promptTokens(),
            usage.completionTokens(),
            usage.totalTokens(),
            call.errorType().name(),
            call.errorMessage(),
            metadata
        );
    }
}
