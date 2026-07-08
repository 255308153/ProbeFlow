package com.probeflow.testagent.demorun;

import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentArtifactReference;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentDiagnostic;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentProviderMode;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunRequest;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunStatus;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSource;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSummary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DemoRunApplicationService {

    private final ManualSuiteAgentHarness harness;
    private final DemoRunRealLlmGateway realLlmGateway;
    private final DemoRunComparisonReportWriter comparisonReportWriter = new DemoRunComparisonReportWriter();

    public DemoRunApplicationService() {
        this(ManualSuiteAgentHarness.defaults(), DemoRunRealLlmGateway.disabled());
    }

    public DemoRunApplicationService(ManualSuiteAgentHarness harness) {
        this(harness, DemoRunRealLlmGateway.disabled());
    }

    public DemoRunApplicationService(ManualSuiteAgentHarness harness, DemoRunRealLlmGateway realLlmGateway) {
        this.harness = harness == null ? ManualSuiteAgentHarness.defaults() : harness;
        this.realLlmGateway = realLlmGateway == null ? DemoRunRealLlmGateway.disabled() : realLlmGateway;
    }

    @Autowired
    public DemoRunApplicationService(ObjectProvider<DemoRunRealLlmGateway> realLlmGateway) {
        this(ManualSuiteAgentHarness.defaults(), realLlmGateway.getIfAvailable(DemoRunRealLlmGateway::disabled));
    }

    public DemoRunResult run(DemoRunRequest request) {
        var effectiveRequest = request == null
            ? new DemoRunRequest(null, null, null, null)
            : request;
        if (effectiveRequest.comparisonEnabled() || effectiveRequest.providerMode() == DemoRunProviderMode.COMPARISON) {
            return runComparison(effectiveRequest);
        }
        if (effectiveRequest.providerMode() == DemoRunProviderMode.REAL) {
            var realGuard = validateRealRunProfile(effectiveRequest);
            if (realGuard != null) {
                return rejectedRealResult(
                    effectiveRequest,
                    "REAL_LLM_NOT_AVAILABLE",
                    realGuard,
                    List.of(),
                    Map.of("category", "CONFIGURATION")
                );
            }
            var probe = realLlmGateway.probe(effectiveRequest, realProbeInput(effectiveRequest));
            if (probe.status() != DemoRunRealLlmProbeStatus.SUCCESS) {
                return rejectedRealResult(
                    effectiveRequest,
                    realRejectionCode(probe),
                    probe.message(),
                    probe.callSummaries(),
                    probe.metadata()
                );
            }
            return runHarness(effectiveRequest, true, probe.callSummaries());
        }
        var harnessResult = harness.run(toHarnessRequest(effectiveRequest, false));
        return toResult(effectiveRequest, harnessResult, List.of());
    }

    private DemoRunResult runHarness(
        DemoRunRequest effectiveRequest,
        boolean allowManualProvider,
        List<DemoRunLlmCallSummary> llmCalls
    ) {
        var harnessResult = harness.run(toHarnessRequest(effectiveRequest, allowManualProvider));
        return toResult(effectiveRequest, harnessResult, llmCalls);
    }

    private DemoRunResult toResult(
        DemoRunRequest effectiveRequest,
        ManualSuiteAgentRunResult harnessResult,
        List<DemoRunLlmCallSummary> llmCalls
    ) {
        var providerMode = toDemoProviderMode(harnessResult.providerMode());
        return new DemoRunResult(
            DemoRunResult.SCHEMA_VERSION,
            harnessResult.runId(),
            harnessResult.fixtureId(),
            harnessResult.fixtureVersion(),
            providerMode,
            toDemoStatus(harnessResult.status()),
            harnessResult.startedAt(),
            harnessResult.completedAt(),
            harnessResult.runProfile(),
            harnessResult.usesRealProvider(),
            harnessResult.usesExternalHttp(),
            providerSummary(effectiveRequest, harnessResult, providerMode, llmCalls),
            planSection(harnessResult),
            contextSection(harnessResult),
            toolsSection(harnessResult),
            mappedSection(harnessResult, "generated-suite-draft", "suite", "Suite Draft"),
            mappedSection(harnessResult, "execution-result", "execution", "Execution"),
            mappedSection(harnessResult, "variable-audit", "variable-audit", "Variable Audit"),
            mappedSection(harnessResult, "failure-analysis", "failure-analysis", "Failure Analysis"),
            memoryFeedbackSection(effectiveRequest, harnessResult, providerMode),
            mappedSection(harnessResult, "evaluation-comparison", "evaluation", "Evaluation"),
            DemoRunSectionView.notRun("comparison", "Comparison"),
            errorsSection(harnessResult),
            harnessResult.artifacts().stream().map(this::artifactView).toList(),
            harnessResult.diagnostics().stream().map(this::diagnosticView).toList()
        );
    }

    private ManualSuiteAgentRunRequest toHarnessRequest(DemoRunRequest request, boolean allowManualProvider) {
        var harnessProviderMode = request.providerMode() == DemoRunProviderMode.REAL
            ? ManualSuiteAgentProviderMode.MANUAL_REAL_LLM
            : ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE;
        return new ManualSuiteAgentRunRequest(
            request.fixtureId(),
            harnessProviderMode,
            request.outputDirectory(),
            allowManualProvider,
            false,
            request.runProfile(),
            request.providerMode().name()
        );
    }

    private DemoRunProviderSummary providerSummary(
        DemoRunRequest request,
        ManualSuiteAgentRunResult result,
        DemoRunProviderMode providerMode,
        List<DemoRunLlmCallSummary> llmCalls
    ) {
        return new DemoRunProviderSummary(
            providerMode,
            result.providerMode().name(),
            request.providerMode().name(),
            result.runProfile(),
            result.usesRealProvider(),
            result.usesExternalHttp(),
            providerMode == DemoRunProviderMode.FAKE,
            request.comparisonEnabled(),
            request.allowMemoryWrite(),
            request.outputFormats(),
            DemoRunRedactor.sanitizeLlmCalls(llmCalls),
            List.of(
                "default demo run uses deterministic fake provider",
                "default demo run does not require a real LLM key",
                "default demo run does not require real embedding",
                "default demo run uses fake HTTP gateway only"
            )
        );
    }

    private String validateRealRunProfile(DemoRunRequest request) {
        if (!"manual-real-llm".equals(request.runProfile())) {
            return "Manual real LLM mode requires runProfile=manual-real-llm.";
        }
        return null;
    }

    private Map<String, Object> realProbeInput(DemoRunRequest request) {
        return orderedMap(
            "fixtureId", request.fixtureId(),
            "providerMode", request.providerMode().name(),
            "runProfile", request.runProfile(),
            "allowMemoryWrite", request.allowMemoryWrite(),
            "usesExternalHttp", false
        );
    }

    private DemoRunResult rejectedRealResult(
        DemoRunRequest request,
        String diagnosticCode,
        String message,
        List<DemoRunLlmCallSummary> llmCalls,
        Map<String, Object> metadata
    ) {
        var now = Instant.now();
        var diagnostic = new DemoRunDiagnosticView(
            diagnosticCode,
            "ERROR",
            DemoRunRedactor.sanitizeText(message),
            DemoRunRedactor.sanitizeMap(metadata)
        );
        var errors = new DemoRunSectionView(
            "errors",
            "Errors",
            DemoRunSectionSource.APPLICATION,
            DemoRunStatus.REJECTED.name(),
            orderedMap(
                "hasErrors", true,
                "diagnostics", List.of(orderedMap(
                    "code", diagnostic.code(),
                    "category", diagnosticCategory(diagnostic.code()),
                    "severity", diagnostic.severity(),
                    "message", DemoRunRedactor.sanitizeText(diagnostic.message()),
                    "metadata", diagnostic.metadata()
                ))
            )
        );
        return new DemoRunResult(
            DemoRunResult.SCHEMA_VERSION,
            "v4d-rejected-" + java.util.UUID.randomUUID(),
            request.fixtureId(),
            "unknown",
            DemoRunProviderMode.REAL,
            DemoRunStatus.REJECTED,
            now,
            now,
            request.runProfile(),
            false,
            false,
            new DemoRunProviderSummary(
                DemoRunProviderMode.REAL,
                ManualSuiteAgentProviderMode.MANUAL_REAL_LLM.name(),
                request.providerMode().name(),
                request.runProfile(),
                false,
                false,
                false,
                request.comparisonEnabled(),
                request.allowMemoryWrite(),
                request.outputFormats(),
                DemoRunRedactor.sanitizeLlmCalls(llmCalls),
                List.of(
                    "real LLM mode requires explicit providerMode=REAL",
                    "real LLM mode requires runProfile=manual-real-llm",
                    "real LLM mode is rejected when endpoint, key, model, timeout or cost limit are missing",
                    "default demo run still uses fake provider and fake HTTP only"
                )
            ),
            DemoRunSectionView.notRun("plan", "Plan"),
            DemoRunSectionView.notRun("context", "Context"),
            DemoRunSectionView.notRun("tools", "Tools"),
            DemoRunSectionView.notRun("suite", "Suite Draft"),
            DemoRunSectionView.notRun("execution", "Execution"),
            DemoRunSectionView.notRun("variable-audit", "Variable Audit"),
            DemoRunSectionView.notRun("failure-analysis", "Failure Analysis"),
            DemoRunSectionView.notRun("memory-feedback", "Memory Feedback"),
            DemoRunSectionView.notRun("evaluation", "Evaluation"),
            DemoRunSectionView.notRun("comparison", "Comparison"),
            errors,
            List.of(),
            List.of(diagnostic)
        );
    }

    private String realRejectionCode(DemoRunRealLlmProbeResult probe) {
        var policyBlocked = probe.callSummaries().stream()
            .anyMatch(call -> "POLICY_BLOCKED".equals(call.errorType()));
        return probe.status() == DemoRunRealLlmProbeStatus.BLOCKED && policyBlocked
            ? "REAL_LLM_POLICY_BLOCKED"
            : "REAL_LLM_NOT_AVAILABLE";
    }

    private DemoRunResult runComparison(DemoRunRequest request) {
        var comparisonRunId = "v4d-comparison-" + java.util.UUID.randomUUID();
        var now = Instant.now();
        var fakeRequest = new DemoRunRequest(
            request.fixtureId(),
            DemoRunProviderMode.FAKE,
            "comparison-demo",
            request.outputDirectory(),
            false,
            false,
            request.outputFormats()
        );
        var realRequest = new DemoRunRequest(
            request.fixtureId(),
            DemoRunProviderMode.REAL,
            "manual-real-llm",
            request.outputDirectory(),
            false,
            false,
            request.outputFormats()
        );
        var fakeBaseline = run(fakeRequest);
        var realRun = run(realRequest);
        var comparison = comparisonSection(fakeBaseline, realRun);
        var diagnostics = mergeDiagnostics(fakeBaseline, realRun);
        var artifacts = comparisonReportWriter.artifactReferences(
            comparisonRunId,
            request.outputDirectory(),
            request.outputFormats(),
            fakeBaseline.runId(),
            realRun.runId()
        );
        var result = new DemoRunResult(
            DemoRunResult.SCHEMA_VERSION,
            comparisonRunId,
            request.fixtureId(),
            fakeBaseline.fixtureVersion(),
            DemoRunProviderMode.COMPARISON,
            fakeBaseline.status() == DemoRunStatus.COMPLETED ? DemoRunStatus.COMPLETED : DemoRunStatus.FAILED,
            now,
            Instant.now(),
            "comparison-demo",
            realRun.usesRealLlm(),
            fakeBaseline.usesExternalHttp() || realRun.usesExternalHttp(),
            new DemoRunProviderSummary(
                DemoRunProviderMode.COMPARISON,
                "FAKE_BASELINE_AND_MANUAL_REAL_LLM",
                request.providerMode().name(),
                "comparison-demo",
                realRun.usesRealLlm(),
                fakeBaseline.usesExternalHttp() || realRun.usesExternalHttp(),
                true,
                true,
                false,
                request.outputFormats(),
                realRun.provider().llmCalls(),
                List.of(
                    "comparison mode always runs a deterministic fake baseline",
                    "comparison mode attempts real LLM only through the existing provider seam",
                    "comparison mode defaults allowMemoryWrite=false",
                    "real LLM unavailable is represented as a real-run result, not a demo crash"
                )
            ),
            fakeBaseline.plan(),
            fakeBaseline.context(),
            fakeBaseline.tools(),
            fakeBaseline.suite(),
            fakeBaseline.execution(),
            fakeBaseline.variableAudit(),
            fakeBaseline.failureAnalysis(),
            fakeBaseline.memoryFeedback(),
            fakeBaseline.evaluation(),
            comparison,
            comparisonErrorsSection(fakeBaseline, realRun),
            mergeArtifacts(artifacts, fakeBaseline, realRun),
            diagnostics
        );
        comparisonReportWriter.write(result, fakeBaseline, realRun);
        return result;
    }

    private DemoRunSectionView comparisonSection(DemoRunResult fakeBaseline, DemoRunResult realRun) {
        var summary = orderedMap(
            "fakeBaseline", runComparisonSummary("fake-baseline", fakeBaseline),
            "realRun", runComparisonSummary("real-run", realRun),
            "memoryWriteSuppressed", true,
            "planStepDifferences", difference("plan", fakeBaseline.plan(), realRun.plan()),
            "toolSelectionDifferences", difference("tools", fakeBaseline.tools(), realRun.tools()),
            "failureAnalysisDifferences", difference("failure-analysis", fakeBaseline.failureAnalysis(), realRun.failureAnalysis()),
            "memoryFeedbackDifferences", difference("memory-feedback", fakeBaseline.memoryFeedback(), realRun.memoryFeedback()),
            "reportSummaryDifferences", difference("evaluation", fakeBaseline.evaluation(), realRun.evaluation()),
            "humanJudgmentNotes", List.of(
                "Comparison shows behavioral differences and risk points; it does not rank real LLM as automatically better.",
                "A rejected real run is still useful evidence about configuration or policy readiness.",
                "Fake baseline remains the stable CI-safe reference."
            )
        );
        return new DemoRunSectionView(
            "comparison",
            "Fake vs Real LLM Comparison",
            DemoRunSectionSource.APPLICATION,
            "READY",
            summary
        );
    }

    private Map<String, Object> runComparisonSummary(String role, DemoRunResult result) {
        return orderedMap(
            "role", role,
            "runId", result.runId(),
            "status", result.status().name(),
            "providerMode", result.providerMode().name(),
            "usesRealLlm", result.usesRealLlm(),
            "usesExternalHttp", result.usesExternalHttp(),
            "runProfile", result.runProfile(),
            "diagnostics", result.diagnostics().stream()
                .map(diagnostic -> orderedMap(
                    "code", diagnostic.code(),
                    "severity", diagnostic.severity(),
                    "message", DemoRunRedactor.sanitizeText(diagnostic.message())
                ))
                .toList()
        );
    }

    private Map<String, Object> difference(
        String dimension,
        DemoRunSectionView fakeSection,
        DemoRunSectionView realSection
    ) {
        return orderedMap(
            "dimension", dimension,
            "fakeProvider", sectionComparisonSummary(fakeSection),
            "realProvider", sectionComparisonSummary(realSection),
            "statusChanged", !fakeSection.status().equals(realSection.status()),
            "sourceChanged", fakeSection.source() != realSection.source(),
            "summaryChanged", !String.valueOf(fakeSection.summary()).equals(String.valueOf(realSection.summary()))
        );
    }

    private Map<String, Object> sectionComparisonSummary(DemoRunSectionView section) {
        return orderedMap(
            "sectionId", section.sectionId(),
            "status", section.status(),
            "source", section.source().name(),
            "summary", DemoRunRedactor.sanitize(section.summary())
        );
    }

    private DemoRunSectionView comparisonErrorsSection(DemoRunResult fakeBaseline, DemoRunResult realRun) {
        var diagnostics = mergeDiagnostics(fakeBaseline, realRun).stream()
            .map(diagnostic -> orderedMap(
                "code", diagnostic.code(),
                "category", diagnosticCategory(diagnostic.code()),
                "severity", diagnostic.severity(),
                "message", DemoRunRedactor.sanitizeText(diagnostic.message()),
                "metadata", diagnostic.metadata()
            ))
            .toList();
        return new DemoRunSectionView(
            "errors",
            "Errors",
            DemoRunSectionSource.APPLICATION,
            diagnostics.isEmpty() ? "NONE" : "COMPARISON_WARNINGS",
            orderedMap(
                "hasErrors", !diagnostics.isEmpty(),
                "diagnostics", diagnostics
            )
        );
    }

    private List<DemoRunArtifactReference> mergeArtifacts(
        List<DemoRunArtifactReference> comparisonArtifacts,
        DemoRunResult fakeBaseline,
        DemoRunResult realRun
    ) {
        var artifacts = new java.util.ArrayList<DemoRunArtifactReference>();
        artifacts.addAll(comparisonArtifacts);
        artifacts.addAll(fakeBaseline.artifacts());
        artifacts.addAll(realRun.artifacts());
        return List.copyOf(artifacts);
    }

    private List<DemoRunDiagnosticView> mergeDiagnostics(DemoRunResult fakeBaseline, DemoRunResult realRun) {
        var diagnostics = new java.util.ArrayList<DemoRunDiagnosticView>();
        diagnostics.addAll(fakeBaseline.diagnostics());
        diagnostics.addAll(realRun.diagnostics());
        return List.copyOf(diagnostics);
    }

    private DemoRunSectionView errorsSection(ManualSuiteAgentRunResult result) {
        var diagnostics = result.diagnostics().stream()
            .map(diagnostic -> orderedMap(
                "code", diagnostic.code(),
                "category", diagnosticCategory(diagnostic.code()),
                "severity", diagnostic.severity(),
                "message", DemoRunRedactor.sanitizeText(diagnostic.message()),
                "metadata", DemoRunRedactor.sanitize(diagnostic.metadata())
            ))
            .toList();
        return new DemoRunSectionView(
            "errors",
            "Errors",
            DemoRunSectionSource.APPLICATION,
            diagnostics.isEmpty() ? "NONE" : result.status().name(),
            orderedMap(
                "hasErrors", !diagnostics.isEmpty(),
                "diagnostics", diagnostics
            )
        );
    }

    private String diagnosticCategory(String code) {
        if (code == null || code.isBlank()) {
            return "SYSTEM";
        }
        if (code.contains("FIXTURE")) {
            return "FIXTURE";
        }
        if (code.contains("PROVIDER") || code.contains("LLM")) {
            return "CONFIGURATION";
        }
        if (code.contains("POLICY")) {
            return "POLICY";
        }
        return "SYSTEM";
    }

    private DemoRunSectionView planSection(ManualSuiteAgentRunResult result) {
        var discovery = section(result, "business-flow-discovery");
        var summary = orderedMap(
            "planner", "Manual Suite Agent Harness",
            "sourceSectionId", discovery == null ? null : discovery.sectionId(),
            "status", discovery == null ? result.status().name() : discovery.status(),
            "providerMode", result.providerMode().name(),
            "fixtureId", result.fixtureId(),
            "runProfile", result.runProfile(),
            "businessFlowDiscovery", discovery == null ? Map.of() : DemoRunRedactor.sanitize(discovery.summary())
        );
        return new DemoRunSectionView("plan", "Plan", DemoRunSectionSource.APPLICATION, status(summary), summary);
    }

    private DemoRunSectionView contextSection(ManualSuiteAgentRunResult result) {
        var taskContext = section(result, "task-context");
        var apiSpecInput = section(result, "api-spec-input");
        var variableAudit = section(result, "variable-audit");
        var contextSources = List.of(
            contextSource("TASK_MEMORY", taskContext),
            contextSource("API_CONTEXT", apiSpecInput),
            contextSource("RUNTIME_CONTEXT", variableAudit),
            orderedMap(
                "contextType", "KNOWLEDGE_RAG",
                "sourceSectionId", "business-flow-discovery",
                "status", sectionStatus(result, "business-flow-discovery"),
                "note", "Fixture-backed knowledge context from the existing Manual Suite Agent Harness."
            ),
            orderedMap(
                "contextType", "LONG_TERM_MEMORY",
                "sourceSectionId", "business-flow-discovery",
                "status", sectionStatus(result, "business-flow-discovery"),
                "note", "Fixture-backed long-term memory context from the existing Manual Suite Agent Harness."
            )
        );
        return new DemoRunSectionView(
            "context",
            "Context",
            DemoRunSectionSource.APPLICATION,
            "READY",
            orderedMap(
                "builder", "V4 demo view model over existing Manual Suite Agent Harness context",
                "usesRealEmbedding", false,
                "contextSources", contextSources
            )
        );
    }

    private DemoRunSectionView toolsSection(ManualSuiteAgentRunResult result) {
        var toolCalls = List.of(
            toolCall(result, "business-flow-discovery", "BusinessFlowDiscoveryService.discover"),
            toolCall(result, "generated-suite-draft", "SuiteDraftGenerationService.generate"),
            toolCall(result, "execution-result", "ExecutionContext + fake HTTP gateway"),
            toolCall(result, "variable-audit", "ExecutionContext variable audit"),
            toolCall(result, "failure-analysis", "Suite failure analysis"),
            toolCall(result, "memory-feedback", "AgentMemoryFeedbackApplicationService"),
            toolCall(result, "evaluation-comparison", "AgentEvaluationApplicationService")
        );
        return new DemoRunSectionView(
            "tools",
            "Tools",
            DemoRunSectionSource.APPLICATION,
            "READY",
            orderedMap(
                "orchestrator", "Manual Suite Agent Harness",
                "reusedExistingHarness", true,
                "usesExternalHttp", result.usesExternalHttp(),
                "toolCalls", toolCalls
            )
        );
    }

    private Map<String, Object> contextSource(String contextType, ManualSuiteAgentSectionSummary section) {
        return orderedMap(
            "contextType", contextType,
            "sourceSectionId", section == null ? null : section.sectionId(),
            "status", section == null ? "NOT_RUN" : section.status(),
            "summary", section == null ? Map.of() : DemoRunRedactor.sanitize(section.summary())
        );
    }

    private Map<String, Object> toolCall(
        ManualSuiteAgentRunResult result,
        String sourceSectionId,
        String toolName
    ) {
        var section = section(result, sourceSectionId);
        return orderedMap(
            "toolName", toolName,
            "sourceSectionId", sourceSectionId,
            "status", section == null ? "NOT_RUN" : section.status(),
            "source", section == null ? DemoRunSectionSource.NOT_RUN.name() : toDemoSource(section.source()).name(),
            "usesExternalHttp", result.usesExternalHttp()
        );
    }

    private DemoRunSectionView memoryFeedbackSection(
        DemoRunRequest request,
        ManualSuiteAgentRunResult result,
        DemoRunProviderMode providerMode
    ) {
        var section = section(result, "memory-feedback");
        if (section == null) {
            return DemoRunSectionView.notRun("memory-feedback", "Memory Feedback");
        }
        var summary = orderedMap(
            "sourceSectionId", section.sectionId(),
            "sourceTitle", section.title(),
            "summary", DemoRunRedactor.sanitize(section.summary()),
            "memoryWrite", memoryWriteSafety(request, result, providerMode, section)
        );
        return new DemoRunSectionView(
            "memory-feedback",
            "Memory Feedback",
            toDemoSource(section.source()),
            section.status(),
            summary
        );
    }

    private Map<String, Object> memoryWriteSafety(
        DemoRunRequest request,
        ManualSuiteAgentRunResult result,
        DemoRunProviderMode providerMode,
        ManualSuiteAgentSectionSummary section
    ) {
        var explicitlyAllowed = request.allowMemoryWrite()
            && providerMode == DemoRunProviderMode.REAL
            && "manual-real-llm".equals(request.runProfile());
        return orderedMap(
            "requested", request.allowMemoryWrite(),
            "enabled", explicitlyAllowed,
            "actualWritePerformed", false,
            "comparisonMode", request.comparisonEnabled() || request.providerMode() == DemoRunProviderMode.COMPARISON,
            "providerMode", providerMode.name(),
            "runProfile", result.runProfile(),
            "decision", explicitlyAllowed ? "EXPLICITLY_ALLOWED_BUT_DEMO_HARNESS_DOES_NOT_PERSIST" : "DISABLED_BY_DEFAULT",
            "sourceEvidence", List.of(orderedMap(
                "sourceSectionId", section.sectionId(),
                "redacted", true,
                "summary", DemoRunRedactor.sanitize(section.summary())
            ))
        );
    }

    private DemoRunSectionView mappedSection(
        ManualSuiteAgentRunResult result,
        String harnessSectionId,
        String demoSectionId,
        String title
    ) {
        var section = section(result, harnessSectionId);
        if (section == null) {
            return DemoRunSectionView.notRun(demoSectionId, title);
        }
        return new DemoRunSectionView(
            demoSectionId,
            title,
            toDemoSource(section.source()),
            section.status(),
            orderedMap(
                "sourceSectionId", section.sectionId(),
                "sourceTitle", section.title(),
                "summary", DemoRunRedactor.sanitize(section.summary())
            )
        );
    }

    private ManualSuiteAgentSectionSummary section(ManualSuiteAgentRunResult result, String sectionId) {
        return result.sections().stream()
            .filter(section -> section.sectionId().equals(sectionId))
            .findFirst()
            .orElse(null);
    }

    private String sectionStatus(ManualSuiteAgentRunResult result, String sectionId) {
        var found = section(result, sectionId);
        return found == null ? "NOT_RUN" : found.status();
    }

    private DemoRunProviderMode toDemoProviderMode(ManualSuiteAgentProviderMode providerMode) {
        return providerMode == ManualSuiteAgentProviderMode.MANUAL_REAL_LLM
            ? DemoRunProviderMode.REAL
            : DemoRunProviderMode.FAKE;
    }

    private DemoRunStatus toDemoStatus(ManualSuiteAgentRunStatus status) {
        return switch (status) {
            case COMPLETED -> DemoRunStatus.COMPLETED;
            case BLOCKED -> DemoRunStatus.REJECTED;
            case FAILED -> DemoRunStatus.FAILED;
        };
    }

    private DemoRunSectionSource toDemoSource(ManualSuiteAgentSectionSource source) {
        return switch (source) {
            case REAL -> DemoRunSectionSource.REAL_APPLICATION;
            case FIXTURE -> DemoRunSectionSource.FIXTURE;
            case STAGED -> DemoRunSectionSource.APPLICATION;
            case PENDING_RUNTIME, NOT_RUN -> DemoRunSectionSource.NOT_RUN;
        };
    }

    private DemoRunArtifactReference artifactView(ManualSuiteAgentArtifactReference artifact) {
        return new DemoRunArtifactReference(
            artifact.artifactType(),
            artifact.path(),
            artifact.mediaType(),
            DemoRunRedactor.sanitizeMap(artifact.metadata())
        );
    }

    private DemoRunDiagnosticView diagnosticView(ManualSuiteAgentDiagnostic diagnostic) {
        return new DemoRunDiagnosticView(
            diagnostic.code(),
            diagnostic.severity(),
            DemoRunRedactor.sanitizeText(diagnostic.message()),
            DemoRunRedactor.sanitizeMap(diagnostic.metadata())
        );
    }

    private String status(Map<String, Object> summary) {
        var status = summary.get("status");
        return status == null ? "READY" : status.toString();
    }

    private Map<String, Object> orderedMap(Object... values) {
        var map = new LinkedHashMap<String, Object>();
        for (var index = 0; index < values.length; index += 2) {
            map.put(values[index].toString(), values[index + 1]);
        }
        return map;
    }
}
