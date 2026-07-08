package com.probeflow.testagent.agentevaluation;

import com.probeflow.testagent.agentmemoryfeedback.AgentMemoryCandidateSourceType;
import com.probeflow.testagent.agentmemoryfeedback.AgentMemoryFeedbackApplicationService;
import com.probeflow.testagent.agentmemoryfeedback.AgentMemoryFeedbackResult;
import com.probeflow.testagent.agentmemoryfeedback.MemoryCandidateProcessingStatus;
import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.memory.ContextBundle;
import com.probeflow.testagent.memory.LongTermMemory;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.MemoryUsageConsumer;
import com.probeflow.testagent.memory.MemoryUsageRecord;
import com.probeflow.testagent.memory.MemoryUsageRecordRepository;
import com.probeflow.testagent.memory.MemoryUsefulnessFeedbackRequest;
import com.probeflow.testagent.memory.MemoryUsefulnessFeedbackService;
import com.probeflow.testagent.memory.MemoryUsefulnessFeedbackStatus;
import com.probeflow.testagent.memory.MemoryUsefulnessOutcome;
import com.probeflow.testagent.memory.UnifiedContextBuilder;
import com.probeflow.testagent.memory.UnifiedContextQuery;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MemoryReuseClosedLoopEvaluator implements AgentEvaluationEvaluator {

    public static final String METRIC_NAME = "memory-reuse";
    public static final String V3_SUITE_METRIC_NAME = "v3-suite-memory-reuse-closed-loop";

    private final AgentMemoryFeedbackApplicationService memoryFeedback;
    private final UnifiedContextBuilder contextBuilder;
    private final MemoryUsefulnessFeedbackService usefulnessFeedback;
    private final LongTermMemoryRepository longTermMemories;
    private final MemoryUsageRecordRepository usageRecords;
    private final ApiSpecRepository apiSpecs;
    private final TaskRepository tasks;
    private final EntityManager entityManager;

    public MemoryReuseClosedLoopEvaluator(
        AgentMemoryFeedbackApplicationService memoryFeedback,
        UnifiedContextBuilder contextBuilder,
        MemoryUsefulnessFeedbackService usefulnessFeedback,
        LongTermMemoryRepository longTermMemories,
        MemoryUsageRecordRepository usageRecords,
        ApiSpecRepository apiSpecs,
        TaskRepository tasks,
        EntityManager entityManager
    ) {
        this.memoryFeedback = memoryFeedback;
        this.contextBuilder = contextBuilder;
        this.usefulnessFeedback = usefulnessFeedback;
        this.longTermMemories = longTermMemories;
        this.usageRecords = usageRecords;
        this.apiSpecs = apiSpecs;
        this.tasks = tasks;
        this.entityManager = entityManager;
    }

    @Override
    public String capability() {
        return METRIC_NAME;
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.MEMORY_REUSE
            || fixture.fixtureType() == EvaluationFixtureType.V3_SUITE_MEMORY_REUSE;
    }

    @Override
    @Transactional
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var metricName = metricName(fixture);
        if (suiteMemoryReuse(fixture) && context.providerMode() == EvaluationProviderMode.MANUAL_REAL_EXPERIMENT) {
            return manualRealBoundaryResult(dataset, fixture, context, metricName);
        }
        var scenario = scenario(fixture, context);
        var diagnostics = new ArrayList<String>();
        seedApiSpec(scenario);
        seedTask(scenario.firstTaskId(), scenario.apiSpecId(), fixture, "first-stage-learning");
        seedTask(scenario.repeatTaskId(), scenario.apiSpecId(), fixture, "repeated-failure-learning");
        seedTask(scenario.positiveTaskId(), scenario.apiSpecId(), fixture, "positive-recall");
        seedTask(scenario.negativeTaskId(), scenario.apiSpecId(), fixture, "negative-recall");

        AgentMemoryFeedbackResult firstStage = null;
        AgentMemoryFeedbackResult repeatedFailure = null;
        if (!bool(fixture.setupMetadata().get("skipFirstStageLearning"), false)) {
            firstStage = refineFailureCandidate(scenario, scenario.firstTaskId(), "initial");
            flushAndClear();
            repeatedFailure = refineFailureCandidate(scenario, scenario.repeatTaskId(), "repeat");
            flushAndClear();
        }

        var memoryId = memoryId(firstStage);
        var afterMerge = memory(memoryId);
        var skipReuseAfterLearning = bool(fixture.setupMetadata().get("skipReuseAfterLearning"), false);
        ContextBundle positiveBundle = null;
        MemoryUsageRecord positiveUsage = null;
        var recalledByPositiveTask = false;
        var citedByPositiveTask = false;
        if (!skipReuseAfterLearning) {
            positiveBundle = contextBuilder.build(query(
                scenario,
                scenario.positiveTaskId(),
                scenario.positiveUsageSourceRef(),
                "payment auth failed with " + scenario.errorCode() + " and tenant bootstrap symptoms"
            ));
            flushAndClear();
            recalledByPositiveTask = recalled(positiveBundle, memoryId);
            citedByPositiveTask = cited(positiveBundle, memoryId);
            positiveUsage = usageFor(scenario.positiveTaskId(), memoryId, scenario.positiveUsageSourceRef());
        }
        var beforePositive = scores(memory(memoryId));
        var positiveStatus = submitFeedback(
            positiveUsage,
            "phase8-agent-evaluation-positive",
            MemoryUsefulnessOutcome.POSITIVE,
            "next task succeeded after using the recalled tenant bootstrap memory"
        );
        flushAndClear();
        var afterPositive = scores(memory(memoryId));

        ContextBundle negativeBundle = null;
        MemoryUsageRecord negativeUsage = null;
        var recalledByNegativeTask = false;
        var citedByNegativeTask = false;
        if (!skipReuseAfterLearning) {
            negativeBundle = contextBuilder.build(query(
                scenario,
                scenario.negativeTaskId(),
                scenario.negativeUsageSourceRef(),
                "payment " + scenario.errorCode() + " investigation but human says tenant bootstrap memory was misleading"
            ));
            flushAndClear();
            recalledByNegativeTask = recalled(negativeBundle, memoryId);
            citedByNegativeTask = cited(negativeBundle, memoryId);
            negativeUsage = usageFor(scenario.negativeTaskId(), memoryId, scenario.negativeUsageSourceRef());
        }
        var beforeNegative = scores(memory(memoryId));
        var negativeStatus = submitFeedback(
            negativeUsage,
            "phase8-agent-evaluation-negative",
            MemoryUsefulnessOutcome.NEGATIVE,
            "human review found this recalled memory misleading for the later task"
        );
        flushAndClear();
        var afterNegative = scores(memory(memoryId));

        appendDiagnostics(
            diagnostics,
            firstStage,
            repeatedFailure,
            memoryId,
            afterMerge,
            recalledByPositiveTask,
            citedByPositiveTask,
            positiveUsage,
            beforePositive,
            afterPositive,
            positiveStatus,
            recalledByNegativeTask,
            citedByNegativeTask,
            negativeUsage,
            beforeNegative,
            afterNegative,
            negativeStatus
        );

        var actual = actual(
            scenario,
            firstStage,
            repeatedFailure,
            memoryId,
            afterMerge,
            positiveBundle,
            positiveUsage,
            beforePositive,
            afterPositive,
            positiveStatus,
            negativeBundle,
            negativeUsage,
            beforeNegative,
            afterNegative,
            negativeStatus
        );
        var expected = fixture.expectedResults();
        var score = score(diagnostics);
        var passed = diagnostics.isEmpty() && score >= dataset.thresholdFor(metricName);
        var metric = new EvaluationMetricResult(
            metricName,
            score,
            dataset.thresholdFor(metricName),
            dataset.weightFor(metricName),
            passed,
            actual,
            expected,
            passed
                ? "Memory reuse closed loop recalled, cited, recorded, merged and calibrated expected long-term memory."
                : String.join("; ", diagnostics)
        );
        if (passed) {
            return EvaluationCaseResult.passed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "Long-term memory was learned, merged, recalled, cited, usage-recorded and calibrated by usefulness feedback.",
                expected.toString(),
                List.of(metric)
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "Memory reuse closed loop missed one or more expected stages.",
            expected.toString(),
            diagnostics,
            List.of(metric)
        );
    }

    private AgentMemoryFeedbackResult refineFailureCandidate(MemoryScenario scenario, String taskId, String suffix) {
        var fingerprint = scenario.isolationFingerprint();
        var v3 = scenario.suiteMemoryReuse();
        return memoryFeedback.refineFailureAnalysisCandidate(
            AgentMemoryCandidateSourceType.FAILURE_ANALYSIS,
            new MemoryCandidateRequest(
                (v3 ? "V3 SUITE " : "") + scenario.errorCode() + " isolated failure fingerprint " + fingerprint,
                (v3 ? "V3 SUITE failure analysis learned " : "Failure analysis learned ")
                    + "fingerprint " + fingerprint
                    + " for " + scenario.errorCode()
                    + ": tenant bootstrap was skipped before auth in this payment namespace.",
                MemorySourceType.EXECUTION_RESULT,
                scenario.sourceRef(suffix),
                taskId,
                v3
                    ? List.of("v3", "suite", "memory-reuse", "closed-loop", "payment", "auth", "tenant", scenario.errorCode())
                    : List.of("phase8", "memory-reuse", "payment", "auth", "tenant", scenario.errorCode()),
                0.88f,
                "ExecutionRecord fingerprint " + fingerprint + " showed " + scenario.errorCode()
                    + " disappears after tenant bootstrap is restored. suffix=" + suffix,
                Map.of(
                    "systemName", scenario.systemName(),
                    "module", scenario.moduleName(),
                    "apiPath", scenario.apiPath(),
                    "errorCode", scenario.errorCode(),
                    "riskLevel", "HIGH",
                    "retryable", true,
                    "phase", v3 ? "V3_PHASE_6" : "V2_PHASE_8",
                    "fixtureId", scenario.fixtureId()
                )
            )
        );
    }

    private void appendDiagnostics(
        List<String> diagnostics,
        AgentMemoryFeedbackResult firstStage,
        AgentMemoryFeedbackResult repeatedFailure,
        String memoryId,
        LongTermMemory afterMerge,
        boolean recalledByPositiveTask,
        boolean citedByPositiveTask,
        MemoryUsageRecord positiveUsage,
        MemoryScores beforePositive,
        MemoryScores afterPositive,
        MemoryUsefulnessFeedbackStatus positiveStatus,
        boolean recalledByNegativeTask,
        boolean citedByNegativeTask,
        MemoryUsageRecord negativeUsage,
        MemoryScores beforeNegative,
        MemoryScores afterNegative,
        MemoryUsefulnessFeedbackStatus negativeStatus
    ) {
        if (!learned(firstStage) || memoryId == null) {
            diagnostics.add("missing long-term memory from first-stage learning");
        }
        if (!merged(repeatedFailure, afterMerge)) {
            diagnostics.add("repeated failure did not merge into the expected long-term memory");
        }
        if (!recalledByPositiveTask) {
            diagnostics.add("missing recall: expected long-term memory was not recalled by the next task");
        }
        if (!citedByPositiveTask) {
            diagnostics.add("missing citation: expected memory citation was absent from ContextBundle");
        }
        if (positiveUsage == null) {
            diagnostics.add("missing usage record: recalled long-term memory was not audited");
        }
        if (!feedbackRecorded(positiveStatus) || !increased(beforePositive, afterPositive)) {
            diagnostics.add("feedback not applied: positive usefulness feedback did not increase memory score");
        }
        if (!recalledByNegativeTask || !citedByNegativeTask || negativeUsage == null) {
            diagnostics.add("negative feedback path did not recall, cite and audit the memory before calibration");
        }
        if (!feedbackRecorded(negativeStatus) || !decreased(beforeNegative, afterNegative)) {
            diagnostics.add("feedback not applied: negative usefulness feedback did not decrease memory score");
        }
    }

    private Map<String, Object> actual(
        MemoryScenario scenario,
        AgentMemoryFeedbackResult firstStage,
        AgentMemoryFeedbackResult repeatedFailure,
        String memoryId,
        LongTermMemory afterMerge,
        ContextBundle positiveBundle,
        MemoryUsageRecord positiveUsage,
        MemoryScores beforePositive,
        MemoryScores afterPositive,
        MemoryUsefulnessFeedbackStatus positiveStatus,
        ContextBundle negativeBundle,
        MemoryUsageRecord negativeUsage,
        MemoryScores beforeNegative,
        MemoryScores afterNegative,
        MemoryUsefulnessFeedbackStatus negativeStatus
    ) {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("apiSpecId", scenario.apiSpecId());
        actual.put("errorCode", scenario.errorCode());
        actual.put("memoryId", memoryId);
        actual.put("sourceRef", scenario.sourceRef("initial"));
        actual.put("reuseSignal", recalled(positiveBundle, memoryId) ? scenario.positiveUsageSourceRef() : null);
        actual.put("providerMode", scenario.providerMode().name());
        actual.put("usesRealProvider", scenario.providerMode() == EvaluationProviderMode.MANUAL_REAL_EXPERIMENT);
        actual.put("includedInCiRegression", scenario.providerMode() == EvaluationProviderMode.DETERMINISTIC_FAKE);
        actual.put("writesLongTermMemory", learned(firstStage) && memoryId != null);
        actual.put("requiresHumanConfirmedMemoryFeedback", false);
        actual.put("firstStageStatus", status(firstStage));
        actual.put("repeatedFailureStatus", status(repeatedFailure));
        actual.put("mergeCount", mergeCount(afterMerge));
        actual.put("hitCountAfterMerge", afterMerge == null ? null : afterMerge.getHitCount());
        actual.put("positiveRecall", recalled(positiveBundle, memoryId));
        actual.put("positiveCitation", cited(positiveBundle, memoryId));
        actual.put("positiveUsageRecordId", positiveUsage == null ? null : positiveUsage.getUsageId());
        actual.put("positiveUsageConsumer", positiveUsage == null ? null : positiveUsage.getConsumer().name());
        actual.put("positiveFeedbackStatus", positiveStatus == null ? null : positiveStatus.name());
        actual.put("confidenceBeforePositive", confidence(beforePositive));
        actual.put("confidenceAfterPositive", confidence(afterPositive));
        actual.put("importanceBeforePositive", importance(beforePositive));
        actual.put("importanceAfterPositive", importance(afterPositive));
        actual.put("successContributionBeforePositive", success(beforePositive));
        actual.put("successContributionAfterPositive", success(afterPositive));
        actual.put("negativeRecall", recalled(negativeBundle, memoryId));
        actual.put("negativeCitation", cited(negativeBundle, memoryId));
        actual.put("negativeUsageRecordId", negativeUsage == null ? null : negativeUsage.getUsageId());
        actual.put("negativeUsageConsumer", negativeUsage == null ? null : negativeUsage.getConsumer().name());
        actual.put("negativeFeedbackStatus", negativeStatus == null ? null : negativeStatus.name());
        actual.put("confidenceBeforeNegative", confidence(beforeNegative));
        actual.put("confidenceAfterNegative", confidence(afterNegative));
        actual.put("importanceBeforeNegative", importance(beforeNegative));
        actual.put("importanceAfterNegative", importance(afterNegative));
        actual.put("successContributionBeforeNegative", success(beforeNegative));
        actual.put("successContributionAfterNegative", success(afterNegative));
        actual.put("positiveCitations", citations(positiveBundle));
        actual.put("negativeCitations", citations(negativeBundle));
        return actual;
    }

    private UnifiedContextQuery query(
        MemoryScenario scenario,
        String taskId,
        String usageSourceRef,
        String rawQuery
    ) {
        return new UnifiedContextQuery(
            taskId,
            null,
            scenario.apiSpecId(),
            null,
            "failure_analysis",
            rawQuery,
            scenario.systemName(),
            scenario.moduleName(),
            scenario.apiPath(),
            scenario.errorCode(),
            scenario.suiteMemoryReuse()
                ? List.of("v3", "suite", "memory-reuse", "closed-loop", "payment", "auth", "tenant", scenario.errorCode())
                : List.of("phase8", "memory-reuse", "payment", "auth", "tenant", scenario.errorCode()),
            500,
            MemoryUsageConsumer.AGENT_EVALUATION,
            usageSourceRef
        );
    }

    private void seedApiSpec(MemoryScenario scenario) {
        if (apiSpecs.existsById(scenario.apiSpecId())) {
            return;
        }
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(scenario.apiSpecId());
        apiSpec.setSystemName(scenario.systemName());
        apiSpec.setModuleName(scenario.moduleName());
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath(scenario.apiPath());
        apiSpec.setSummary("Phase 8 memory reuse payment API");
        apiSpec.setDescription("Payment API used by the memory reuse closed-loop evaluation fixture.");
        apiSpec.setOperationId("phase8MemoryReusePay");
        apiSpec.setParameters(Map.of("orderId", Map.of("type", "string", "required", true)));
        apiSpec.setConstraints(Map.of("requiresTenantBootstrap", true));
        apiSpec.setAuth(Map.of("required", true, "type", "bearer"));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("phase8-agent-evaluation-memory-reuse");
        apiSpec.setSourceLocation(Map.of("fixtureId", scenario.fixtureId()));
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        apiSpecs.save(apiSpec);
    }

    private void seedTask(String taskId, String apiSpecId, GoldenTaskFixture fixture, String stage) {
        if (tasks.existsById(taskId)) {
            return;
        }
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 8 memory reuse evaluation " + fixture.fixtureId() + " " + stage);
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("phase8-agent-evaluation");
        task.setTargetApiSpecIds(List.of(apiSpecId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("phase8-agent-evaluation");
        task.setMetadata(Map.of("fixtureId", fixture.fixtureId(), "capability", metricName(fixture), "stage", stage));
        tasks.save(task);
    }

    private MemoryUsageRecord usageFor(String taskId, String memoryId, String sourceRef) {
        if (memoryId == null) {
            return null;
        }
        return usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(taskId).stream()
            .filter(record -> memoryId.equals(record.getMemoryId()))
            .filter(record -> sourceRef.equals(record.getSourceRef()))
            .reduce((first, second) -> second)
            .orElse(null);
    }

    private MemoryUsefulnessFeedbackStatus submitFeedback(
        MemoryUsageRecord usage,
        String actor,
        MemoryUsefulnessOutcome outcome,
        String reason
    ) {
        if (usage == null) {
            return null;
        }
        return usefulnessFeedback.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            usage.getUsageId(),
            actor,
            outcome,
            reason,
            Map.of("phase", "V2_PHASE_8", "capability", METRIC_NAME)
        )).status();
    }

    private boolean learned(AgentMemoryFeedbackResult result) {
        if (result == null) {
            return false;
        }
        return result.status() == MemoryCandidateProcessingStatus.ACCEPTED
            || result.status() == MemoryCandidateProcessingStatus.MERGED
            || result.status() == MemoryCandidateProcessingStatus.DUPLICATE;
    }

    private boolean merged(AgentMemoryFeedbackResult result, LongTermMemory memory) {
        if (result == null || memory == null) {
            return false;
        }
        return result.status() == MemoryCandidateProcessingStatus.MERGED
            || (result.status() == MemoryCandidateProcessingStatus.DUPLICATE && mergeCount(memory) >= 2);
    }

    private String memoryId(AgentMemoryFeedbackResult result) {
        return result == null || result.memoryId() == null || result.memoryId().isBlank() ? null : result.memoryId();
    }

    private LongTermMemory memory(String memoryId) {
        if (memoryId == null) {
            return null;
        }
        return longTermMemories.findById(memoryId).orElse(null);
    }

    private boolean recalled(ContextBundle bundle, String memoryId) {
        return bundle != null && memoryId != null && bundle.longTermMemoryContext().hits().stream()
            .anyMatch(hit -> memoryId.equals(hit.memoryId()));
    }

    private boolean cited(ContextBundle bundle, String memoryId) {
        return bundle != null && memoryId != null && bundle.citations().stream()
            .anyMatch(citation -> "long_term_memory".equals(citation.citationType()) && memoryId.equals(citation.sourceId()));
    }

    private List<String> citations(ContextBundle bundle) {
        if (bundle == null) {
            return List.of();
        }
        return bundle.citations().stream()
            .filter(citation -> "long_term_memory".equals(citation.citationType()))
            .map(citation -> citation.sourceId() + ":" + citation.sourceRef())
            .toList();
    }

    private boolean feedbackRecorded(MemoryUsefulnessFeedbackStatus status) {
        return status == MemoryUsefulnessFeedbackStatus.RECORDED
            || status == MemoryUsefulnessFeedbackStatus.DUPLICATE;
    }

    private boolean increased(MemoryScores before, MemoryScores after) {
        return before != null && after != null
            && (after.confidence() > before.confidence()
                || after.importance() > before.importance()
                || after.successContribution() > before.successContribution());
    }

    private boolean decreased(MemoryScores before, MemoryScores after) {
        return before != null && after != null
            && (after.confidence() < before.confidence()
                || after.importance() < before.importance()
                || after.successContribution() < before.successContribution());
    }

    private int mergeCount(LongTermMemory memory) {
        if (memory == null) {
            return 0;
        }
        var value = memory.getMetadata().get("mergeCount");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private double score(List<String> diagnostics) {
        var checks = 8;
        return Math.max(0.0d, Math.round(((checks - diagnostics.size()) / (double) checks) * 10000.0d) / 10000.0d);
    }

    private String status(AgentMemoryFeedbackResult result) {
        return result == null ? null : result.status().name();
    }

    private Float confidence(MemoryScores scores) {
        return scores == null ? null : scores.confidence();
    }

    private Float importance(MemoryScores scores) {
        return scores == null ? null : scores.importance();
    }

    private Float success(MemoryScores scores) {
        return scores == null ? null : scores.successContribution();
    }

    private MemoryScores scores(LongTermMemory memory) {
        if (memory == null) {
            return null;
        }
        return new MemoryScores(memory.getConfidence(), memory.getImportance(), memory.getSuccessContribution());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private MemoryScenario scenario(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var apiSpecId = id(context, fixture, "api");
        var code = "MR_" + id(context, fixture, "error").replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        var apiPath = "/api/phase8/memory-reuse/" + id(context, fixture, "path").substring(0, 8) + "/pay";
        var baseModule = text(fixture.setupMetadata(), "moduleName", "payment");
        var moduleName = baseModule + "-" + id(context, fixture, "module").substring(0, 8);
        return new MemoryScenario(
            fixture.fixtureId(),
            context.runId(),
            apiSpecId,
            id(context, fixture, "task-first"),
            id(context, fixture, "task-repeat"),
            id(context, fixture, "task-positive"),
            id(context, fixture, "task-negative"),
            text(fixture.setupMetadata(), "systemName", "order-platform"),
            moduleName,
            apiPath,
            code,
            suiteMemoryReuse(fixture),
            context.providerMode()
        );
    }

    private EvaluationCaseResult manualRealBoundaryResult(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context,
        String metricName
    ) {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("providerMode", context.providerMode().name());
        actual.put("usesRealProvider", true);
        actual.put("includedInCiRegression", false);
        actual.put("writesLongTermMemory", false);
        actual.put("requiresHumanConfirmedMemoryFeedback", true);
        actual.put("memoryId", null);
        actual.put("sourceRef", "manual-real-experiment:" + fixture.fixtureId());
        actual.put("reuseSignal", "manual-real-output-redacted-and-isolated");
        actual.put(
            "comparisonSummary",
            "Manual real experiment is isolated from deterministic fake regression and does not write long-term memory by default."
        );
        var metric = EvaluationMetricResult.passed(
            metricName,
            dataset.thresholdFor(metricName),
            dataset.weightFor(metricName),
            "Manual real experiment boundary held: explicit providerMode, usesRealProvider=true, CI-isolated, no default long-term memory write.",
            actual,
            fixture.expectedResults()
        );
        return EvaluationCaseResult.passed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "Manual real experiment output is redacted, marked usesRealProvider=true and excluded from stable CI scoring.",
            fixture.expectedResults().toString(),
            List.of(metric)
        );
    }

    private boolean suiteMemoryReuse(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.V3_SUITE_MEMORY_REUSE;
    }

    private String metricName(GoldenTaskFixture fixture) {
        return suiteMemoryReuse(fixture) ? V3_SUITE_METRIC_NAME : METRIC_NAME;
    }

    private String id(EvaluationRunContext context, GoldenTaskFixture fixture, String suffix) {
        var raw = context.fixtureNamespace() + ":" + fixture.fixtureId() + ":" + suffix;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        var value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private record MemoryScenario(
        String fixtureId,
        String runId,
        String apiSpecId,
        String firstTaskId,
        String repeatTaskId,
        String positiveTaskId,
        String negativeTaskId,
        String systemName,
        String moduleName,
        String apiPath,
        String errorCode,
        boolean suiteMemoryReuse,
        EvaluationProviderMode providerMode
    ) {

        String sourceRef(String suffix) {
            return (suiteMemoryReuse ? "v3-suite-memory-reuse:" : "phase8-memory-reuse:")
                + runId + ":" + fixtureId + ":" + suffix;
        }

        String positiveUsageSourceRef() {
            return sourceRef("positive-context");
        }

        String negativeUsageSourceRef() {
            return sourceRef("negative-context");
        }

        String isolationFingerprint() {
            return errorCode + " " + moduleName.replace("-", "_") + " " + apiSpecId.replace("-", " ");
        }
    }

    private record MemoryScores(Float confidence, Float importance, Float successContribution) {
    }
}
