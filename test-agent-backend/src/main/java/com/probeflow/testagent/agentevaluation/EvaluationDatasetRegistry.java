package com.probeflow.testagent.agentevaluation;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EvaluationDatasetRegistry {

    public static final String SMOKE_DATASET = "v2-phase-8-smoke";
    public static final String PLANNER_DATASET = "v2-phase-8-planner";
    public static final String TOOL_POLICY_DATASET = "v2-phase-8-tool-policy";

    public EvaluationDataset load(String datasetName) {
        var effectiveName = datasetName == null || datasetName.isBlank() ? SMOKE_DATASET : datasetName.trim();
        return switch (effectiveName) {
            case SMOKE_DATASET -> smokeDataset();
            case PLANNER_DATASET -> plannerDataset();
            case TOOL_POLICY_DATASET -> toolPolicyDataset();
            default -> throw new IllegalArgumentException("Unknown evaluation dataset: " + effectiveName);
        };
    }

    private EvaluationDataset smokeDataset() {
        return new EvaluationDataset(
            SMOKE_DATASET,
            "2026-07-07",
            List.of(new GoldenTaskFixture(
                "smoke-foundation",
                List.of("evaluation-foundation"),
                "Run the deterministic smoke fixture through the evaluation harness.",
                EvaluationFixtureType.SMOKE,
                Map.of("providerMode", EvaluationProviderMode.DETERMINISTIC_FAKE.name(), "status", "PASSED"),
                Map.of("isolated", true, "usesRealLlm", false, "usesRealEmbedding", false, "usesExternalHttp", false)
            )),
            0.8d,
            Map.of("evaluation-foundation", 0.8d),
            Map.of("evaluation-foundation", 1.0d),
            Map.of("smoke-foundation", 0.8d)
        );
    }

    private EvaluationDataset plannerDataset() {
        return new EvaluationDataset(
            PLANNER_DATASET,
            "2026-07-07",
            List.of(
                plannerFixture("planner-continue", "CONTINUE", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "CONTINUE",
                    "confidenceMin", 0.9d,
                    "confidenceMax", 1.0d,
                    "riskLevel", "LOW"
                )),
                plannerFixture("planner-insert-step", "INSERT_STEP", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "INSERT_STEP",
                    "proposedStepType", "GENERATE_CASES",
                    "proposedToolName", "testcase.generate-drafts",
                    "confidenceMin", 0.8d,
                    "confidenceMax", 0.9d,
                    "riskLevel", "MEDIUM"
                )),
                plannerFixture("planner-replan", "REPLAN", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "REPLAN",
                    "confidenceMin", 0.6d,
                    "confidenceMax", 0.7d,
                    "riskLevel", "MEDIUM",
                    "blockers", List.of("State changed after last step")
                )),
                plannerFixture("planner-wait-for-human", "WAIT_FOR_HUMAN", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "WAIT_FOR_HUMAN",
                    "confidenceMin", 0.3d,
                    "confidenceMax", 0.4d,
                    "riskLevel", "HIGH",
                    "requiredHumanInputFields", List.of("targetEnvironment")
                )),
                plannerFixture("planner-stop", "STOP", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "STOP",
                    "confidenceMin", 0.8d,
                    "confidenceMax", 0.9d,
                    "riskLevel", "LOW",
                    "blockers", List.of("No further planning action is required")
                ))
            ),
            0.8d,
            Map.of("planner-decision", 0.8d),
            Map.of("planner-decision", 2.0d),
            Map.of()
        );
    }

    private GoldenTaskFixture plannerFixture(String fixtureId, String scenario, Map<String, Object> expected) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("planner-decision"),
            "Evaluate deterministic fake planner scenario " + scenario + ".",
            EvaluationFixtureType.PLANNER_DECISION,
            expected,
            Map.of("fakePlannerScenario", scenario, "isolated", true, "usesRealLlm", false)
        );
    }

    private EvaluationDataset toolPolicyDataset() {
        return new EvaluationDataset(
            TOOL_POLICY_DATASET,
            "2026-07-07",
            List.of(
                toolPolicyFixture(
                    "tool-policy-allowed",
                    Map.of(
                        "toolName", "knowledge.retrieve-context",
                        "taskPhase", "CONTEXT_BUILDING",
                        "toolInput", Map.of("taskId", "task-1", "query", "auth boundary"),
                        "satisfiedPreconditions", List.of("TASK_EXISTS")
                    ),
                    Map.of(
                        "toolName", "knowledge.retrieve-context",
                        "policyStatus", "ALLOWED",
                        "policyReason", "TOOL_ALLOWED_BY_POLICY"
                    )
                ),
                toolPolicyFixture(
                    "tool-policy-human-confirmation",
                    Map.of(
                        "toolName", "testcase.review-draft",
                        "taskPhase", "TEST_DESIGN",
                        "toolInput", Map.of("taskId", "task-1", "draftId", "draft-1", "decision", "APPROVE"),
                        "satisfiedPreconditions", List.of("TASK_EXISTS", "TEST_CASE_DRAFT_EXISTS")
                    ),
                    Map.of(
                        "toolName", "testcase.review-draft",
                        "policyStatus", "REQUIRES_HUMAN_CONFIRMATION",
                        "policyReason", "HUMAN_CONFIRMATION_REQUIRED"
                    )
                ),
                toolPolicyFixture(
                    "tool-policy-unknown",
                    Map.of("toolName", "imaginary.make-cases", "taskPhase", "TEST_DESIGN"),
                    Map.of(
                        "toolName", "imaginary.make-cases",
                        "policyStatus", "BLOCKED",
                        "policyReason", "UNKNOWN_TOOL"
                    )
                ),
                toolPolicyFixture(
                    "tool-policy-not-visible",
                    Map.of(
                        "toolName", "knowledge.retrieve-context",
                        "taskPhase", "CONTEXT_BUILDING",
                        "visibleToolNames", List.of("memory.build-context"),
                        "toolInput", Map.of("taskId", "task-1", "query", "auth boundary"),
                        "satisfiedPreconditions", List.of("TASK_EXISTS")
                    ),
                    Map.of(
                        "toolName", "knowledge.retrieve-context",
                        "policyStatus", "BLOCKED",
                        "policyReason", "TOOL_NOT_VISIBLE"
                    )
                ),
                toolPolicyFixture(
                    "tool-policy-not-whitelisted",
                    Map.of(
                        "toolName", "knowledge.retrieve-context",
                        "taskPhase", "CONTEXT_BUILDING",
                        "whitelistedTools", List.of("memory.build-context"),
                        "toolInput", Map.of("taskId", "task-1", "query", "auth boundary"),
                        "satisfiedPreconditions", List.of("TASK_EXISTS")
                    ),
                    Map.of(
                        "toolName", "knowledge.retrieve-context",
                        "policyStatus", "BLOCKED",
                        "policyReason", "TOOL_NOT_WHITELISTED"
                    )
                ),
                toolPolicyFixture(
                    "tool-policy-missing-precondition",
                    Map.of(
                        "toolName", "testcase.generate-drafts",
                        "taskPhase", "TEST_DESIGN",
                        "toolInput", Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "SINGLE"),
                        "satisfiedPreconditions", List.of("TASK_EXISTS", "API_SPEC_AVAILABLE")
                    ),
                    Map.of(
                        "toolName", "testcase.generate-drafts",
                        "policyStatus", "BLOCKED",
                        "policyReason", "MISSING_PRECONDITION"
                    )
                ),
                toolPolicyFixture(
                    "tool-policy-v1-boundary",
                    Map.of(
                        "toolName", "ui.run-automation",
                        "taskPhase", "EXECUTION",
                        "toolInput", Map.of("taskId", "task-1"),
                        "satisfiedPreconditions", List.of("TASK_EXISTS")
                    ),
                    Map.of(
                        "toolName", "ui.run-automation",
                        "policyStatus", "BLOCKED",
                        "policyReason", "V1_BOUNDARY_BLOCKED"
                    )
                )
            ),
            0.8d,
            Map.of("policy-validation", 0.8d),
            Map.of("policy-validation", 2.0d),
            Map.of()
        );
    }

    private GoldenTaskFixture toolPolicyFixture(
        String fixtureId,
        Map<String, Object> setup,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("policy-validation"),
            "Evaluate PolicyValidator outcome for " + setup.get("toolName") + ".",
            EvaluationFixtureType.TOOL_POLICY,
            expected,
            setup
        );
    }
}
