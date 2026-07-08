package com.probeflow.testagent.manualsuiteagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowCandidate;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryBlocker;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryEvidence;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryProviderMode;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryRequest;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryResult;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryService;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowDiscoveryStep;
import com.probeflow.testagent.businessflowdiscovery.BusinessFlowSourceCoverage;
import com.probeflow.testagent.httpexecution.HttpClientRequest;
import com.probeflow.testagent.httpexecution.HttpClientResponse;
import com.probeflow.testagent.httpexecution.HttpExecutionOptions;
import com.probeflow.testagent.httpexecution.HttpExecutionRequest;
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.suiteruntime.DynamicValueProvider;
import com.probeflow.testagent.suiteruntime.ExecutionContext;
import com.probeflow.testagent.suiteruntime.ResponseExtractor;
import com.probeflow.testagent.suiteruntime.RuntimeRedactor;
import com.probeflow.testagent.suiteruntime.VariableResolver;
import com.probeflow.testagent.suiteruntime.VariableWriteBackService;
import com.probeflow.testagent.suitedraft.SuiteDraftGenerationRequest;
import com.probeflow.testagent.suitedraft.SuiteDraftGenerationResult;
import com.probeflow.testagent.suitedraft.SuiteDraftGenerationService;
import com.probeflow.testagent.suitedraft.SuiteDraftProviderMode;
import com.probeflow.testagent.suitedraft.SuiteDraftStep;
import com.probeflow.testagent.suitedraft.SuiteExtractRule;
import com.probeflow.testagent.suitedraft.SuiteReadinessDiagnostic;
import com.probeflow.testagent.suitedraft.SuiteVariableDependency;
import com.probeflow.testagent.suitedraft.SuiteVariableReference;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.testcase.CaseCategory;
import com.probeflow.testagent.testcase.CasePriority;
import com.probeflow.testagent.testcase.CaseRiskLevel;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcase.CaseStatus;
import com.probeflow.testagent.testcase.DetailType;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ManualSuiteAgentHarness {

    private final ManualSuiteAgentFixtureRegistry fixtureRegistry;
    private final ManualSuiteAgentReportWriter reportWriter;
    private final ManualSuiteAgentFakeHttpGateway fakeHttpGateway = new ManualSuiteAgentFakeHttpGateway();
    private final BusinessFlowDiscoveryService businessFlowDiscoveryService = new BusinessFlowDiscoveryService();
    private final SuiteDraftGenerationService suiteDraftGenerationService = new SuiteDraftGenerationService();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final VariableResolver variableResolver = new VariableResolver(new DynamicValueProvider());
    private final ResponseExtractor responseExtractor = new ResponseExtractor(objectMapper);
    private final VariableWriteBackService variableWriteBackService = new VariableWriteBackService();

    public ManualSuiteAgentHarness(
        ManualSuiteAgentFixtureRegistry fixtureRegistry,
        ManualSuiteAgentReportWriter reportWriter
    ) {
        this.fixtureRegistry = fixtureRegistry;
        this.reportWriter = reportWriter;
    }

    public static ManualSuiteAgentHarness defaults() {
        return new ManualSuiteAgentHarness(
            ManualSuiteAgentFixtureRegistry.defaults(),
            new ManualSuiteAgentReportWriter()
        );
    }

    public ManualSuiteAgentRunResult run(ManualSuiteAgentRunRequest request) {
        var startedAt = Instant.now();
        if (request.providerMode() == ManualSuiteAgentProviderMode.UNSUPPORTED) {
            var completedAt = Instant.now();
            return writeBestEffort(baseResult(
                request,
                null,
                ManualSuiteAgentRunStatus.FAILED,
                startedAt,
                completedAt,
                List.of(),
                List.of(ManualSuiteAgentDiagnostic.error(
                    "INVALID_PROVIDER_MODE",
                    "Provider mode is not supported: " + request.requestedProviderMode(),
                    Map.of("requestedProviderMode", request.requestedProviderMode())
                )),
                Map.of()
            ), request);
        }

        var fixture = fixtureRegistry.findById(request.fixtureId());
        if (fixture.isEmpty()) {
            var completedAt = Instant.now();
            return writeBestEffort(baseResult(
                request,
                null,
                ManualSuiteAgentRunStatus.FAILED,
                startedAt,
                completedAt,
                List.of(),
                List.of(ManualSuiteAgentDiagnostic.error(
                    "FIXTURE_NOT_FOUND",
                    "Fixture not found: " + request.fixtureId(),
                    Map.of("fixtureId", request.fixtureId())
                )),
                Map.of()
            ), request);
        }

        var fixtureValue = fixture.get();
        var invalidFixtureDiagnostic = validateFixture(fixtureValue);
        if (invalidFixtureDiagnostic != null) {
            var completedAt = Instant.now();
            return writeBestEffort(baseResult(
                request,
                fixtureValue,
                ManualSuiteAgentRunStatus.FAILED,
                startedAt,
                completedAt,
                List.of(),
                List.of(invalidFixtureDiagnostic),
                Map.of()
            ), request);
        }

        if (request.providerMode() == ManualSuiteAgentProviderMode.MANUAL_REAL_LLM
            && !request.allowManualProvider()) {
            var completedAt = Instant.now();
            return writeBestEffort(baseResult(
                request,
                fixtureValue,
                ManualSuiteAgentRunStatus.BLOCKED,
                startedAt,
                completedAt,
                List.of(),
                List.of(ManualSuiteAgentDiagnostic.error(
                    "PROVIDER_BLOCKED",
                    "Manual LLM provider mode requires explicit --allow-manual-real-llm.",
                    Map.of(
                        "fixtureId", request.fixtureId(),
                        "providerMode", request.providerMode().name(),
                        "allowManualProvider", request.allowManualProvider()
                    )
                )),
                Map.of()
            ), request);
        }

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("harnessScope", "v3-1-manual-suite-agent-harness");
        var sections = baseSections(request, fixtureValue);
        if ("order-suite-demo".equals(fixtureValue.metadata().get("fixtureType"))) {
            var orderRun = runOrderSuiteFixture(request, fixtureValue);
            metadata.putAll(orderRun.metadata());
            sections = orderRun.sections();
        }
        var completedAt = Instant.now();
        var result = baseResult(
            request,
            fixtureValue,
            ManualSuiteAgentRunStatus.COMPLETED,
            startedAt,
            completedAt,
            sections,
            List.of(),
            metadata
        );
        return writeBestEffort(result, request);
    }

    private ManualSuiteAgentDiagnostic validateFixture(ManualSuiteAgentFixture fixture) {
        var missingFields = new ArrayList<String>();
        if (fixture.fixtureId() == null || fixture.fixtureId().isBlank()) {
            missingFields.add("fixtureId");
        }
        if (fixture.fixtureVersion() == null || fixture.fixtureVersion().isBlank()) {
            missingFields.add("fixtureVersion");
        }
        if (fixture.displayName() == null || fixture.displayName().isBlank()) {
            missingFields.add("displayName");
        }
        if (missingFields.isEmpty()) {
            return null;
        }
        return ManualSuiteAgentDiagnostic.error(
            "FIXTURE_INVALID",
            "Fixture is invalid: missing required fields " + missingFields,
            Map.of(
                "fixtureId", fixture.fixtureId(),
                "missingFields", missingFields,
                "fixtureMetadata", fixture.metadata()
            )
        );
    }

    private OrderFixtureRun runOrderSuiteFixture(ManualSuiteAgentRunRequest request, ManualSuiteAgentFixture fixture) {
        var apiSpecs = orderApiSpecs();
        var discoveryResult = businessFlowDiscoveryService.discover(new BusinessFlowDiscoveryRequest(
            fixture.fixtureId(),
            apiSpecs,
            orderKnowledgeEntries(),
            orderMemoryHits(),
            List.of(),
            BusinessFlowDiscoveryProviderMode.DETERMINISTIC_FAKE,
            false,
            List.of(),
            request.runProfile(),
            Map.of("harnessFixture", fixture.fixtureId())
        ));
        var suiteDraftResult = generateSuiteDraft(request, fixture, apiSpecs, discoveryResult);
        var task = orderTask(apiSpecs.stream().map(ApiSpec::getApiSpecId).toList());
        var testCase = orderSuiteTestCase(apiSpecs, suiteDraftResult);
        var failureScenario = stringValue(fixture.metadata().get("failureScenario"));
        applyFailureScenario(testCase, failureScenario);
        var executionSummary = executeOrderSuite(task, testCase, failureScenario);
        var suiteDraftSummary = generatedSuiteDraftSummary(suiteDraftResult);
        var failureAnalysisSection = failureAnalysisSection(executionSummary, failureScenario);

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("task", taskSummary(task));
        metadata.put("apiSpecs", apiSpecs.stream().map(this::apiSpecSummary).toList());
        metadata.put("testCases", List.of(testCaseSummary(testCase)));
        metadata.put("businessFlowDiscovery", businessFlowDiscoverySummary(discoveryResult));
        metadata.put("generatedSuiteDraft", suiteDraftSummary);
        metadata.put("executionSummary", executionSummary);
        metadata.put("failureAnalysis", failureAnalysisSection.summary());

        var sections = new ArrayList<>(baseSections(request, fixture));
        sections.add(new ManualSuiteAgentSectionSummary(
            "task-context",
            "Task context initialized from fixture",
            ManualSuiteAgentSectionSource.REAL,
            "READY",
            taskSummary(task)
        ));
        sections.add(new ManualSuiteAgentSectionSummary(
            "api-spec-input",
            "ApiSpec inputs initialized from fixture",
            ManualSuiteAgentSectionSource.REAL,
            "READY",
            orderedMap("count", apiSpecs.size(), "apiSpecIds", apiSpecs.stream().map(ApiSpec::getApiSpecId).toList())
        ));
        sections.add(new ManualSuiteAgentSectionSummary(
            "business-flow-discovery",
            "Business Flow Discovery candidate flows",
            ManualSuiteAgentSectionSource.REAL,
            discoveryResult.status().name(),
            businessFlowDiscoverySummary(discoveryResult)
        ));
        sections.add(new ManualSuiteAgentSectionSummary(
            "test-case-input",
            "SUITE TestCase input initialized from fixture",
            ManualSuiteAgentSectionSource.REAL,
            "READY",
            testCaseSummary(testCase)
        ));
        sections.add(new ManualSuiteAgentSectionSummary(
            "generated-suite-draft",
            "Generated suite draft summary",
            ManualSuiteAgentSectionSource.REAL,
            suiteDraftResult.readinessStatus().name(),
            suiteDraftSummary
        ));
        sections.add(variableAuditSection(executionSummary));
        sections.add(failureAnalysisSection);
        sections.addAll(v3StagedSections(request, fixture, testCase));
        sections.add(new ManualSuiteAgentSectionSummary(
            "execution-result",
            "ExecutionContext runtime fake HTTP execution summary",
            ManualSuiteAgentSectionSource.REAL,
            stringValue(executionSummary.getOrDefault("status", "PASSED")),
            executionSummary
        ));
        return new OrderFixtureRun(sections, metadata);
    }

    @SuppressWarnings("unchecked")
    private void applyFailureScenario(TestCase testCase, String failureScenario) {
        if (!"variable-resolution-failure".equals(failureScenario)) {
            return;
        }
        for (var step : testCase.getSteps()) {
            if (!"pay-order".equals(step.get("stepId"))) {
                continue;
            }
            var requestTemplate = new LinkedHashMap<>(objectMap(step.get("requestTemplate")));
            requestTemplate.put("path", "/api/orders/${suite.missingOrderId}/payments");
            step.put("requestTemplate", requestTemplate);
            var references = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) step.getOrDefault("variableReferences", List.of()));
            references.add(orderedMap(
                "consumerStepId", "pay-order",
                "consumerLocation", "PATH",
                "consumerField", "orderId",
                "targetScope", "suite",
                "targetKey", "missingOrderId",
                "referenceExpression", "${suite.missingOrderId}",
                "sourceDependencyId", "fixture-missing-order-id"
            ));
            step.put("variableReferences", references);
        }
    }

    @SuppressWarnings("unchecked")
    private ManualSuiteAgentSectionSummary variableAuditSection(Map<String, Object> executionSummary) {
        var auditSummary = objectMap(executionSummary.get("variableAuditSummary"));
        var auditEvents = (List<Map<String, Object>>) auditSummary.getOrDefault("events", List.of());
        var diagnostics = (List<Map<String, Object>>) executionSummary.getOrDefault("runtimeDiagnostics", List.of());
        var summary = orderedMap(
            "sourceMarker", "real",
            "runtime", "ExecutionContext",
            "phaseNote", "V3-4 ExecutionContext runtime produced this variable audit from the V3-3 generated suite draft.",
            "status", diagnostics.isEmpty() ? "PASSED" : "REVIEW",
            "auditEvents", auditEvents,
            "producerHighlights", producerHighlights(auditEvents),
            "consumerHighlights", consumerHighlights(auditEvents),
            "variableAuditSummary", auditSummary,
            "contextSummary", executionSummary.getOrDefault("contextSummary", Map.of()),
            "runtimeDiagnostics", diagnostics
        );
        return new ManualSuiteAgentSectionSummary(
            "variable-audit",
            "ExecutionContext variable audit",
            ManualSuiteAgentSectionSource.REAL,
            diagnostics.isEmpty() ? "PASSED" : "REVIEW",
            summary
        );
    }

    @SuppressWarnings("unchecked")
    private ManualSuiteAgentSectionSummary failureAnalysisSection(
        Map<String, Object> executionSummary,
        String failureScenario
    ) {
        var steps = (List<Map<String, Object>>) executionSummary.getOrDefault("stepResults", List.of());
        var diagnostics = (List<Map<String, Object>>) executionSummary.getOrDefault("runtimeDiagnostics", List.of());
        var auditSummary = objectMap(executionSummary.get("variableAuditSummary"));
        var auditEvents = (List<Map<String, Object>>) auditSummary.getOrDefault("events", List.of());
        var rootStep = firstProblemStep(steps);
        var affectedSteps = affectedSkippedSteps(steps, rootStep);
        var classification = failureClassification(rootStep, affectedSteps, diagnostics, auditEvents, failureScenario);
        var riskLevel = "NONE".equals(classification) ? "LOW" : "HIGH";
        var confidence = "NONE".equals(classification) ? "1.00" : "0.90";
        var nextSuggestion = failureNextSuggestion(classification, rootStep);
        var evidence = failureEvidence(executionSummary, rootStep, affectedSteps, diagnostics, auditEvents, classification);
        var rootStepId = rootStep == null ? null : stringValue(rootStep.get("stepId"));
        var affectedStepIds = affectedSteps.stream()
            .map(step -> stringValue(step.get("stepId")))
            .toList();
        var summary = orderedMap(
            "sourceMarker", "real",
            "phaseNote", "V3-5 failure-analysis section generated from the current Manual Suite Agent fake runtime execution.",
            "analysisMode", "BASIC",
            "classification", classification,
            "rootStep", rootStepId,
            "rootCause", rootCauseSummary(classification, rootStep, diagnostics),
            "affectedSteps", affectedStepIds,
            "affectedDownstreamSteps", affectedStepIds,
            "evidence", evidence,
            "riskLevel", riskLevel,
            "confidence", confidence,
            "requiresHumanReview", !"NONE".equals(classification),
            "nextSuggestion", nextSuggestion,
            "recoveryActionType", recoveryActionType(classification),
            "suiteFailureAnalysis", orderedMap(
                "suiteExecution", true,
                "classification", classification,
                "rootCauseStep", stepSummary(rootStep),
                "affectedDownstreamSteps", affectedSteps.stream().map(this::stepSummary).toList(),
                "variableFailure", variableFailureSummary(classification, diagnostics, auditEvents),
                "impactSummary", impactSummary(classification, rootStepId, affectedStepIds)
            ),
            "replanningHandoff", orderedMap(
                "available", !"NONE".equals(classification),
                "sourceStepId", rootStepId,
                "affectedDownstreamStepIds", affectedStepIds,
                "recoveryActionType", recoveryActionType(classification),
                "nextSuggestion", nextSuggestion,
                "policyNotes", List.of("PolicyValidator and Human-in-the-loop gates remain required before any recovery is applied.")
            ),
            "humanHandoff", orderedMap(
                "required", !"NONE".equals(classification),
                "reason", "NONE".equals(classification) ? "No failure follow-up is required." : "Manual review can confirm the proposed recovery before replanning.",
                "suggestedAction", nextSuggestion
            )
        );
        return new ManualSuiteAgentSectionSummary(
            "failure-analysis",
            "Suite failure analysis",
            ManualSuiteAgentSectionSource.REAL,
            "NONE".equals(classification) ? "PASSED" : "REVIEW",
            summary
        );
    }

    private Map<String, Object> firstProblemStep(List<Map<String, Object>> steps) {
        return steps.stream()
            .filter(step -> {
                var status = stringValue(step.get("status"));
                return "FAILED".equals(status) || "ERROR".equals(status) || "BLOCKED".equals(status);
            })
            .findFirst()
            .orElse(null);
    }

    private List<Map<String, Object>> affectedSkippedSteps(
        List<Map<String, Object>> steps,
        Map<String, Object> rootStep
    ) {
        if (rootStep == null) {
            return List.of();
        }
        var rootOrder = intValue(rootStep.get("order"));
        return steps.stream()
            .filter(step -> "SKIPPED".equals(stringValue(step.get("status"))))
            .filter(step -> intValue(step.get("order")) > rootOrder)
            .toList();
    }

    private String failureClassification(
        Map<String, Object> rootStep,
        List<Map<String, Object>> affectedSteps,
        List<Map<String, Object>> diagnostics,
        List<Map<String, Object>> auditEvents,
        String failureScenario
    ) {
        if (rootStep == null) {
            return "NONE";
        }
        if ("variable-extraction-failure".equals(failureScenario) || diagnostics.stream().anyMatch(this::extractionDiagnostic)) {
            return "VARIABLE_EXTRACTION_FAILURE";
        }
        if ("variable-resolution-failure".equals(failureScenario) || diagnostics.stream().anyMatch(this::resolutionDiagnostic)) {
            return "VARIABLE_RESOLUTION_FAILURE";
        }
        if ("FAILED".equals(stringValue(rootStep.get("status")))
            && intValue(rootStep.get("order")) > 1
            && auditEvents.stream().anyMatch(event -> stringValue(rootStep.get("stepId")).equals(stringValue(event.get("stepId")))
                && "CONSUMPTION".equals(stringValue(event.get("eventType")))
                && Boolean.TRUE.equals(event.get("resolved")))) {
            return "DOWNSTREAM_API_FAILURE";
        }
        if (!affectedSteps.isEmpty()) {
            return "PREREQUISITE_STEP_FAILURE";
        }
        return "UNKNOWN";
    }

    private boolean extractionDiagnostic(Map<String, Object> diagnostic) {
        return diagnostic.containsKey("sourcePath")
            || diagnostic.containsKey("targetKey")
            || diagnostic.containsKey("sourceType");
    }

    private boolean resolutionDiagnostic(Map<String, Object> diagnostic) {
        return diagnostic.containsKey("expression")
            || diagnostic.containsKey("location")
            || diagnostic.containsKey("path");
    }

    private String failureNextSuggestion(String classification, Map<String, Object> rootStep) {
        return switch (classification) {
            case "VARIABLE_EXTRACTION_FAILURE" -> "Check response field path, extractRule source mapping, and upstream response shape before rerunning downstream steps.";
            case "VARIABLE_RESOLUTION_FAILURE" -> "Provide the missing variable, fix the variable reference, or move the producing suite step before this consumer.";
            case "DOWNSTREAM_API_FAILURE" -> "Inspect the downstream API response and service health; prerequisite variables were already produced and consumed.";
            case "PREREQUISITE_STEP_FAILURE" -> "Inspect suite prerequisite step " + stringValue(rootStep == null ? null : rootStep.get("stepId"))
                + " before treating downstream skipped steps as independent failures.";
            case "NONE" -> "No failure follow-up is required.";
            default -> "Review deterministic failure analysis evidence.";
        };
    }

    private String recoveryActionType(String classification) {
        return switch (classification) {
            case "VARIABLE_EXTRACTION_FAILURE" -> "FIX_EXTRACT_RULE";
            case "VARIABLE_RESOLUTION_FAILURE" -> "FIX_VARIABLE_REFERENCE";
            case "PREREQUISITE_STEP_FAILURE", "DOWNSTREAM_API_FAILURE" -> "WAIT_FOR_SERVICE_OR_DATA_FIX";
            case "NONE" -> "NO_ACTION";
            default -> "WAIT_FOR_HUMAN";
        };
    }

    private List<String> failureEvidence(
        Map<String, Object> executionSummary,
        Map<String, Object> rootStep,
        List<Map<String, Object>> affectedSteps,
        List<Map<String, Object>> diagnostics,
        List<Map<String, Object>> auditEvents,
        String classification
    ) {
        var evidence = new ArrayList<String>();
        evidence.add("suiteStatus=" + executionSummary.get("status"));
        evidence.add("classification=" + classification);
        if (rootStep != null) {
            evidence.add("rootStep=" + rootStep.get("stepId") + " status=" + rootStep.get("status") + " statusCode=" + rootStep.get("statusCode"));
        }
        if (!affectedSteps.isEmpty()) {
            evidence.add("affectedDownstreamSteps=" + affectedSteps.stream().map(step -> stringValue(step.get("stepId"))).toList());
        }
        diagnostics.stream()
            .limit(3)
            .map(diagnostic -> "runtimeDiagnostic=" + diagnostic.get("code")
                + " stepId=" + diagnostic.get("stepId")
                + " targetKey=" + diagnostic.get("targetKey")
                + " expression=" + diagnostic.get("expression"))
            .forEach(evidence::add);
        auditEvents.stream()
            .filter(event -> Boolean.FALSE.equals(event.get("success")) || Boolean.FALSE.equals(event.get("resolved")))
            .limit(3)
            .map(event -> "variableAuditEvent=" + event.get("eventType")
                + " stepId=" + event.get("stepId")
                + " targetKey=" + event.get("targetKey")
                + " expression=" + event.get("expression"))
            .forEach(evidence::add);
        return List.copyOf(evidence);
    }

    private Map<String, Object> rootCauseSummary(
        String classification,
        Map<String, Object> rootStep,
        List<Map<String, Object>> diagnostics
    ) {
        return orderedMap(
            "classification", classification,
            "stepId", rootStep == null ? null : rootStep.get("stepId"),
            "status", rootStep == null ? null : rootStep.get("status"),
            "statusCode", rootStep == null ? null : rootStep.get("statusCode"),
            "diagnostics", diagnostics.stream().limit(3).toList()
        );
    }

    private Map<String, Object> stepSummary(Map<String, Object> step) {
        if (step == null) {
            return Map.of();
        }
        return orderedMap(
            "stepId", step.get("stepId"),
            "order", step.get("order"),
            "apiSpecId", step.get("apiSpecId"),
            "status", step.get("status"),
            "statusCode", step.get("statusCode"),
            "message", firstPresent(step, "message", "skipReason")
        );
    }

    private Map<String, Object> variableFailureSummary(
        String classification,
        List<Map<String, Object>> diagnostics,
        List<Map<String, Object>> auditEvents
    ) {
        if (!classification.startsWith("VARIABLE_")) {
            return Map.of();
        }
        var diagnostic = diagnostics.stream().findFirst().orElse(Map.of());
        var event = auditEvents.stream()
            .filter(item -> Boolean.FALSE.equals(item.get("success")) || Boolean.FALSE.equals(item.get("resolved")))
            .findFirst()
            .orElse(Map.of());
        return orderedMap(
            "classification", classification,
            "stepId", firstPresent(diagnostic, "stepId"),
            "expression", firstPresent(diagnostic, "expression", "referenceExpression"),
            "location", diagnostic.get("location"),
            "sourceType", diagnostic.get("sourceType"),
            "sourcePath", diagnostic.get("sourcePath"),
            "targetScope", firstPresent(diagnostic, "targetScope", "scope"),
            "targetKey", firstPresent(diagnostic, "targetKey", "path"),
            "failureReason", firstPresent(event, "failureReason", "code")
        );
    }

    private String impactSummary(String classification, String rootStepId, List<String> affectedStepIds) {
        if ("NONE".equals(classification)) {
            return "Suite execution passed; no downstream failure impact.";
        }
        return "Suite root step " + rootStepId
            + " classified as " + classification
            + " affected downstream steps " + affectedStepIds + ".";
    }

    private List<ManualSuiteAgentSectionSummary> v3StagedSections(
        ManualSuiteAgentRunRequest request,
        ManualSuiteAgentFixture fixture,
        TestCase testCase
    ) {
        return List.of(
            new ManualSuiteAgentSectionSummary(
                "memory-feedback",
                "Memory feedback summary integration slot",
                ManualSuiteAgentSectionSource.STAGED,
                "STAGED",
                orderedMap(
                    "sourceMarker", "staged",
                    "phaseNote", "Memory feedback slot awaits V3-6.",
                    "candidateCount", 1,
                    "sourceType", "FIXTURE_EXECUTION_SUMMARY",
                    "tags", List.of("order-suite", "fake-http", "happy-path"),
                    "confidence", "0.80",
                    "learningNote", "Successful order payment chains should query final order status after payment."
                )
            ),
            new ManualSuiteAgentSectionSummary(
                "evaluation-comparison",
                "Agent evaluation comparison integration slot",
                ManualSuiteAgentSectionSource.NOT_RUN,
                "NOT_RUN",
                orderedMap(
                    "sourceMarker", "not-run",
                    "phaseNote", "V3 Agent Evaluation comparison awaits V3-6 and remains non-required for default CI.",
                    "providerMode", request.providerMode().name(),
                    "fixtureId", fixture.fixtureId(),
                    "capabilityTags", fixture.capabilityTags(),
                    "expectedMarkers", List.of("suite-draft-present", "fake-http-passed", "no-external-llm", "no-external-http")
                )
            )
        );
    }

    private SuiteDraftGenerationResult generateSuiteDraft(
        ManualSuiteAgentRunRequest request,
        ManualSuiteAgentFixture fixture,
        List<ApiSpec> apiSpecs,
        BusinessFlowDiscoveryResult discoveryResult
    ) {
        var candidate = discoveryResult.candidates().stream()
            .filter(BusinessFlowCandidate::primary)
            .findFirst()
            .or(() -> discoveryResult.candidates().stream().findFirst())
            .orElse(null);
        return suiteDraftGenerationService.generate(new SuiteDraftGenerationRequest(
            fixture.fixtureId(),
            candidate,
            apiSpecs,
            List.of(),
            suiteDraftProviderMode(request.providerMode()),
            request.allowManualProvider(),
            List.of(),
            null,
            request.runProfile(),
            orderedMap("harnessFixture", fixture.fixtureId(), "source", "manual-suite-agent-harness")
        ));
    }

    private SuiteDraftProviderMode suiteDraftProviderMode(ManualSuiteAgentProviderMode providerMode) {
        return providerMode == ManualSuiteAgentProviderMode.MANUAL_REAL_LLM
            ? SuiteDraftProviderMode.MANUAL_REAL_LLM
            : SuiteDraftProviderMode.DETERMINISTIC_FAKE;
    }

    private Map<String, Object> generatedSuiteDraftSummary(SuiteDraftGenerationResult result) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("sourceMarker", "real");
        summary.put("phaseNote", "V3-3 DependencyLinker generated this SUITE draft; V3-4 ExecutionContext runtime consumes it for variable audit.");
        summary.put("schemaVersion", result.schemaVersion());
        summary.put("status", result.status().name());
        summary.put("readinessStatus", result.readinessStatus().name());
        summary.put("providerMode", result.providerMode().name());
        summary.put(ManualSuiteAgentRunResult.USES_REAL_LLM_REPORT_KEY, result.usesManualProvider());
        summary.put("usesExternalHttp", result.usesExternalHttp());
        summary.put("dependencyCount", result.dependencyLinks().size());
        summary.put("diagnosticCount", result.diagnostics().size());
        summary.put("diagnostics", result.diagnostics().stream().map(this::suiteDiagnosticSummary).toList());
        summary.put("blockers", result.blockers().stream().map(this::suiteDiagnosticSummary).toList());
        summary.put("dependencyLinks", result.dependencyLinks().stream().map(this::dependencyLinkSummary).toList());
        summary.put("metadata", result.metadata());
        if (result.draft() == null) {
            summary.put("flowId", null);
            summary.put("scenarioName", null);
            summary.put("stepCount", 0);
            summary.put("steps", List.of());
            summary.put("extractRules", List.of());
            summary.put("variableReferences", List.of());
            return summary;
        }
        summary.put("flowId", result.draft().flowId());
        summary.put("scenarioName", result.draft().scenarioName());
        summary.put("stepCount", result.draft().steps().size());
        summary.put("steps", result.draft().steps().stream()
            .map(step -> orderedMap(
                "stepId", step.stepId(),
                "stepName", step.stepName(),
                "order", step.order(),
                "apiSpecId", step.apiSpecId(),
                "critical", step.critical(),
                "sourceRefs", step.sourceRefs(),
                "dependencyRefs", step.dependencyRefs(),
                "readinessStatus", step.readinessStatus().name(),
                "requestTemplate", step.requestTemplate(),
                "metadata", step.metadata()
            ))
            .toList());
        summary.put("extractRules", result.draft().steps().stream()
            .flatMap(step -> step.extractRules().stream())
            .map(this::extractRuleSummary)
            .toList());
        summary.put("variableReferences", result.draft().steps().stream()
            .flatMap(step -> step.variableReferences().stream())
            .map(this::variableReferenceSummary)
            .toList());
        summary.put("draftMetadata", result.draft().metadata());
        return summary;
    }

    private Map<String, Object> dependencyLinkSummary(SuiteVariableDependency dependency) {
        return orderedMap(
            "dependencyId", dependency.dependencyId(),
            "producerStepId", dependency.producerStepId(),
            "consumerStepId", dependency.consumerStepId(),
            "variableName", dependency.variableName(),
            "sourceType", dependency.sourceType().name(),
            "sourcePath", dependency.sourcePath(),
            "consumerLocation", dependency.consumerLocation().name(),
            "consumerField", dependency.consumerField(),
            "targetScope", dependency.targetScope().name(),
            "targetKey", dependency.targetKey(),
            "referenceExpression", dependency.referenceExpression(),
            "confidence", dependency.confidence(),
            "evidenceRefs", dependency.evidenceRefs(),
            "riskLevel", dependency.riskLevel()
        );
    }

    private Map<String, Object> extractRuleSummary(SuiteExtractRule rule) {
        return orderedMap(
            "ruleId", rule.ruleId(),
            "producerStepId", rule.producerStepId(),
            "sourceType", rule.sourceType().name(),
            "sourcePath", rule.sourcePath(),
            "targetScope", rule.targetScope().name(),
            "targetKey", rule.targetKey(),
            "required", rule.required(),
            "failureStrategy", rule.failureStrategy().name(),
            "description", rule.description(),
            "confidence", rule.confidence(),
            "evidenceRefs", rule.evidenceRefs(),
            "consumerStepIds", rule.consumerStepIds(),
            "sourceDependencyIds", rule.sourceDependencyIds()
        );
    }

    private Map<String, Object> variableReferenceSummary(SuiteVariableReference reference) {
        return orderedMap(
            "consumerStepId", reference.consumerStepId(),
            "consumerLocation", reference.consumerLocation().name(),
            "consumerField", reference.consumerField(),
            "targetScope", reference.targetScope().name(),
            "targetKey", reference.targetKey(),
            "referenceExpression", reference.referenceExpression(),
            "sourceDependencyId", reference.sourceDependencyId(),
            "evidenceRefs", reference.evidenceRefs()
        );
    }

    private Map<String, Object> suiteDiagnosticSummary(SuiteReadinessDiagnostic diagnostic) {
        return orderedMap(
            "code", diagnostic.code(),
            "severity", diagnostic.severity(),
            "affectedSteps", diagnostic.affectedSteps(),
            "affectedDependencyId", diagnostic.affectedDependencyId(),
            "reason", diagnostic.reason(),
            "recommendedAction", diagnostic.recommendedAction(),
            "metadata", diagnostic.metadata()
        );
    }

    private List<ApiSpec> orderApiSpecs() {
        return List.of(
            apiSpec("api-order-create", HttpMethod.POST, "/api/orders", "Create order"),
            apiSpec("api-order-pay", HttpMethod.POST, "/api/orders/{orderId}/payments", "Pay order"),
            apiSpec("api-order-query", HttpMethod.GET, "/api/orders/{orderId}", "Query order")
        );
    }

    private List<KnowledgeContextEntry> orderKnowledgeEntries() {
        return List.of(new KnowledgeContextEntry(
            "chunk-order-flow-001",
            "doc-order-flow",
            "rev-order-flow-v1",
            "Business flow: create order, pay order, query order",
            0.92,
            "business_flow",
            "fixture://order-suite-demo/business-flow.md",
            orderedMap("docType", "business_flow", "module", "order"),
            List.of("explicit step order", "mentions create/pay/query"),
            false
        ));
    }

    private List<LongTermMemoryRetrievalHit> orderMemoryHits() {
        return List.of(new LongTermMemoryRetrievalHit(
            "mem-order-flow",
            MemoryScopeType.TESTING_PATTERN,
            "Successful order payment chains should verify final order status.",
            "Create order before payment, then query the final order status by orderId.",
            "Create order before payment, then query the final order status by orderId.",
            List.of("order", "payment", "business-flow"),
            MemorySourceType.USER_FEEDBACK,
            "fixture-memory://order-suite-demo",
            0.88f,
            0.70f,
            0.65f,
            3,
            Instant.parse("2026-07-01T00:00:00Z"),
            orderedMap("module", "order"),
            42,
            0.91,
            Map.of("tag", 0.5),
            List.of("same order module", "mentions final query"),
            false
        ));
    }

    private ApiSpec apiSpec(String apiSpecId, HttpMethod method, String path, String summary) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setSystemName("ProbeFlow Fixture Commerce");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(method);
        apiSpec.setPath(path);
        apiSpec.setSummary(summary);
        apiSpec.setOperationId(apiSpecId.replace("api-", ""));
        apiSpec.setParameters(orderedMap("pathVariables", List.of("orderId")));
        apiSpec.setConstraints(orderedMap("fixture", true));
        apiSpec.setAuth(orderedMap("type", "bearer", "header", "Authorization", "tokenVariable", "orderAuthToken"));
        apiSpec.setSourceType(ApiSpecSourceType.MANUAL);
        apiSpec.setSourceRef("order-suite-demo");
        apiSpec.setSourceLocation(orderedMap("fixtureId", "order-suite-demo"));
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(true);
        return apiSpec;
    }

    private Task orderTask(List<String> apiSpecIds) {
        var task = new Task();
        task.setTaskId("task-v3-order-suite-demo");
        task.setTaskName("V3 Manual Suite Agent order demo");
        task.setTaskType(TaskType.REGRESSION);
        task.setStatus(TaskStatus.EXECUTING);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("order-suite-demo");
        task.setTargetApiSpecIds(apiSpecIds);
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("manual-suite-agent-harness");
        task.setMetadata(orderedMap(
            "fixtureId", "order-suite-demo",
            "runMode", "deterministic-fake",
            "environment", "fixture-local"
        ));
        return task;
    }

    private TestCase orderSuiteTestCase(List<ApiSpec> apiSpecs, SuiteDraftGenerationResult suiteDraftResult) {
        var testCase = new TestCase();
        testCase.setCaseId("case-order-suite-demo");
        testCase.setPrimaryApiSpecId("api-order-create");
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(TestCaseMode.SUITE);
        testCase.setTitle("Create, pay and query order");
        testCase.setDescription("Fixture-backed order suite for V3 manual harness demo.");
        testCase.setPreconditions(List.of("baseUrl is provided by fixture", "fake auth token is provided by fixture"));
        testCase.setExpectedResult("Order is created, paid and queryable.");
        testCase.setPriority(CasePriority.HIGH);
        testCase.setRiskLevel(CaseRiskLevel.MEDIUM);
        testCase.setTags(List.of("v3-1", "order", "suite", "fake-http"));
        testCase.setScenarioName("Order checkout happy path");
        testCase.setModuleName("order");
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(CaseSource.MANUAL);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(orderedMap(
            "fixtureId", "order-suite-demo",
            "source", "manual-suite-agent-harness",
            "runtimeInput", "generated-suite-draft",
            "draftFlowId", suiteDraftResult.draft() == null ? null : suiteDraftResult.draft().flowId()
        ));
        testCase.setSteps(suiteDraftResult.draft() == null
            ? apiSpecs.stream().map(apiSpec -> suiteStep(apiSpec, apiSpecs.indexOf(apiSpec) + 1)).toList()
            : suiteDraftResult.draft().steps().stream().map(this::suiteStep).toList());
        testCase.setBasedOnApiSpecVersions(orderedMap(
            "api-order-create", 1,
            "api-order-pay", 1,
            "api-order-query", 1
        ));
        return testCase;
    }

    private Map<String, Object> suiteStep(SuiteDraftStep step) {
        return orderedMap(
            "stepId", step.stepId(),
            "order", step.order(),
            "stepName", step.stepName(),
            "apiSpecId", step.apiSpecId(),
            "critical", step.critical(),
            "requestTemplate", step.requestTemplate(),
            "expectedStatus", step.expectedStatus(),
            "assertionHints", step.assertionHints(),
            "extractRules", step.extractRules().stream().map(this::extractRuleRuntime).toList(),
            "variableReferences", step.variableReferences().stream().map(reference -> orderedMap(
                "consumerStepId", reference.consumerStepId(),
                "consumerLocation", reference.consumerLocation().name(),
                "consumerField", reference.consumerField(),
                "targetScope", reference.targetScope().name(),
                "targetKey", reference.targetKey(),
                "referenceExpression", reference.referenceExpression(),
                "sourceDependencyId", reference.sourceDependencyId(),
                "evidenceRefs", reference.evidenceRefs()
            )).toList(),
            "readinessStatus", step.readinessStatus().name(),
            "metadata", step.metadata()
        );
    }

    private Map<String, Object> suiteStep(ApiSpec apiSpec, int order) {
        var stepId = switch (apiSpec.getApiSpecId()) {
            case "api-order-create" -> "create-order";
            case "api-order-pay" -> "pay-order";
            case "api-order-query" -> "query-order";
            default -> apiSpec.getApiSpecId();
        };
        return orderedMap(
            "stepId", stepId,
            "order", order,
            "stepName", apiSpec.getSummary(),
            "apiSpecId", apiSpec.getApiSpecId(),
            "critical", true,
            "requestTemplate", orderedMap(
                "method", apiSpec.getHttpMethod().name(),
                "path", apiSpec.getPath(),
                "headers", orderedMap("Authorization", "Bearer {{orderAuthToken}}", "X-Tenant", "{{tenant}}")
            )
        );
    }

    private Map<String, Object> executeOrderSuite(Task task, TestCase testCase, String failureScenario) {
        var executionRequest = new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "fixture-local",
            false,
            HttpExecutionOptions.defaults(),
            orderedMap("baseUrl", "https://fixture.example.test", "tenant", "tenant-demo"),
            orderedMap("orderAuthToken", "order-demo-token")
        );
        var context = ExecutionContext.create(task, testCase, executionRequest);
        var stepResults = new ArrayList<Map<String, Object>>();
        var responseHighlights = new ArrayList<Map<String, Object>>();
        var totalDurationMs = 0L;
        var halted = false;
        var skipReason = "";
        for (var step : orderedSteps(testCase)) {
            var stepId = step.get("stepId").toString();
            if (halted) {
                stepResults.add(orderedMap(
                    "stepId", stepId,
                    "order", step.get("order"),
                    "apiSpecId", step.get("apiSpecId"),
                    "status", "SKIPPED",
                    "statusCode", null,
                    "durationMs", 0L,
                    "skipReason", skipReason,
                    "contextBefore", context.summary(),
                    "contextAfter", context.summary(),
                    "variableEvents", eventsForStep(context, stepId),
                    "runtimeDiagnostics", diagnosticsForStep(context, stepId)
                ));
                continue;
            }

            var contextBefore = context.summary();
            var requestTemplate = objectMap(step.get("requestTemplate"));
            var resolvedTemplate = variableResolver.resolveRequestTemplate(context, stepId, requestTemplate);
            if (!resolvedTemplate.successful()) {
                var message = "Variable resolution failed for suite step " + stepId;
                stepResults.add(orderedMap(
                    "stepId", stepId,
                    "order", step.get("order"),
                    "apiSpecId", step.get("apiSpecId"),
                    "status", "BLOCKED",
                    "statusCode", null,
                    "durationMs", 0L,
                    "message", message,
                    "requestTemplate", RuntimeRedactor.redact(requestTemplate, "requestTemplate"),
                    "contextBefore", contextBefore,
                    "contextAfter", context.summary(),
                    "variableEvents", eventsForStep(context, stepId),
                    "runtimeDiagnostics", diagnosticsForStep(context, stepId)
                ));
                halted = true;
                skipReason = message;
                continue;
            }

            var clientRequest = clientRequest(objectMap(resolvedTemplate.resolvedValue()), executionRequest);
            var response = fakeHttpGateway.execute(stepId, clientRequest, failureScenario);
            var httpResponse = new HttpClientResponse(
                response.statusCode(),
                orderedMap("Content-Type", "application/json", "X-Fixture-Gateway", "manual-suite-agent"),
                response.body(),
                response.durationMs()
            );
            var extracted = responseExtractor.extract(stepId, step, httpResponse);
            var writeBackBlockingFailure = variableWriteBackService.write(context, stepId, extracted);
            totalDurationMs += response.durationMs();
            var status = writeBackBlockingFailure ? "BLOCKED" : response.statusCode() < 400 ? "PASSED" : "FAILED";
            stepResults.add(orderedMap(
                "stepId", stepId,
                "order", step.get("order"),
                "apiSpecId", step.get("apiSpecId"),
                "status", status,
                "statusCode", response.statusCode(),
                "durationMs", response.durationMs(),
                "requestSnapshot", requestSnapshot(clientRequest),
                "contextBefore", contextBefore,
                "contextAfter", context.summary(),
                "variableEvents", eventsForStep(context, stepId),
                "runtimeDiagnostics", diagnosticsForStep(context, stepId)
            ));
            responseHighlights.add(orderedMap(
                "stepId", stepId,
                "statusCode", response.statusCode(),
                "summary", response.summary(),
                "body", response.body()
            ));
            if (writeBackBlockingFailure) {
                halted = true;
                skipReason = "Required variable extraction failed for suite step " + stepId;
            } else if (response.statusCode() >= 400) {
                halted = true;
                skipReason = "Skipped because prerequisite step failed: " + stepId;
            }
        }
        var passed = countStatus(stepResults, "PASSED");
        var failed = countStatus(stepResults, "FAILED");
        var skipped = countStatus(stepResults, "SKIPPED");
        var blocked = countStatus(stepResults, "BLOCKED");
        return orderedMap(
            "sourceMarker", "real",
            "runtime", "ExecutionContext",
            "runtimeSource", "V3-4 ExecutionContext runtime",
            "runtimeInput", "generated-suite-draft",
            "failureScenario", failureScenario == null || failureScenario.isBlank() ? "none" : failureScenario,
            "draftFlowId", testCase.getDetail() == null ? null : testCase.getDetail().get("draftFlowId"),
            "environment", "fixture-local",
            "gateway", "FAKE_HTTP",
            "usesExternalHttp", false,
            "caseCount", 1,
            "stepCount", testCase.getSteps().size(),
            "status", blocked > 0 ? "BLOCKED" : failed > 0 ? "FAILED" : skipped > 0 ? "SKIPPED" : "PASSED",
            "passed", passed,
            "failed", failed,
            "skipped", skipped,
            "blocked", blocked,
            "totalDurationMs", totalDurationMs,
            "stepResults", stepResults,
            "responseHighlights", responseHighlights,
            "contextSummary", context.summary(),
            "variableAuditSummary", context.variableAuditSummary(),
            "runtimeDiagnostics", context.diagnostics()
        );
    }

    private Map<String, Object> taskSummary(Task task) {
        return orderedMap(
            "taskId", task.getTaskId(),
            "taskName", task.getTaskName(),
            "taskType", task.getTaskType().name(),
            "sourceType", task.getSourceType().name(),
            "promotionMode", task.getPromotionMode().name(),
            "targetApiSpecIds", task.getTargetApiSpecIds(),
            "metadata", task.getMetadata()
        );
    }

    private Map<String, Object> apiSpecSummary(ApiSpec apiSpec) {
        return orderedMap(
            "apiSpecId", apiSpec.getApiSpecId(),
            "moduleName", apiSpec.getModuleName(),
            "httpMethod", apiSpec.getHttpMethod().name(),
            "path", apiSpec.getPath(),
            "summary", apiSpec.getSummary(),
            "sourceType", apiSpec.getSourceType().name(),
            "authReady", apiSpec.isAuthReady()
        );
    }

    private Map<String, Object> testCaseSummary(TestCase testCase) {
        return orderedMap(
            "caseId", testCase.getCaseId(),
            "primaryApiSpecId", testCase.getPrimaryApiSpecId(),
            "mode", testCase.getMode().name(),
            "caseCategory", testCase.getCaseCategory().name(),
            "scenarioName", testCase.getScenarioName(),
            "stepCount", testCase.getSteps().size(),
            "steps", testCase.getSteps().stream()
                .map(step -> orderedMap(
                    "stepId", step.get("stepId"),
                    "order", step.get("order"),
                    "stepName", step.get("stepName"),
                    "apiSpecId", step.get("apiSpecId"),
                    "critical", step.get("critical")
                ))
                .toList()
        );
    }

    private Map<String, Object> businessFlowDiscoverySummary(BusinessFlowDiscoveryResult result) {
        return orderedMap(
            "schemaVersion", result.schemaVersion(),
            "status", result.status().name(),
            "providerMode", result.providerMode().name(),
            ManualSuiteAgentRunResult.USES_REAL_LLM_REPORT_KEY, result.usesManualLlmProvider(),
            "usesExternalHttp", result.usesExternalHttp(),
            "candidateCount", result.candidates().size(),
            "sourceCoverage", sourceCoverageSummary(result.sourceCoverage()),
            "blockers", result.blockers().stream().map(this::blockerSummary).toList(),
            "candidates", result.candidates().stream().map(this::candidateSummary).toList(),
            "metadata", result.metadata()
        );
    }

    private Map<String, Object> candidateSummary(BusinessFlowCandidate candidate) {
        return orderedMap(
            "candidateId", candidate.candidateId(),
            "scenarioName", candidate.scenarioName(),
            "primary", candidate.primary(),
            "confidence", candidate.confidence(),
            "requiresHumanReview", candidate.requiresHumanReview(),
            "steps", candidate.steps().stream().map(this::flowStepSummary).toList(),
            "evidence", candidate.evidence().stream().map(this::evidenceSummary).toList(),
            "blockers", candidate.blockers().stream().map(this::blockerSummary).toList(),
            "sourceCoverage", sourceCoverageSummary(candidate.sourceCoverage()),
            "tags", candidate.tags(),
            "metadata", candidate.metadata()
        );
    }

    private Map<String, Object> flowStepSummary(BusinessFlowDiscoveryStep step) {
        return orderedMap(
            "stepId", step.stepId(),
            "order", step.order(),
            "apiSpecId", step.apiSpecId(),
            "stepName", step.stepName(),
            "operationKind", step.operationKind().name(),
            "httpMethod", step.httpMethod().name(),
            "path", step.path(),
            "critical", step.critical(),
            "sourceRefs", step.sourceRefs(),
            "metadata", step.metadata()
        );
    }

    private Map<String, Object> evidenceSummary(BusinessFlowDiscoveryEvidence evidence) {
        return orderedMap(
            "evidenceId", evidence.evidenceId(),
            "source", evidence.source().name(),
            "summary", evidence.summary(),
            "confidenceContribution", evidence.confidenceContribution(),
            "refs", evidence.refs(),
            "metadata", evidence.metadata()
        );
    }

    private Map<String, Object> blockerSummary(BusinessFlowDiscoveryBlocker blocker) {
        return orderedMap(
            "code", blocker.code(),
            "severity", blocker.severity(),
            "message", blocker.message(),
            "metadata", blocker.metadata()
        );
    }

    private Map<String, Object> sourceCoverageSummary(BusinessFlowSourceCoverage coverage) {
        return orderedMap(
            "apiSpecEvidenceCount", coverage.apiSpecEvidenceCount(),
            "knowledgeEvidenceCount", coverage.knowledgeEvidenceCount(),
            "memoryEvidenceCount", coverage.memoryEvidenceCount(),
            "userSelectionEvidenceCount", coverage.userSelectionEvidenceCount(),
            "llmSuggestionEvidenceCount", coverage.llmSuggestionEvidenceCount(),
            "metadata", coverage.metadata()
        );
    }

    private ManualSuiteAgentRunResult writeBestEffort(
        ManualSuiteAgentRunResult result,
        ManualSuiteAgentRunRequest request
    ) {
        try {
            return reportWriter.write(result, request.outputDirectory());
        } catch (IOException exception) {
            var diagnostics = new java.util.ArrayList<>(result.diagnostics());
            diagnostics.add(ManualSuiteAgentDiagnostic.error(
                "REPORT_WRITE_FAILED",
                "Report write failed: " + exception.getMessage(),
                Map.of("outputDirectory", request.outputDirectory().toAbsolutePath().toString())
            ));
            return result.withDiagnostics(ManualSuiteAgentRunStatus.FAILED, diagnostics);
        }
    }

    private ManualSuiteAgentRunResult baseResult(
        ManualSuiteAgentRunRequest request,
        ManualSuiteAgentFixture fixture,
        ManualSuiteAgentRunStatus status,
        Instant startedAt,
        Instant completedAt,
        List<ManualSuiteAgentSectionSummary> sections,
        List<ManualSuiteAgentDiagnostic> diagnostics,
        Map<String, Object> metadata
    ) {
        var fixtureSummary = fixture == null
            ? missingFixtureSummary(request.fixtureId())
            : fixture.summary();
        return new ManualSuiteAgentRunResult(
            ManualSuiteAgentRunResult.SCHEMA_VERSION,
            "v3h-" + UUID.randomUUID(),
            fixtureSummary.fixtureId(),
            fixtureSummary.fixtureVersion(),
            request.providerMode(),
            status,
            startedAt,
            completedAt,
            request.runProfile(),
            request.providerMode() == ManualSuiteAgentProviderMode.MANUAL_REAL_LLM && request.allowManualProvider(),
            request.allowExternalHttp(),
            fixtureSummary,
            sections,
            diagnostics,
            List.of(),
            metadata
        );
    }

    private ManualSuiteAgentFixtureSummary missingFixtureSummary(String fixtureId) {
        return new ManualSuiteAgentFixtureSummary(
            fixtureId,
            "unknown",
            "Unknown fixture",
            "Fixture metadata could not be loaded.",
            List.of(),
            Map.of()
        );
    }

    private List<ManualSuiteAgentSectionSummary> baseSections(
        ManualSuiteAgentRunRequest request,
        ManualSuiteAgentFixture fixture
    ) {
        return List.of(
            new ManualSuiteAgentSectionSummary(
                "input",
                "Fixture and provider input",
                ManualSuiteAgentSectionSource.FIXTURE,
                "READY",
                orderedMap(
                    "fixtureId", fixture.fixtureId(),
                    "fixtureVersion", fixture.fixtureVersion(),
                    "providerMode", request.providerMode().name()
                )
            ),
            new ManualSuiteAgentSectionSummary(
                "run-summary",
                "Harness run summary",
                ManualSuiteAgentSectionSource.REAL,
                "READY",
                orderedMap(
                    ManualSuiteAgentRunResult.USES_REAL_LLM_REPORT_KEY, false,
                    "usesExternalHttp", false,
                    "runProfile", request.runProfile()
                )
            ),
            new ManualSuiteAgentSectionSummary(
                "sections",
                "V3 section registry placeholder",
                ManualSuiteAgentSectionSource.STAGED,
                "READY",
                orderedMap("sectionCount", 4)
            ),
            new ManualSuiteAgentSectionSummary(
                "diagnostics",
                "Structured diagnostics",
                ManualSuiteAgentSectionSource.REAL,
                "READY",
                orderedMap("diagnosticCount", 0)
            )
        );
    }

    private Map<String, Object> extractRuleRuntime(SuiteExtractRule rule) {
        return orderedMap(
            "ruleId", rule.ruleId(),
            "producerStepId", rule.producerStepId(),
            "sourceType", rule.sourceType().name(),
            "sourcePath", rule.sourcePath(),
            "targetScope", rule.targetScope().name(),
            "targetKey", rule.targetKey(),
            "required", rule.required(),
            "failureStrategy", rule.failureStrategy().name(),
            "defaultValue", rule.defaultValue(),
            "description", rule.description(),
            "confidence", rule.confidence(),
            "evidenceRefs", rule.evidenceRefs(),
            "consumerStepIds", rule.consumerStepIds(),
            "sourceDependencyIds", rule.sourceDependencyIds()
        );
    }

    private List<Map<String, Object>> orderedSteps(TestCase testCase) {
        return testCase.getSteps().stream()
            .sorted(Comparator.comparingInt(step -> intValue(step.get("order"))))
            .toList();
    }

    private HttpClientRequest clientRequest(Map<String, Object> resolvedTemplate, HttpExecutionRequest executionRequest) {
        var method = stringValue(resolvedTemplate.getOrDefault("method", "GET"));
        var path = stringValue(resolvedTemplate.getOrDefault("path", ""));
        var headers = objectMap(resolvedTemplate.get("headers"));
        var query = objectMap(firstPresent(resolvedTemplate, "query", "queryParams"));
        var body = resolvedTemplate.getOrDefault("body", Map.of());
        var url = buildUrl(stringValue(executionRequest.environmentVariables().get("baseUrl")), path, query);
        return new HttpClientRequest(method, path, url, headers, query, body);
    }

    private Map<String, Object> requestSnapshot(HttpClientRequest request) {
        return orderedMap(
            "method", request.method(),
            "path", request.path(),
            "url", request.url(),
            "headers", RuntimeRedactor.redact(request.headers(), "headers"),
            "queryParams", RuntimeRedactor.redact(request.queryParams(), "queryParams"),
            "body", RuntimeRedactor.redact(request.body(), "body")
        );
    }

    private String buildUrl(String baseUrl, String path, Map<String, Object> query) {
        var normalizedBase = baseUrl == null || baseUrl.isBlank() ? "" : baseUrl.replaceAll("/+$", "");
        var normalizedPath = path == null ? "" : path;
        var url = normalizedBase.isBlank()
            ? normalizedPath
            : normalizedBase + "/" + normalizedPath.replaceAll("^/+", "");
        if (query.isEmpty()) {
            return url;
        }
        var queryString = query.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .toList();
        return queryString.isEmpty() ? url : url + "?" + String.join("&", queryString);
    }

    private List<Map<String, Object>> eventsForStep(ExecutionContext context, String stepId) {
        return context.auditEvents().stream()
            .filter(event -> stepId.equals(event.get("stepId")))
            .toList();
    }

    private List<Map<String, Object>> diagnosticsForStep(ExecutionContext context, String stepId) {
        return context.diagnostics().stream()
            .filter(diagnostic -> stepId.equals(diagnostic.get("stepId")))
            .toList();
    }

    private List<Map<String, Object>> producerHighlights(List<Map<String, Object>> auditEvents) {
        return auditEvents.stream()
            .filter(event -> "PRODUCTION".equals(event.get("eventType")) && Boolean.TRUE.equals(event.get("success")))
            .map(event -> orderedMap(
                "stepId", event.get("stepId"),
                "variable", event.get("targetScope") + "." + event.get("targetKey"),
                "source", event.get("sourceType") + ":" + event.get("sourcePath"),
                "summary", event.get("stepId") + " produces " + event.get("targetScope") + "."
                    + event.get("targetKey") + " from " + event.get("sourceType") + " " + event.get("sourcePath")
            ))
            .toList();
    }

    private List<Map<String, Object>> consumerHighlights(List<Map<String, Object>> auditEvents) {
        return auditEvents.stream()
            .filter(event -> "CONSUMPTION".equals(event.get("eventType")) && Boolean.TRUE.equals(event.get("resolved")))
            .map(event -> orderedMap(
                "stepId", event.get("stepId"),
                "expression", event.get("expression"),
                "location", event.get("location"),
                "summary", event.get("stepId") + " consumes " + event.get("expression") + " at " + event.get("location")
            ))
            .toList();
    }

    private int countStatus(List<Map<String, Object>> stepResults, String status) {
        return (int) stepResults.stream()
            .filter(step -> status.equals(step.get("status")))
            .count();
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (var key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> incoming)) {
            return new LinkedHashMap<>();
        }
        var map = new LinkedHashMap<String, Object>();
        incoming.forEach((key, mapValue) -> {
            if (key != null) {
                map.put(key.toString(), mapValue);
            }
        });
        return map;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(value.toString());
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }

    private record OrderFixtureRun(
        List<ManualSuiteAgentSectionSummary> sections,
        Map<String, Object> metadata
    ) {
    }
}
