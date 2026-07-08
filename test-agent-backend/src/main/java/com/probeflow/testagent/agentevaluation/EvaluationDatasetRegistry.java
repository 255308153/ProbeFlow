package com.probeflow.testagent.agentevaluation;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EvaluationDatasetRegistry {

    public static final String SMOKE_DATASET = "v2-phase-8-smoke";
    public static final String PLANNER_DATASET = "v2-phase-8-planner";
    public static final String TOOL_POLICY_DATASET = "v2-phase-8-tool-policy";
    public static final String CONTEXT_CITATION_DATASET = "v2-phase-8-context-citation";
    public static final String FAILURE_CLASSIFICATION_DATASET = "v2-phase-8-failure-classification";
    public static final String CASE_COVERAGE_DATASET = "v2-phase-8-case-coverage";
    public static final String REPORT_USEFULNESS_DATASET = "v2-phase-8-report-usefulness";
    public static final String MEMORY_REUSE_DATASET = "v2-phase-8-memory-reuse";
    public static final String REGRESSION_SUITE_DATASET = "v2-phase-8-regression-suite";
    public static final String V3_SUITE_AGENT_DATASET = "v3-phase-6-suite-agent-capability";

    public EvaluationDataset load(String datasetName) {
        var effectiveName = datasetName == null || datasetName.isBlank() ? SMOKE_DATASET : datasetName.trim();
        return switch (effectiveName) {
            case SMOKE_DATASET -> smokeDataset();
            case PLANNER_DATASET -> plannerDataset();
            case TOOL_POLICY_DATASET -> toolPolicyDataset();
            case CONTEXT_CITATION_DATASET -> contextCitationDataset();
            case FAILURE_CLASSIFICATION_DATASET -> failureClassificationDataset();
            case CASE_COVERAGE_DATASET -> caseCoverageDataset();
            case REPORT_USEFULNESS_DATASET -> reportUsefulnessDataset();
            case MEMORY_REUSE_DATASET -> memoryReuseDataset();
            case REGRESSION_SUITE_DATASET -> regressionSuiteDataset();
            case V3_SUITE_AGENT_DATASET -> v3SuiteAgentDataset();
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
                Map.of("isolated", true, "uses_real_llm", false, "uses_real_embedding", false, "uses_external_http", false)
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
            Map.of("fakePlannerScenario", scenario, "isolated", true, "uses_real_llm", false)
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

    private EvaluationDataset contextCitationDataset() {
        return new EvaluationDataset(
            CONTEXT_CITATION_DATASET,
            "2026-07-07",
            List.of(new GoldenTaskFixture(
                "context-citation-payment-auth",
                List.of("context-citation"),
                "Evaluate payment auth context retrieval, citations and budget discipline.",
                EvaluationFixtureType.CONTEXT_CITATION,
                Map.of(
                    "expectedKnowledgeCitations", List.of("phase8/wiki/payment-auth-note.md"),
                    "expectedMemoryCitations", List.of("phase8-ltm-payment-auth"),
                    "expectedCoverage", List.of("api", "task-state", "knowledge", "memory"),
                    "allowedIrrelevantCitationCount", 0,
                    "tokenBudget", 500,
                    "expectedLowConfidence", false
                ),
                Map.of(
                    "taskId", "phase8-context-task-payment",
                    "apiSpecId", "phase8-context-api-payment",
                    "stageProfile", "failure_analysis",
                    "rawQuery", "payment auth PAY_401 tenant bootstrap",
                    "errorCode", "PAY_401",
                    "tags", List.of("payment", "auth", "tenant", "PAY_401"),
                    "tokenBudget", 500
                )
            )),
            0.8d,
            Map.of(ContextCitationUsefulnessEvaluator.METRIC_NAME, 0.8d),
            Map.of(ContextCitationUsefulnessEvaluator.METRIC_NAME, 2.0d),
            Map.of()
        );
    }

    private EvaluationDataset failureClassificationDataset() {
        return new EvaluationDataset(
            FAILURE_CLASSIFICATION_DATASET,
            "2026-07-07",
            List.of(
                failureFixture(
                    "failure-auth-401",
                    Map.of(
                        "overallStatus", "FAILED",
                        "statusCode", 401,
                        "requestPath", "/api/orders/pay",
                        "criticalFailed", false
                    ),
                    Map.of(
                        "classification", "AUTH_ISSUE",
                        "failureReasonContains", "AUTH_ISSUE",
                        "riskLevel", "MEDIUM",
                        "nextSuggestionContains", "Inspect auth variables",
                        "memoryCandidatePresent", true
                    )
                ),
                failureFixture(
                    "failure-validation-422",
                    Map.of(
                        "overallStatus", "FAILED",
                        "statusCode", 422,
                        "requestPath", "/api/orders",
                        "criticalFailed", false
                    ),
                    Map.of(
                        "classification", "VALIDATION_ISSUE",
                        "failureReasonContains", "VALIDATION_ISSUE",
                        "riskLevel", "LOW",
                        "nextSuggestionContains", "Review request data",
                        "memoryCandidatePresent", true
                    )
                ),
                failureFixture(
                    "failure-environment-missing",
                    Map.of(
                        "overallStatus", "BLOCKED",
                        "requestPath", "/api/orders",
                        "responseSnapshot", Map.of("errorType", "INVALID_REQUEST"),
                        "errorMessage", "Unresolved variable ${baseUrl}",
                        "criticalFailed", false
                    ),
                    Map.of(
                        "classification", "ENVIRONMENT_ISSUE",
                        "failureReasonContains", "Unresolved variable",
                        "riskLevel", "MEDIUM",
                        "nextSuggestionContains", "Inspect environment variables",
                        "memoryCandidatePresent", true
                    )
                ),
                failureFixture(
                    "failure-server-503",
                    Map.of(
                        "overallStatus", "FAILED",
                        "statusCode", 503,
                        "requestPath", "/api/orders/pay",
                        "criticalFailed", false
                    ),
                    Map.of(
                        "classification", "SERVER_ERROR",
                        "failureReasonContains", "SERVER_ERROR",
                        "riskLevel", "HIGH",
                        "nextSuggestionContains", "Retry once",
                        "memoryCandidatePresent", true
                    )
                ),
                failureFixture(
                    "failure-high-value-memory-candidate",
                    Map.of(
                        "overallStatus", "FAILED",
                        "statusCode", 503,
                        "requestPath", "/api/payments/capture",
                        "durationMs", 2500,
                        "criticalFailed", true
                    ),
                    Map.of(
                        "classification", "SERVER_ERROR",
                        "riskLevel", "HIGH",
                        "nextSuggestionContains", "investigate API regression",
                        "memoryCandidatePresent", true
                    )
                )
            ),
            0.8d,
            Map.of(FailureClassificationAccuracyEvaluator.METRIC_NAME, 0.8d),
            Map.of(FailureClassificationAccuracyEvaluator.METRIC_NAME, 2.0d),
            Map.of()
        );
    }

    private GoldenTaskFixture failureFixture(
        String fixtureId,
        Map<String, Object> setup,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("failure-classification"),
            "Evaluate FailureAnalysis classification and recovery suggestion for " + fixtureId + ".",
            EvaluationFixtureType.FAILURE_CLASSIFICATION,
            expected,
            setup
        );
    }

    private EvaluationDataset caseCoverageDataset() {
        return new EvaluationDataset(
            CASE_COVERAGE_DATASET,
            "2026-07-07",
            List.of(
                caseCoverageFixture(
                    "case-coverage-single-order",
                    Map.of(
                        "generationMode", "SINGLE",
                        "scenarioCategories", List.of(
                            "HAPPY_PATH",
                            "MISSING_REQUIRED",
                            "INVALID_VALUE",
                            "BOUNDARY_VALUE",
                            "AUTHENTICATION_FAILURE",
                            "BUSINESS_RULE"
                        ),
                        "includeKnowledge", true
                    ),
                    Map.of(
                        "expectedCoverageCategories", List.of(
                            "happy-path",
                            "validation-negative",
                            "auth-negative",
                            "boundary-value",
                            "business-rule"
                        ),
                        "requiredHappyPathScenario", "HAPPY_PATH",
                        "requiredValidationNegativeCases", List.of("MISSING_REQUIRED", "INVALID_VALUE"),
                        "requiredAuthNegativeCases", List.of("AUTHENTICATION_FAILURE"),
                        "requiredBoundaryValueCases", List.of("BOUNDARY_VALUE"),
                        "requiredBusinessRuleCases", List.of("BUSINESS_RULE"),
                        "requireRequestVariationEvidence", true,
                        "requireAssertionEvidence", true,
                        "requireScenarioMetadata", true,
                        "allowedDuplicateCount", 0
                    )
                ),
                caseCoverageFixture(
                    "case-coverage-suite-flow",
                    Map.of(
                        "generationMode", "SUITE",
                        "scenarioCategories", List.of("BUSINESS_FLOW")
                    ),
                    Map.of(
                        "expectedCoverageCategories", List.of("suite-dependency"),
                        "requiredSuiteDependencyCoverage", true,
                        "requireRequestVariationEvidence", true,
                        "requireAssertionEvidence", true,
                        "requireScenarioMetadata", true,
                        "allowedDuplicateCount", 0
                    )
                )
            ),
            0.8d,
            Map.of(TestCaseCoverageEvaluator.METRIC_NAME, 0.8d),
            Map.of(TestCaseCoverageEvaluator.METRIC_NAME, 2.0d),
            Map.of()
        );
    }

    private GoldenTaskFixture caseCoverageFixture(
        String fixtureId,
        Map<String, Object> setup,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("case-coverage"),
            "Evaluate generated TestCaseDraft coverage for " + fixtureId + ".",
            EvaluationFixtureType.TEST_CASE_COVERAGE,
            expected,
            setup
        );
    }

    private EvaluationDataset reportUsefulnessDataset() {
        return new EvaluationDataset(
            REPORT_USEFULNESS_DATASET,
            "2026-07-07",
            List.of(reportUsefulnessFixture(
                "report-usefulness-auth-failure",
                Map.of(
                    "statusCode", 401,
                    "overallStatus", "FAILED",
                    "requestPath", "/api/orders/pay",
                    "environment", "qa",
                    "criticalFailed", false
                ),
                Map.of(
                    "expectedReportSections", List.of(
                        "summary",
                        "execution-stats",
                        "failure-evidence",
                        "recommendations",
                        "source-references",
                        "memory-learning-summary"
                    ),
                    "expectedEvidenceTypes", List.of("execution", "observation", "citation", "memory-feedback"),
                    "expectedRecommendationContains", "Inspect credentials",
                    "forbidSecretLeakage", true
                )
            )),
            0.8d,
            Map.of(ReportUsefulnessNoSecretEvaluator.METRIC_NAME, 0.8d),
            Map.of(ReportUsefulnessNoSecretEvaluator.METRIC_NAME, 1.5d),
            Map.of()
        );
    }

    private GoldenTaskFixture reportUsefulnessFixture(
        String fixtureId,
        Map<String, Object> setup,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("report-usefulness"),
            "Evaluate generated report usefulness and no-secret safety for " + fixtureId + ".",
            EvaluationFixtureType.REPORT_USEFULNESS,
            expected,
            setup
        );
    }

    private EvaluationDataset regressionSuiteDataset() {
        return new EvaluationDataset(
            REGRESSION_SUITE_DATASET,
            "2026-07-07",
            List.of(
                plannerFixture("regression-planner-continue", "CONTINUE", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "CONTINUE",
                    "confidenceMin", 0.9d,
                    "confidenceMax", 1.0d,
                    "riskLevel", "LOW"
                )),
                toolPolicyFixture(
                    "regression-policy-validation-allowed",
                    Map.of(
                        "toolName", "knowledge.retrieve-context",
                        "taskPhase", "CONTEXT_BUILDING",
                        "toolInput", Map.of("taskId", "task-regression", "query", "payment auth"),
                        "satisfiedPreconditions", List.of("TASK_EXISTS")
                    ),
                    Map.of(
                        "toolName", "knowledge.retrieve-context",
                        "policyStatus", "ALLOWED",
                        "policyReason", "TOOL_ALLOWED_BY_POLICY"
                    )
                ),
                contextCitationFixture(
                    "regression-context-citation-payment-auth",
                    Map.ofEntries(
                        Map.entry("seedDeterministicContext", true),
                        Map.entry("taskId", "p8-reg-context-task-pay"),
                        Map.entry("apiSpecId", "p8-reg-context-api-pay"),
                        Map.entry("stageProfile", "failure_analysis"),
                        Map.entry("rawQuery", "payment auth PAY_REG_401 tenant bootstrap"),
                        Map.entry("systemName", "order-platform"),
                        Map.entry("moduleName", "payment-regression"),
                        Map.entry("apiPath", "/api/phase8/regression/orders/pay"),
                        Map.entry("errorCode", "PAY_REG_401"),
                        Map.entry("tags", List.of("payment", "auth", "tenant", "PAY_REG_401", "phase8-regression")),
                        Map.entry("tokenBudget", 500),
                        Map.entry("seedKnowledgeSourceRef", "phase8/regression/wiki/payment-auth-note.md"),
                        Map.entry("seedMemorySourceRef", "phase8-regression-ltm-payment-auth")
                    ),
                    Map.of(
                        "expectedKnowledgeCitations", List.of("phase8/regression/wiki/payment-auth-note.md"),
                        "expectedMemoryCitations", List.of("phase8-regression-ltm-payment-auth"),
                        "expectedCoverage", List.of("api", "task-state", "knowledge", "memory"),
                        "allowedIrrelevantCitationCount", 0,
                        "tokenBudget", 500,
                        "expectedLowConfidence", false
                    )
                ),
                failureFixture(
                    "regression-failure-auth-401",
                    Map.of(
                        "overallStatus", "FAILED",
                        "statusCode", 401,
                        "requestPath", "/api/orders/pay",
                        "criticalFailed", false
                    ),
                    Map.of(
                        "classification", "AUTH_ISSUE",
                        "failureReasonContains", "AUTH_ISSUE",
                        "riskLevel", "MEDIUM",
                        "nextSuggestionContains", "Inspect auth variables",
                        "memoryCandidatePresent", true
                    )
                ),
                caseCoverageFixture(
                    "regression-case-coverage-single-order",
                    Map.of(
                        "generationMode", "SINGLE",
                        "scenarioCategories", List.of(
                            "HAPPY_PATH",
                            "MISSING_REQUIRED",
                            "INVALID_VALUE",
                            "BOUNDARY_VALUE",
                            "AUTHENTICATION_FAILURE",
                            "BUSINESS_RULE"
                        ),
                        "includeKnowledge", true
                    ),
                    Map.of(
                        "expectedCoverageCategories", List.of(
                            "happy-path",
                            "validation-negative",
                            "auth-negative",
                            "boundary-value",
                            "business-rule"
                        ),
                        "requiredHappyPathScenario", "HAPPY_PATH",
                        "requiredValidationNegativeCases", List.of("MISSING_REQUIRED", "INVALID_VALUE"),
                        "requiredAuthNegativeCases", List.of("AUTHENTICATION_FAILURE"),
                        "requiredBoundaryValueCases", List.of("BOUNDARY_VALUE"),
                        "requiredBusinessRuleCases", List.of("BUSINESS_RULE"),
                        "requireRequestVariationEvidence", true,
                        "requireAssertionEvidence", true,
                        "requireScenarioMetadata", true,
                        "allowedDuplicateCount", 0
                    )
                ),
                reportUsefulnessFixture(
                    "regression-report-usefulness-auth-failure",
                    Map.of(
                        "statusCode", 401,
                        "overallStatus", "FAILED",
                        "requestPath", "/api/orders/pay",
                        "environment", "qa",
                        "criticalFailed", false
                    ),
                    Map.of(
                        "expectedReportSections", List.of(
                            "summary",
                            "execution-stats",
                            "failure-evidence",
                            "recommendations",
                            "source-references",
                            "memory-learning-summary"
                        ),
                        "expectedEvidenceTypes", List.of("execution", "observation", "citation", "memory-feedback"),
                        "expectedRecommendationContains", "Inspect credentials",
                        "forbidSecretLeakage", true
                    )
                ),
                memoryReuseFixture(
                    "regression-memory-reuse-payment-auth-loop",
                    Map.of(
                        "systemName", "order-platform",
                        "moduleName", "payment-regression-memory"
                    ),
                    Map.of(
                        "expectedFirstStageLearning", true,
                        "expectedRepeatedFailureMerge", true,
                        "expectedRecall", true,
                        "expectedCitation", true,
                        "expectedUsageRecord", true,
                        "expectedPositiveFeedbackIncrease", true,
                        "expectedNegativeFeedbackDecrease", true,
                        "expectedConsumer", "AGENT_EVALUATION"
                    )
                )
            ),
            0.8d,
            Map.of(
                PlannerDecisionAccuracyEvaluator.METRIC_NAME, 0.8d,
                ToolSelectionPolicyValidityEvaluator.METRIC_NAME, 0.8d,
                ContextCitationUsefulnessEvaluator.METRIC_NAME, 0.8d,
                FailureClassificationAccuracyEvaluator.METRIC_NAME, 0.8d,
                TestCaseCoverageEvaluator.METRIC_NAME, 0.8d,
                ReportUsefulnessNoSecretEvaluator.METRIC_NAME, 0.8d,
                MemoryReuseClosedLoopEvaluator.METRIC_NAME, 0.8d
            ),
            Map.of(
                PlannerDecisionAccuracyEvaluator.METRIC_NAME, 1.0d,
                ToolSelectionPolicyValidityEvaluator.METRIC_NAME, 1.5d,
                ContextCitationUsefulnessEvaluator.METRIC_NAME, 1.5d,
                FailureClassificationAccuracyEvaluator.METRIC_NAME, 1.5d,
                TestCaseCoverageEvaluator.METRIC_NAME, 1.5d,
                ReportUsefulnessNoSecretEvaluator.METRIC_NAME, 1.0d,
                MemoryReuseClosedLoopEvaluator.METRIC_NAME, 2.0d
            ),
            Map.of(
                "regression-planner-continue", 0.8d,
                "regression-policy-validation-allowed", 0.8d,
                "regression-context-citation-payment-auth", 0.8d,
                "regression-failure-auth-401", 0.8d,
                "regression-case-coverage-single-order", 0.8d,
                "regression-report-usefulness-auth-failure", 0.8d,
                "regression-memory-reuse-payment-auth-loop", 0.8d
            )
        );
    }

    private GoldenTaskFixture contextCitationFixture(
        String fixtureId,
        Map<String, Object> setup,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("context-citation"),
            "Evaluate ContextBundle citation quality for " + fixtureId + ".",
            EvaluationFixtureType.CONTEXT_CITATION,
            expected,
            setup
        );
    }

    private EvaluationDataset memoryReuseDataset() {
        return new EvaluationDataset(
            MEMORY_REUSE_DATASET,
            "2026-07-07",
            List.of(memoryReuseFixture(
                "memory-reuse-payment-auth-loop",
                Map.of(
                    "systemName", "order-platform",
                    "moduleName", "payment"
                ),
                Map.of(
                    "expectedFirstStageLearning", true,
                    "expectedRepeatedFailureMerge", true,
                    "expectedRecall", true,
                    "expectedCitation", true,
                    "expectedUsageRecord", true,
                    "expectedPositiveFeedbackIncrease", true,
                    "expectedNegativeFeedbackDecrease", true,
                    "expectedConsumer", "AGENT_EVALUATION"
                )
            )),
            0.8d,
            Map.of(MemoryReuseClosedLoopEvaluator.METRIC_NAME, 0.8d),
            Map.of(MemoryReuseClosedLoopEvaluator.METRIC_NAME, 2.0d),
            Map.of("memory-reuse-payment-auth-loop", 0.8d)
        );
    }

    private GoldenTaskFixture memoryReuseFixture(
        String fixtureId,
        Map<String, Object> setup,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("memory-reuse"),
            "Evaluate two-stage long-term memory learning, recall, usage audit and usefulness feedback.",
            EvaluationFixtureType.MEMORY_REUSE,
            expected,
            setup
        );
    }

    private EvaluationDataset v3SuiteAgentDataset() {
        return new EvaluationDataset(
            V3_SUITE_AGENT_DATASET,
            "2026-07-08",
            List.of(v3SuiteAgentFixture(), v3SuiteMemoryReuseFixture()),
            0.85d,
            Map.of(
                V3SuiteAgentCapabilityEvaluator.DEPENDENCY_COVERAGE_METRIC, 0.9d,
                V3SuiteAgentCapabilityEvaluator.VARIABLE_AUDIT_METRIC, 0.9d,
                V3SuiteAgentCapabilityEvaluator.FAILURE_ANALYSIS_METRIC, 0.9d,
                V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC, 0.9d,
                V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC, 0.8d,
                V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC, 1.0d,
                MemoryReuseClosedLoopEvaluator.V3_SUITE_METRIC_NAME, 0.85d
            ),
            Map.of(
                V3SuiteAgentCapabilityEvaluator.DEPENDENCY_COVERAGE_METRIC, 1.8d,
                V3SuiteAgentCapabilityEvaluator.VARIABLE_AUDIT_METRIC, 1.8d,
                V3SuiteAgentCapabilityEvaluator.FAILURE_ANALYSIS_METRIC, 2.2d,
                V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC, 2.4d,
                V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC, 1.0d,
                V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC, 2.5d,
                MemoryReuseClosedLoopEvaluator.V3_SUITE_METRIC_NAME, 2.3d
            ),
            Map.of(
                "v3-suite-agent-order-payment-loop", 0.85d,
                "v3-suite-memory-reuse-order-payment-loop", 0.85d
            )
        );
    }

    private GoldenTaskFixture v3SuiteAgentFixture() {
        return new GoldenTaskFixture(
            "v3-suite-agent-order-payment-loop",
            List.of(
                "v3",
                "suite",
                "suite-dependency",
                "variable-audit",
                "failure-analysis",
                "memory-feedback",
                "harness-demo",
                "redaction"
            ),
            "Evaluate deterministic fake V3 suite agent dependency, runtime audit, failure analysis, memory feedback and harness output.",
            EvaluationFixtureType.V3_SUITE_AGENT,
            Map.ofEntries(
                Map.entry("expectedDependencyPairs", List.of("create-order->pay-order")),
                Map.entry("expectedVariableWrites", List.of("suite.orderId")),
                Map.entry("expectedVariableOverwrites", List.of("suite.orderId")),
                Map.entry("expectedVariableConsumers", List.of("pay-order:${suite.orderId}")),
                Map.entry("expectedMissingDiagnostics", List.of("SUITE_VARIABLE_MISSING")),
                Map.entry("expectedFailureClassification", "VARIABLE_EXTRACTION_FAILURE"),
                Map.entry("expectedRootStep", "create-order"),
                Map.entry("expectedAffectedDownstreamSteps", List.of("pay-order")),
                Map.entry("expectedNextSuggestionContains", "Fix BODY_JSON extractRule $.data.id"),
                Map.entry("expectedMemoryTags", List.of("v3", "suite", "failure-analysis", "memory-feedback", "variable-extraction")),
                Map.entry("expectedMemoryConfidenceMin", 0.85d),
                Map.entry("expectedMemoryEvidence", List.of("rootStep=create-order", "failedVariable=suite.orderId", "diagnostic=SUITE_VARIABLE_MISSING")),
                Map.entry("expectedHarnessSections", List.of(
                    "generated-suite-draft",
                    "execution-result",
                    "variable-audit",
                    "failure-analysis",
                    "memory-feedback",
                    "evaluation-comparison"
                )),
                Map.entry("expectedHarnessRealSections", List.of(
                    "generated-suite-draft",
                    "execution-result",
                    "variable-audit",
                    "failure-analysis"
                ))
            ),
            Map.of(
                "suiteDraft", Map.of(
                    "dependencyPairs", List.of("create-order->pay-order"),
                    "extractRules", List.of(Map.of(
                        "id", "extract-order-id",
                        "stepId", "create-order",
                        "type", "BODY_JSON",
                        "sourcePath", "$.data.id",
                        "targetScope", "suite",
                        "targetKey", "orderId"
                    )),
                    "variableReferences", List.of(Map.of(
                        "stepId", "pay-order",
                        "expression", "${suite.orderId}",
                        "producerStepId", "create-order"
                    ))
                ),
                "variableAudit", Map.of(
                    "writes", List.of("suite.orderId"),
                    "overwrites", List.of("suite.orderId"),
                    "consumes", List.of("pay-order:${suite.orderId}"),
                    "missingDiagnostics", List.of("SUITE_VARIABLE_MISSING")
                ),
                "failureAnalysis", Map.of(
                    "classification", "VARIABLE_EXTRACTION_FAILURE",
                    "rootStep", "create-order",
                    "affectedDownstreamSteps", List.of("pay-order"),
                    "nextSuggestion", "Fix BODY_JSON extractRule $.data.id before retrying the pay-order consumer step."
                ),
                "memoryCandidate", Map.of(
                    "sourceRef", "suite-failure-analysis:execution-v3-order-payment:variable-extraction",
                    "tags", List.of("v3", "suite", "failure-analysis", "memory-feedback", "variable-extraction"),
                    "confidence", 0.9d,
                    "evidence", List.of(
                        "rootStep=create-order",
                        "failedVariable=suite.orderId",
                        "diagnostic=SUITE_VARIABLE_MISSING"
                    ),
                    "applicableWhen", "A consumer references ${suite.orderId} after create-order extraction.",
                    "content", "Learn orderId extraction from $.data.id; Authorization=[REDACTED], token=[REDACTED]."
                ),
                "harness", Map.of("sections", Map.of(
                    "generated-suite-draft", "REAL",
                    "execution-result", "REAL",
                    "variable-audit", "REAL",
                    "failure-analysis", "REAL",
                    "memory-feedback", "FIXTURE",
                    "evaluation-comparison", "FIXTURE"
                )),
                "report", Map.of(
                    "markdown", "V3 suite report uses Authorization=[REDACTED], cookie=[REDACTED], secret=[REDACTED].",
                    "json", Map.of("apiKey", "[REDACTED]", "token", "[REDACTED]")
                )
            )
        );
    }

    private GoldenTaskFixture v3SuiteMemoryReuseFixture() {
        return new GoldenTaskFixture(
            "v3-suite-memory-reuse-order-payment-loop",
            List.of("v3", "suite", "memory-reuse", "closed-loop", "manual-real-boundary", "redaction"),
            "Evaluate V3 SUITE memory learning, later reuse signal and manual real experiment boundary.",
            EvaluationFixtureType.V3_SUITE_MEMORY_REUSE,
            Map.of(
                "expectedFirstStageLearning", true,
                "expectedRepeatedFailureMerge", true,
                "expectedRecall", true,
                "expectedCitation", true,
                "expectedUsageRecord", true,
                "expectedConsumer", "AGENT_EVALUATION",
                "expectedUsesRealProviderByDefault", false,
                "expectedManualRealWritesLongTermMemoryByDefault", false
            ),
            Map.of(
                "systemName", "order-platform",
                "moduleName", "v3-suite-payment-memory",
                "providerMode", EvaluationProviderMode.DETERMINISTIC_FAKE.name(),
                "uses_real_llm", false,
                "uses_real_embedding", false,
                "uses_external_http", false
            )
        );
    }
}
