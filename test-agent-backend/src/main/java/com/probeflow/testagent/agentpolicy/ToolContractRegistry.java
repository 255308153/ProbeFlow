package com.probeflow.testagent.agentpolicy;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ToolContractRegistry {

    private final Map<ToolName, ToolContract> contracts;

    public ToolContractRegistry() {
        this(defaultContracts());
    }

    public ToolContractRegistry(List<ToolContract> contracts) {
        var byName = new LinkedHashMap<ToolName, ToolContract>();
        var ordered = contracts == null ? List.<ToolContract>of() : contracts.stream()
            .sorted(Comparator.comparing(contract -> contract.name().value()))
            .toList();
        for (var contract : ordered) {
            var previous = byName.put(contract.name(), contract);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate tool contract: " + contract.name());
            }
        }
        this.contracts = Map.copyOf(byName);
    }

    public List<ToolContract> listAll() {
        return contracts.values().stream()
            .sorted(Comparator.comparing(contract -> contract.name().value()))
            .toList();
    }

    public Optional<ToolContract> find(ToolName name) {
        return Optional.ofNullable(contracts.get(name));
    }

    public Optional<ToolContract> find(String name) {
        try {
            return find(ToolName.of(name));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public boolean contains(ToolName name) {
        return contracts.containsKey(name);
    }

    private static List<ToolContract> defaultContracts() {
        return List.of(
            apiAnalyzeSource(),
            knowledgeRetrieveContext(),
            memoryBuildContext(),
            testCaseGenerateDrafts(),
            testCaseReviewDraft(),
            httpExecuteApprovedCase(),
            failureAnalyzeExecution(),
            reportGenerateTask()
        );
    }

    private static ToolContract apiAnalyzeSource() {
        return ToolContract.of(
            ToolNames.API_ANALYZE_SOURCE,
            ToolCapabilityGroup.API_ANALYSIS,
            "Analyze existing API source material into ProbeFlow ApiSpec metadata.",
            ToolInputSchema.of("Existing source material for API analysis.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task that owns the analysis."),
                ToolSchemaField.required("sourceMaterialId", ToolSchemaType.STRING, "Source material to parse.")
            ), "{\"taskId\":\"task-1\",\"sourceMaterialId\":\"source-1\"}"),
            ToolOutputSchema.of("ApiSpec id and deterministic analysis summary.", List.of(
                ToolSchemaField.required("apiSpecId", ToolSchemaType.STRING, "Generated or updated ApiSpec id."),
                ToolSchemaField.required("analysisSummary", ToolSchemaType.STRING, "Compact analysis summary.")
            )),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.SOURCE_MATERIAL_AVAILABLE),
            ToolRiskLevel.MEDIUM,
            ToolExecutionMode.AUTO_ALLOWED,
            false,
            Set.of("internal", "analysis", "mutating")
        );
    }

    private static ToolContract knowledgeRetrieveContext() {
        return ToolContract.of(
            ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT,
            ToolCapabilityGroup.KNOWLEDGE,
            "Retrieve relevant knowledge snippets and citations for the current task.",
            ToolInputSchema.of("Task-scoped retrieval query.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task requesting knowledge context."),
                ToolSchemaField.required("query", ToolSchemaType.STRING, "Retrieval query."),
                ToolSchemaField.optional("apiSpecId", ToolSchemaType.STRING, "Optional ApiSpec focus.")
            ), "{\"taskId\":\"task-1\",\"query\":\"auth boundary\"}"),
            ToolOutputSchema.of("Ranked knowledge citations for planner context.", List.of(
                ToolSchemaField.required("citations", ToolSchemaType.ARRAY, "Knowledge citation summaries.")
            )),
            Set.of(ToolPrecondition.TASK_EXISTS),
            ToolRiskLevel.LOW,
            ToolExecutionMode.AUTO_ALLOWED,
            false,
            Set.of("internal", "context", "read-only")
        );
    }

    private static ToolContract memoryBuildContext() {
        return ToolContract.of(
            ToolNames.MEMORY_BUILD_CONTEXT,
            ToolCapabilityGroup.MEMORY,
            "Build task, session and long-term memory context without writing business state.",
            ToolInputSchema.of("Task and optional ApiSpec memory focus.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task requesting memory context."),
                ToolSchemaField.optional("apiSpecId", ToolSchemaType.STRING, "Optional ApiSpec focus.")
            ), "{\"taskId\":\"task-1\"}"),
            ToolOutputSchema.of("Context bundle summary for downstream planning.", List.of(
                ToolSchemaField.required("contextBundleSummary", ToolSchemaType.STRING, "Memory context summary.")
            )),
            Set.of(ToolPrecondition.TASK_EXISTS),
            ToolRiskLevel.LOW,
            ToolExecutionMode.AUTO_ALLOWED,
            false,
            Set.of("internal", "context", "read-only")
        );
    }

    private static ToolContract testCaseGenerateDrafts() {
        return ToolContract.of(
            ToolNames.TESTCASE_GENERATE_DRAFTS,
            ToolCapabilityGroup.TEST_CASE,
            "Generate test case drafts from ApiSpec and context; output remains behind review gate.",
            ToolInputSchema.of("ApiSpec and generation mode for draft creation.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task owning generated drafts."),
                ToolSchemaField.required("apiSpecId", ToolSchemaType.STRING, "ApiSpec to cover."),
                ToolSchemaField.requiredEnum("generationMode", "Draft generation mode.", List.of("SINGLE", "SUITE", "BATCH"))
            ), "{\"taskId\":\"task-1\",\"apiSpecId\":\"api-1\",\"generationMode\":\"SINGLE\"}"),
            ToolOutputSchema.of("Draft ids and Review Gate status.", List.of(
                ToolSchemaField.required("draftIds", ToolSchemaType.ARRAY, "Generated draft identifiers."),
                ToolSchemaField.required("reviewGate", ToolSchemaType.STRING, "Review gate status.")
            )),
            Set.of(
                ToolPrecondition.TASK_EXISTS,
                ToolPrecondition.API_SPEC_AVAILABLE,
                ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE
            ),
            ToolRiskLevel.MEDIUM,
            ToolExecutionMode.AUTO_ALLOWED,
            false,
            Set.of("internal", "draft-output", "review-gate", "mutating")
        );
    }

    private static ToolContract testCaseReviewDraft() {
        return ToolContract.of(
            ToolNames.TESTCASE_REVIEW_DRAFT,
            ToolCapabilityGroup.TEST_CASE,
            "Record controlled review gate decision for a generated test case draft.",
            ToolInputSchema.of("Draft review decision.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task owning the draft."),
                ToolSchemaField.required("draftId", ToolSchemaType.STRING, "Draft under review."),
                ToolSchemaField.requiredEnum("decision", "Review decision.", List.of("APPROVE", "REJECT", "REQUEST_CHANGES"))
            ), "{\"taskId\":\"task-1\",\"draftId\":\"draft-1\",\"decision\":\"APPROVE\"}"),
            ToolOutputSchema.of("Review gate result and optional promoted test case id.", List.of(
                ToolSchemaField.required("reviewGate", ToolSchemaType.STRING, "Resulting review gate state."),
                ToolSchemaField.optional("testCaseId", ToolSchemaType.STRING, "Promoted test case id when approved.")
            )),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.TEST_CASE_DRAFT_EXISTS),
            ToolRiskLevel.HIGH,
            ToolExecutionMode.HUMAN_CONFIRMATION_REQUIRED,
            true,
            Set.of("internal", "review-gate", "mutating", "human-confirmation")
        );
    }

    private static ToolContract httpExecuteApprovedCase() {
        return ToolContract.of(
            ToolNames.HTTP_EXECUTE_APPROVED_CASE,
            ToolCapabilityGroup.HTTP_EXECUTION,
            "Execute an approved HTTP API test case against a configured environment.",
            ToolInputSchema.of("Approved test case execution request.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task owning the execution."),
                ToolSchemaField.required("testCaseId", ToolSchemaType.STRING, "Approved test case id."),
                ToolSchemaField.required("targetEnvironment", ToolSchemaType.STRING, "Configured target environment.")
            ), "{\"taskId\":\"task-1\",\"testCaseId\":\"case-1\",\"targetEnvironment\":\"local\"}"),
            ToolOutputSchema.of("Execution record id and response snapshot summary.", List.of(
                ToolSchemaField.required("executionRecordId", ToolSchemaType.STRING, "Execution record id."),
                ToolSchemaField.required("responseSnapshot", ToolSchemaType.OBJECT, "Response snapshot summary.")
            )),
            Set.of(
                ToolPrecondition.TASK_EXISTS,
                ToolPrecondition.TEST_CASE_EXISTS,
                ToolPrecondition.TEST_CASE_DRAFT_REVIEWED,
                ToolPrecondition.EXECUTION_READINESS_CONFIRMED
            ),
            ToolRiskLevel.HIGH,
            ToolExecutionMode.HUMAN_CONFIRMATION_REQUIRED,
            true,
            Set.of("internal", "http", "high-risk", "mutating", "human-confirmation")
        );
    }

    private static ToolContract failureAnalyzeExecution() {
        return ToolContract.of(
            ToolNames.FAILURE_ANALYZE_EXECUTION,
            ToolCapabilityGroup.FAILURE_ANALYSIS,
            "Analyze a failed execution record into observations and next-step guidance.",
            ToolInputSchema.of("Execution failure signal for analysis.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task owning the failure."),
                ToolSchemaField.required("executionRecordId", ToolSchemaType.STRING, "Execution record to analyze.")
            ), "{\"taskId\":\"task-1\",\"executionRecordId\":\"exec-1\"}"),
            ToolOutputSchema.of("Failure classification and observation summary.", List.of(
                ToolSchemaField.required("failureCategory", ToolSchemaType.STRING, "Failure category."),
                ToolSchemaField.optional("observationId", ToolSchemaType.STRING, "Persisted observation id when created.")
            )),
            Set.of(
                ToolPrecondition.TASK_EXISTS,
                ToolPrecondition.EXECUTION_RECORD_EXISTS,
                ToolPrecondition.FAILURE_SIGNAL_AVAILABLE
            ),
            ToolRiskLevel.MEDIUM,
            ToolExecutionMode.AUTO_ALLOWED,
            false,
            Set.of("internal", "analysis", "mutating")
        );
    }

    private static ToolContract reportGenerateTask() {
        return ToolContract.of(
            ToolNames.REPORT_GENERATE_TASK,
            ToolCapabilityGroup.REPORTING,
            "Generate a structured backend task report from existing process data.",
            ToolInputSchema.of("Task report generation request.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task to summarize.")
            ), "{\"taskId\":\"task-1\"}"),
            ToolOutputSchema.of("Report id and structured coverage summary.", List.of(
                ToolSchemaField.required("reportId", ToolSchemaType.STRING, "Generated report id."),
                ToolSchemaField.required("coverageSummary", ToolSchemaType.OBJECT, "Structured coverage summary.")
            )),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.REPORT_DATA_AVAILABLE),
            ToolRiskLevel.LOW,
            ToolExecutionMode.AUTO_ALLOWED,
            false,
            Set.of("internal", "reporting", "mutating")
        );
    }
}
