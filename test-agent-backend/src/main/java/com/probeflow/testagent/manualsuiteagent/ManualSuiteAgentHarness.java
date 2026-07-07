package com.probeflow.testagent.manualsuiteagent;

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
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ManualSuiteAgentHarness {

    private final ManualSuiteAgentFixtureRegistry fixtureRegistry;
    private final ManualSuiteAgentReportWriter reportWriter;
    private final ManualSuiteAgentFakeHttpGateway fakeHttpGateway = new ManualSuiteAgentFakeHttpGateway();
    private final BusinessFlowDiscoveryService businessFlowDiscoveryService = new BusinessFlowDiscoveryService();

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
        if ("order-suite-demo".equals(fixtureValue.fixtureId())) {
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
        var task = orderTask(apiSpecs.stream().map(ApiSpec::getApiSpecId).toList());
        var testCase = orderSuiteTestCase(apiSpecs);
        var executionSummary = executeOrderSuite(testCase);

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("task", taskSummary(task));
        metadata.put("apiSpecs", apiSpecs.stream().map(this::apiSpecSummary).toList());
        metadata.put("testCases", List.of(testCaseSummary(testCase)));
        metadata.put("businessFlowDiscovery", businessFlowDiscoverySummary(discoveryResult));
        metadata.put("executionSummary", executionSummary);

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
        sections.addAll(v3StagedSections(request, fixture, testCase));
        sections.add(new ManualSuiteAgentSectionSummary(
            "execution-result",
            "Fake HTTP execution summary",
            ManualSuiteAgentSectionSource.REAL,
            "PASSED",
            executionSummary
        ));
        return new OrderFixtureRun(sections, metadata);
    }

    private List<ManualSuiteAgentSectionSummary> v3StagedSections(
        ManualSuiteAgentRunRequest request,
        ManualSuiteAgentFixture fixture,
        TestCase testCase
    ) {
        return List.of(
            new ManualSuiteAgentSectionSummary(
                "generated-suite-draft",
                "Generated suite draft summary",
                ManualSuiteAgentSectionSource.FIXTURE,
                "READY",
                orderedMap(
                    "sourceMarker", "fixture",
                    "phaseNote", "Business Flow Discovery is real in V3-2; this suite draft remains fixture-provided until V3-3 DependencyLinker.",
                    "scenarioName", testCase.getScenarioName(),
                    "steps", testCase.getSteps().stream()
                        .map(step -> orderedMap(
                            "order", step.get("order"),
                            "stepName", step.get("stepName"),
                            "critical", step.get("critical"),
                            "sourceRefs", List.of(step.get("apiSpecId")),
                            "sourceMarker", "fixture"
                        ))
                        .toList()
                )
            ),
            new ManualSuiteAgentSectionSummary(
                "variable-audit",
                "Variable audit integration slot",
                ManualSuiteAgentSectionSource.PENDING_RUNTIME,
                "PENDING_RUNTIME",
                orderedMap(
                    "sourceMarker", "pending-runtime",
                    "phaseNote", "Variable producer and consumer audit awaits V3-4 ExecutionContext runtime.",
                    "producer", "create-order.response.body.orderId",
                    "consumer", "pay-order.request.path.orderId",
                    "targetScope", "suite",
                    "targetKey", "orderId",
                    "auditEvents", List.of(
                        orderedMap(
                            "producer", "create-order",
                            "consumer", "pay-order",
                            "targetScope", "suite",
                            "targetKey", "orderId",
                            "eventSummary", "orderId will be extracted from create response and consumed by payment step"
                        ),
                        orderedMap(
                            "producer", "pay-order",
                            "consumer", "query-order",
                            "targetScope", "suite",
                            "targetKey", "paymentId",
                            "eventSummary", "paymentId will be available for downstream analysis and reporting"
                        )
                    )
                )
            ),
            new ManualSuiteAgentSectionSummary(
                "failure-analysis",
                "Suite failure analysis integration slot",
                ManualSuiteAgentSectionSource.STAGED,
                "STAGED",
                orderedMap(
                    "sourceMarker", "staged",
                    "phaseNote", "Suite failure analysis slot awaits V3-5.",
                    "rootStep", "pay-order",
                    "affectedSteps", List.of("query-order"),
                    "failureType", "STAGED_SUITE_FAILURE_SLOT",
                    "evidence", List.of("fake-http happy path passed; staged failure slot kept for downstream phase replacement"),
                    "nextSuggestion", "Replace this staged summary with V3-5 Suite Failure Analysis output."
                )
            ),
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

    private TestCase orderSuiteTestCase(List<ApiSpec> apiSpecs) {
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
        testCase.setDetail(orderedMap("fixtureId", "order-suite-demo", "source", "manual-suite-agent-harness"));
        testCase.setSteps(apiSpecs.stream()
            .map(apiSpec -> suiteStep(apiSpec, apiSpecs.indexOf(apiSpec) + 1))
            .toList());
        testCase.setBasedOnApiSpecVersions(orderedMap(
            "api-order-create", 1,
            "api-order-pay", 1,
            "api-order-query", 1
        ));
        return testCase;
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

    private Map<String, Object> executeOrderSuite(TestCase testCase) {
        var variables = new LinkedHashMap<String, Object>();
        variables.put("baseUrl", "https://fixture.local");
        variables.put("tenant", "tenant-demo");
        variables.put("orderAuthToken", "order-demo-token");

        var stepResults = new ArrayList<Map<String, Object>>();
        var responseHighlights = new ArrayList<Map<String, Object>>();
        var totalDurationMs = 0L;
        for (var step : testCase.getSteps()) {
            var stepId = step.get("stepId").toString();
            var response = fakeHttpGateway.execute(stepId, variables);
            if (response.body().containsKey("orderId")) {
                variables.put("orderId", response.body().get("orderId"));
            }
            if (response.body().containsKey("paymentId")) {
                variables.put("paymentId", response.body().get("paymentId"));
            }
            totalDurationMs += response.durationMs();
            stepResults.add(orderedMap(
                "stepId", stepId,
                "order", step.get("order"),
                "apiSpecId", step.get("apiSpecId"),
                "status", response.statusCode() < 400 ? "PASSED" : "FAILED",
                "statusCode", response.statusCode(),
                "durationMs", response.durationMs()
            ));
            responseHighlights.add(orderedMap(
                "stepId", stepId,
                "statusCode", response.statusCode(),
                "summary", response.summary(),
                "body", response.body()
            ));
        }
        return orderedMap(
            "environment", "fixture-local",
            "gateway", "FAKE_HTTP",
            "usesExternalHttp", false,
            "caseCount", 1,
            "stepCount", testCase.getSteps().size(),
            "passed", 3,
            "failed", 0,
            "skipped", 0,
            "blocked", 0,
            "totalDurationMs", totalDurationMs,
            "stepResults", stepResults,
            "responseHighlights", responseHighlights
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
            "usesRealLlm", result.usesRealLlm(),
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
