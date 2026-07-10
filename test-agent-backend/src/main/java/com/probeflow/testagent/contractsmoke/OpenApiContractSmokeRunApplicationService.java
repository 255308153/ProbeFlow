package com.probeflow.testagent.contractsmoke;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.httpexecution.HttpExecutionApplicationService;
import com.probeflow.testagent.httpexecution.HttpExecutionOptions;
import com.probeflow.testagent.httpexecution.HttpExecutionOutcomeStatus;
import com.probeflow.testagent.httpexecution.HttpExecutionRequest;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionRequest;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionService;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OpenApiContractSmokeRunApplicationService {

    private static final String GENERATOR = "openapi-contract-smoke";
    private static final String SCENARIO_CATEGORY = "CONTRACT_SMOKE";
    private static final String INTENT_KEY = "valid-request";

    private final ApiSpecRepository apiSpecs;
    private final TaskRepository tasks;
    private final TestCaseDraftRepository drafts;
    private final TestCasePromotionService promotionService;
    private final HttpExecutionApplicationService httpExecution;
    private final ExecutionRecordRepository executionRecords;
    private final ContractEligibilityEvaluator eligibilityEvaluator;
    private final DeterministicContractRequestGenerator requestGenerator;

    public OpenApiContractSmokeRunApplicationService(
        ApiSpecRepository apiSpecs,
        TaskRepository tasks,
        TestCaseDraftRepository drafts,
        TestCasePromotionService promotionService,
        HttpExecutionApplicationService httpExecution,
        ExecutionRecordRepository executionRecords,
        ContractEligibilityEvaluator eligibilityEvaluator,
        DeterministicContractRequestGenerator requestGenerator
    ) {
        this.apiSpecs = apiSpecs;
        this.tasks = tasks;
        this.drafts = drafts;
        this.promotionService = promotionService;
        this.httpExecution = httpExecution;
        this.executionRecords = executionRecords;
        this.eligibilityEvaluator = eligibilityEvaluator;
        this.requestGenerator = requestGenerator;
    }

    @Transactional
    public OpenApiContractSmokeRunResult run(OpenApiContractSmokeRunRequest request) {
        validateRequest(request);
        var apiSpec = apiSpecs.findById(request.apiSpecId())
            .orElseThrow(() -> new IllegalArgumentException("ApiSpec not found: " + request.apiSpecId()));

        var eligibility = eligibilityEvaluator.evaluate(apiSpec, request);
        if (!eligibility.eligible()) {
            return OpenApiContractSmokeRunResult.terminal(
                eligibility.outcome(),
                apiSpec.getApiSpecId(),
                request.profile(),
                eligibility.contractOrigin(),
                eligibility.diagnostics()
            );
        }

        var generatedRequest = requestGenerator.generate(apiSpec, request.profile());
        var task = resolveTask(request, apiSpec);
        var draft = persistSmokeDraft(task, apiSpec, request, eligibility, generatedRequest);
        var promotion = promotionService.promote(new TestCasePromotionRequest(
            List.of(draft.getDraftId()),
            StringUtils.hasText(request.requestedBy()) ? request.requestedBy() : "openapi-contract-smoke"
        ));
        var caseId = promotion.promotedCaseIds().getFirst();

        var executionResult = httpExecution.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(caseId),
            ExecutionMode.SINGLE,
            request.environment(),
            false,
            HttpExecutionOptions.defaults(),
            request.environmentVariables(),
            request.authVariables()
        ));
        var caseResult = executionResult.caseResults().getFirst();
        var executionRecordId = caseResult.executionRecordId();
        var actualStatus = caseResult.statusCode();
        var actualContentType = actualContentType(executionRecordId);
        var statusMatches = eligibility.expectedStatus() != null
            && actualStatus != null
            && eligibility.expectedStatus().equals(actualStatus);
        var contentTypeMatches = contentTypeMatches(eligibility.expectedContentType(), actualContentType);

        var diagnostics = new ArrayList<ContractSmokeDiagnostic>();
        if (caseResult.status() == HttpExecutionOutcomeStatus.BLOCKED) {
            diagnostics.add(ContractSmokeDiagnostic.blocked(
                "HTTP_EXECUTION_BLOCKED",
                caseResult.message() == null ? "HTTP execution was blocked." : caseResult.message(),
                "Resolve execution readiness and rerun contract smoke."
            ));
            return new OpenApiContractSmokeRunResult(
                ContractSmokeOutcome.BLOCKED,
                apiSpec.getApiSpecId(),
                task.getTaskId(),
                draft.getDraftId(),
                caseId,
                executionRecordId,
                request.profile(),
                eligibility.expectedStatus(),
                actualStatus,
                statusMatches,
                eligibility.expectedContentType(),
                actualContentType,
                contentTypeMatches,
                generatedRequest,
                eligibility.contractOrigin(),
                diagnostics
            );
        }

        var outcome = contractOutcome(caseResult.status(), statusMatches, contentTypeMatches, eligibility.expectedContentType());
        if (outcome == ContractSmokeOutcome.FAILED) {
            if (!Boolean.TRUE.equals(statusMatches)) {
                diagnostics.add(ContractSmokeDiagnostic.info(
                    "CONTRACT_STATUS_MISMATCH",
                    "Declared status " + eligibility.expectedStatus() + " does not match actual status " + actualStatus
                ));
            }
            if (StringUtils.hasText(eligibility.expectedContentType()) && !Boolean.TRUE.equals(contentTypeMatches)) {
                diagnostics.add(ContractSmokeDiagnostic.info(
                    "CONTRACT_CONTENT_TYPE_MISMATCH",
                    "Declared Content-Type " + eligibility.expectedContentType()
                        + " does not match actual Content-Type " + actualContentType
                ));
            }
        } else if (outcome == ContractSmokeOutcome.PASSED) {
            diagnostics.add(ContractSmokeDiagnostic.info(
                "CONTRACT_SMOKE_PASSED",
                "Valid request smoke matched declared status"
                    + (StringUtils.hasText(eligibility.expectedContentType()) ? " and Content-Type." : ".")
            ));
        }

        return new OpenApiContractSmokeRunResult(
            outcome,
            apiSpec.getApiSpecId(),
            task.getTaskId(),
            draft.getDraftId(),
            caseId,
            executionRecordId,
            request.profile(),
            eligibility.expectedStatus(),
            actualStatus,
            statusMatches,
            eligibility.expectedContentType(),
            actualContentType,
            contentTypeMatches,
            generatedRequest,
            eligibility.contractOrigin(),
            diagnostics
        );
    }

    private void validateRequest(OpenApiContractSmokeRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Contract smoke request is required");
        }
        if (!StringUtils.hasText(request.apiSpecId())) {
            throw new IllegalArgumentException("apiSpecId is required");
        }
    }

    private Task resolveTask(OpenApiContractSmokeRunRequest request, ApiSpec apiSpec) {
        if (StringUtils.hasText(request.taskId())) {
            var existing = tasks.findById(request.taskId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + request.taskId()));
            if (!existing.getTargetApiSpecIds().isEmpty()
                && !existing.getTargetApiSpecIds().contains(apiSpec.getApiSpecId())) {
                throw new IllegalArgumentException(
                    "Task " + existing.getTaskId() + " does not target ApiSpec " + apiSpec.getApiSpecId()
                );
            }
            return existing;
        }

        var task = new Task();
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("OpenAPI contract smoke: " + apiSpec.getHttpMethod() + " " + apiSpec.getPath());
        task.setStatus(TaskStatus.EXECUTING);
        task.setSourceType(sourceType(apiSpec));
        task.setSourceRef(apiSpec.getSourceRef());
        task.setTargetApiSpecIds(List.of(apiSpec.getApiSpecId()));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator(StringUtils.hasText(request.requestedBy()) ? request.requestedBy() : "openapi-contract-smoke");
        task.setMetadata(Map.of(
            "feature", "v6-2-openapi-contract-smoke",
            "profile", request.profile(),
            "apiSpecId", apiSpec.getApiSpecId()
        ));
        return tasks.save(task);
    }

    private TaskSourceType sourceType(ApiSpec apiSpec) {
        if (apiSpec.getSourceType() == null) {
            return TaskSourceType.MANUAL;
        }
        return switch (apiSpec.getSourceType()) {
            case OPENAPI, SWAGGER -> TaskSourceType.OPENAPI;
            case CODE_ANALYSIS -> TaskSourceType.CODE_REPO;
            case MANUAL -> TaskSourceType.MANUAL;
        };
    }

    private TestCaseDraft persistSmokeDraft(
        Task task,
        ApiSpec apiSpec,
        OpenApiContractSmokeRunRequest request,
        ContractEligibilityDecision eligibility,
        Map<String, Object> generatedRequest
    ) {
        var dedupKey = String.join(
            ":",
            "v6-2",
            "contract-smoke",
            task.getTaskId(),
            apiSpec.getApiSpecId(),
            request.profile(),
            INTENT_KEY,
            String.valueOf(eligibility.expectedStatus())
        );
        var existing = drafts.findByTaskIdAndDedupKeyOrderByCreatedAtAsc(task.getTaskId(), dedupKey).stream()
            .filter(draft -> draft.getStatus() == DraftStatus.PENDING_REVIEW && !StringUtils.hasText(draft.getPromotedCaseId()))
            .findFirst();

        var draft = existing.orElseGet(TestCaseDraft::new);
        draft.setTaskId(task.getTaskId());
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(DraftStatus.PENDING_REVIEW);
        draft.setPromotionMode(PromotionMode.AUTO);
        draft.setTargetApiSpecId(apiSpec.getApiSpecId());
        draft.setDedupKey(dedupKey);
        draft.setExpectedStatusCode(eligibility.expectedStatus());
        draft.setDraftContent(draftContent(apiSpec, request, eligibility, generatedRequest));
        return drafts.save(draft);
    }

    private Map<String, Object> draftContent(
        ApiSpec apiSpec,
        OpenApiContractSmokeRunRequest request,
        ContractEligibilityDecision eligibility,
        Map<String, Object> generatedRequest
    ) {
        var assertions = new ArrayList<Map<String, Object>>();
        assertions.add(Map.of(
            "type", "STATUS_CODE",
            "expected", eligibility.expectedStatus(),
            "critical", true
        ));
        if (StringUtils.hasText(eligibility.expectedContentType())) {
            assertions.add(Map.of(
                "type", "CONTENT_TYPE",
                "expected", eligibility.expectedContentType(),
                "critical", true
            ));
        }

        var executableShape = new LinkedHashMap<String, Object>();
        executableShape.put("method", generatedRequest.get("method"));
        executableShape.put("path", generatedRequest.get("path"));
        executableShape.put("headers", generatedRequest.getOrDefault("headers", Map.of()));
        executableShape.put("queryParams", generatedRequest.getOrDefault("queryParams", Map.of()));
        if (generatedRequest.containsKey("body")) {
            executableShape.put("body", generatedRequest.get("body"));
        }

        var generationMetadata = new LinkedHashMap<String, Object>();
        generationMetadata.put("mode", "SINGLE");
        generationMetadata.put("generator", GENERATOR);
        generationMetadata.put("profile", request.profile());
        generationMetadata.put("stageProfile", "contract_smoke");
        generationMetadata.put("apiSpecVersion", apiSpec.getVersion());
        generationMetadata.put("constraintSource", "API_CONTRACT");
        generationMetadata.put("contractOrigin", eligibility.contractOrigin());
        generationMetadata.put("scenarioIntent", Map.of(
            "category", SCENARIO_CATEGORY,
            "intentKey", INTENT_KEY,
            "expectedStatus", eligibility.expectedStatus()
        ));

        var content = new LinkedHashMap<String, Object>();
        content.put("title", "Contract smoke valid request for " + apiSpec.getHttpMethod() + " " + apiSpec.getPath());
        content.put(
            "description",
            "Deterministic OpenAPI contract smoke case generated from ApiSpec types, required fields, enums and basic constraints."
        );
        content.put("preconditions", List.of(
            "ApiSpec readiness is complete",
            "Execution environment baseUrl is configured",
            "Required authentication credentials are available when declared"
        ));
        content.put("steps", List.of(Map.of(
            "order", 1,
            "action", "Send contract smoke request",
            "apiSpecId", apiSpec.getApiSpecId(),
            "method", apiSpec.getHttpMethod().name(),
            "path", apiSpec.getPath(),
            "requestShape", executableShape,
            "expectedStatus", eligibility.expectedStatus()
        )));
        content.put(
            "expectedResult",
            "The API responds with HTTP " + eligibility.expectedStatus()
                + (StringUtils.hasText(eligibility.expectedContentType())
                    ? " and Content-Type " + eligibility.expectedContentType()
                    : "")
        );
        content.put("requestShape", executableShape);
        content.put("expectedStatus", eligibility.expectedStatus());
        content.put("assertions", assertions);
        content.put("scenarioCategory", SCENARIO_CATEGORY);
        content.put("scenarioName", "contract-smoke-valid");
        content.put("moduleName", apiSpec.getModuleName());
        content.put("tags", List.of("api", "single", "contract-smoke", "valid-request", apiSpec.getModuleName()));
        content.put("validationHints", List.of(
            "Validate declared status code",
            StringUtils.hasText(eligibility.expectedContentType())
                ? "Validate declared Content-Type"
                : "No declared Content-Type for this success response"
        ));
        content.put("priorityHint", "P1");
        content.put("riskHint", "MEDIUM");
        content.put("constraintSource", "API_CONTRACT");
        content.put("contextCitations", List.of());
        content.put("contextWarnings", List.of());
        content.put("generationMetadata", generationMetadata);
        content.put("contractOrigin", eligibility.contractOrigin());
        return content;
    }

    @SuppressWarnings("unchecked")
    private String actualContentType(String executionRecordId) {
        if (!StringUtils.hasText(executionRecordId)) {
            return null;
        }
        var record = executionRecords.findById(executionRecordId).orElse(null);
        if (record == null || record.getResponseSnapshot() == null) {
            return null;
        }
        var headers = record.getResponseSnapshot().get("headers");
        if (!(headers instanceof Map<?, ?> headerMap)) {
            return null;
        }
        for (var entry : headerMap.entrySet()) {
            if (entry.getKey() != null
                && "content-type".equalsIgnoreCase(String.valueOf(entry.getKey()))
                && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private Boolean contentTypeMatches(String expected, String actual) {
        if (!StringUtils.hasText(expected)) {
            return null;
        }
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return mediaTypeBase(expected).equalsIgnoreCase(mediaTypeBase(actual));
    }

    private String mediaTypeBase(String value) {
        var semicolon = value.indexOf(';');
        return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private ContractSmokeOutcome contractOutcome(
        HttpExecutionOutcomeStatus executionStatus,
        Boolean statusMatches,
        Boolean contentTypeMatches,
        String expectedContentType
    ) {
        if (executionStatus == HttpExecutionOutcomeStatus.ERROR) {
            return ContractSmokeOutcome.FAILED;
        }
        if (executionStatus == HttpExecutionOutcomeStatus.SKIPPED) {
            return ContractSmokeOutcome.SKIPPED;
        }
        if (executionStatus == HttpExecutionOutcomeStatus.BLOCKED) {
            return ContractSmokeOutcome.BLOCKED;
        }
        var statusOk = Boolean.TRUE.equals(statusMatches);
        var contentTypeOk = !StringUtils.hasText(expectedContentType) || Boolean.TRUE.equals(contentTypeMatches);
        if (statusOk && contentTypeOk && executionStatus == HttpExecutionOutcomeStatus.PASSED) {
            return ContractSmokeOutcome.PASSED;
        }
        return ContractSmokeOutcome.FAILED;
    }
}
