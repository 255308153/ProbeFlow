package com.probeflow.testagent.testcasegeneration;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.memory.ContextBundle;
import com.probeflow.testagent.memory.ContextCitation;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.SessionMemoryView;
import com.probeflow.testagent.memory.TaskMemoryView;
import com.probeflow.testagent.memory.UnifiedContextBuilder;
import com.probeflow.testagent.memory.UnifiedContextQuery;
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TestCaseGenerationApplicationService {

    private static final int DEFAULT_TOKEN_BUDGET = 1200;
    private static final String STAGE_PROFILE = "case_generation";

    private final TaskRepository tasks;
    private final ApiSpecRepository apiSpecs;
    private final TestCaseDraftRepository drafts;
    private final UnifiedContextBuilder unifiedContextBuilder;

    public TestCaseGenerationApplicationService(
        TaskRepository tasks,
        ApiSpecRepository apiSpecs,
        TestCaseDraftRepository drafts,
        UnifiedContextBuilder unifiedContextBuilder
    ) {
        this.tasks = tasks;
        this.apiSpecs = apiSpecs;
        this.drafts = drafts;
        this.unifiedContextBuilder = unifiedContextBuilder;
    }

    @Transactional
    public TestCaseGenerationResult generate(TestCaseGenerationRequest request) {
        validateSingleRequest(request);

        var task = tasks.findById(request.taskId())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + request.taskId()));
        var apiSpecId = request.targetApiSpecIds().getFirst();
        var apiSpec = apiSpecs.findById(apiSpecId)
            .orElseThrow(() -> new IllegalArgumentException("ApiSpec not found: " + apiSpecId));
        var requestedCategories = requestedCategories(request);
        var structuralDiagnostics = structuralDiagnostics(apiSpec);
        if (!structuralDiagnostics.isEmpty()) {
            return incompleteResult(request, apiSpec, requestedCategories, structuralDiagnostics);
        }

        var context = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            request.sessionId(),
            apiSpec.getApiSpecId(),
            null,
            STAGE_PROFILE,
            rawQueryFor(apiSpec),
            apiSpec.getSystemName(),
            apiSpec.getModuleName(),
            apiSpec.getPath(),
            null,
            contextTags(apiSpec, requestedCategories),
            tokenBudget(request)
        ));

        var plan = planScenarioIntents(apiSpec, context, requestedCategories);
        var createdDraftIds = new ArrayList<String>();
        var updatedDraftIds = new ArrayList<String>();
        var duplicateSuppressed = 0;
        var protectedSkipped = 0;
        var coverageByCategory = new LinkedHashMap<ScenarioCategory, ScenarioCoverage>();
        for (var intent : plan.intents()) {
            var persistence = persistDraft(task.getTaskId(), task.getPromotionMode(), apiSpec, context, tokenBudget(request), intent);
            switch (persistence.action()) {
                case CREATED -> {
                    createdDraftIds.add(persistence.draftId());
                    coverageByCategory.put(intent.category(), new ScenarioCoverage(
                        intent.category(),
                        CoverageStatus.GENERATED,
                        "Generated draft from deterministic scenario plan",
                        persistence.draftId()
                    ));
                }
                case UPDATED -> {
                    updatedDraftIds.add(persistence.draftId());
                    coverageByCategory.put(intent.category(), new ScenarioCoverage(
                        intent.category(),
                        CoverageStatus.UPDATED,
                        "Updated compatible pending draft with regenerated content",
                        persistence.draftId()
                    ));
                }
                case DUPLICATE_SUPPRESSED -> {
                    duplicateSuppressed++;
                    coverageByCategory.put(intent.category(), new ScenarioCoverage(
                        intent.category(),
                        CoverageStatus.SKIPPED,
                        "Equivalent draft already exists for deterministic dedup key",
                        persistence.draftId()
                    ));
                }
                case PROTECTED_SKIPPED -> {
                    protectedSkipped++;
                    coverageByCategory.put(intent.category(), new ScenarioCoverage(
                        intent.category(),
                        CoverageStatus.BLOCKED,
                        "Existing draft is promoted or otherwise protected from regeneration",
                        persistence.draftId()
                    ));
                }
            }
        }
        plan.skippedCategories().forEach((category, reason) -> coverageByCategory.put(category, new ScenarioCoverage(
            category,
            CoverageStatus.SKIPPED,
            reason,
            null
        )));
        plan.unsupportedCategories().forEach((category, reason) -> coverageByCategory.put(category, new ScenarioCoverage(
            category,
            CoverageStatus.UNSUPPORTED,
            reason,
            null
        )));
        for (var category : requestedCategories) {
            coverageByCategory.putIfAbsent(category, new ScenarioCoverage(
                category,
                CoverageStatus.MISSING,
                "Requested scenario category was not produced by the deterministic planner",
                null
            ));
        }
        var resultWarnings = warnings(context);
        return new TestCaseGenerationResult(
            task.getTaskId(),
            request.sessionId(),
            request.generationMode(),
            List.of(apiSpec.getApiSpecId()),
            List.copyOf(createdDraftIds),
            plan.intents().stream().map(ScenarioIntent::category).toList(),
            plan.skippedCategories(),
            plan.unsupportedCategories(),
            List.of(new TargetCoverageSummary(
                apiSpec.getApiSpecId(),
                targetStatus(coverageByCategory),
                orderedCoverage(requestedCategories, coverageByCategory),
                resultWarnings,
                List.of()
            )),
            resultWarnings,
            new TestCaseGenerationCounts(
                createdDraftIds.size(),
                updatedDraftIds.size(),
                plan.skippedCategories().size() + plan.unsupportedCategories().size() + protectedSkipped,
                duplicateSuppressed
            )
        );
    }

    private TestCaseGenerationResult incompleteResult(
        TestCaseGenerationRequest request,
        ApiSpec apiSpec,
        List<ScenarioCategory> requestedCategories,
        List<String> diagnostics
    ) {
        var coverage = requestedCategories.stream()
            .map(category -> new ScenarioCoverage(
                category,
                CoverageStatus.INCOMPLETE,
                "ApiSpec is structurally insufficient for deterministic draft generation",
                null
            ))
            .toList();
        var warnings = diagnostics.stream()
            .map(diagnostic -> "INCOMPLETE_APISPEC: " + diagnostic)
            .toList();
        return new TestCaseGenerationResult(
            request.taskId(),
            request.sessionId(),
            request.generationMode(),
            List.of(apiSpec.getApiSpecId()),
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(new TargetCoverageSummary(
                apiSpec.getApiSpecId(),
                CoverageStatus.INCOMPLETE,
                coverage,
                warnings,
                diagnostics
            )),
            warnings,
            new TestCaseGenerationCounts(0, 0, requestedCategories.size(), 0)
        );
    }

    private DraftPersistenceResult persistDraft(
        String taskId,
        PromotionMode promotionMode,
        ApiSpec apiSpec,
        ContextBundle context,
        int tokenBudget,
        ScenarioIntent intent
    ) {
        var incoming = draftFromIntent(taskId, promotionMode, apiSpec, context, tokenBudget, intent);
        var existingDrafts = drafts.findByTaskIdAndDedupKeyOrderByCreatedAtAsc(taskId, incoming.getDedupKey());
        if (existingDrafts.isEmpty()) {
            var saved = drafts.save(incoming);
            return new DraftPersistenceResult(DraftPersistenceAction.CREATED, saved.getDraftId());
        }

        var updateCandidate = existingDrafts.stream()
            .filter(this::canUpdateDraft)
            .findFirst();
        if (updateCandidate.isEmpty()) {
            return new DraftPersistenceResult(DraftPersistenceAction.PROTECTED_SKIPPED, existingDrafts.getFirst().getDraftId());
        }

        var draft = updateCandidate.get();
        if (draftEquivalent(draft, incoming)) {
            return new DraftPersistenceResult(DraftPersistenceAction.DUPLICATE_SUPPRESSED, draft.getDraftId());
        }

        draft.setSource(incoming.getSource());
        draft.setStage(incoming.getStage());
        draft.setStatus(incoming.getStatus());
        draft.setPromotionMode(incoming.getPromotionMode());
        draft.setTargetApiSpecId(incoming.getTargetApiSpecId());
        draft.setExpectedStatusCode(incoming.getExpectedStatusCode());
        draft.setDraftContent(incoming.getDraftContent());
        var saved = drafts.save(draft);
        return new DraftPersistenceResult(DraftPersistenceAction.UPDATED, saved.getDraftId());
    }

    private boolean canUpdateDraft(TestCaseDraft draft) {
        return draft.getStatus() == DraftStatus.PENDING_REVIEW && !StringUtils.hasText(draft.getPromotedCaseId());
    }

    private boolean draftEquivalent(TestCaseDraft existing, TestCaseDraft incoming) {
        return existing.getExpectedStatusCode().equals(incoming.getExpectedStatusCode())
            && existing.getDraftContent().equals(incoming.getDraftContent());
    }

    private void validateSingleRequest(TestCaseGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Generation request is required");
        }
        if (!StringUtils.hasText(request.taskId())) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (request.generationMode() != TestCaseGenerationMode.SINGLE) {
            throw new IllegalArgumentException("Issue 01 supports SINGLE generation only");
        }
        if (request.targetApiSpecIds() == null || request.targetApiSpecIds().size() != 1) {
            throw new IllegalArgumentException("SINGLE generation requires exactly one target ApiSpec");
        }
    }

    private List<String> structuralDiagnostics(ApiSpec apiSpec) {
        var diagnostics = new ArrayList<String>();
        if (apiSpec.getHttpMethod() == null || !StringUtils.hasText(apiSpec.getPath())) {
            diagnostics.add("ApiSpec is missing route structure: " + apiSpec.getApiSpecId());
        }
        if (!apiSpec.isRouteReady() || !apiSpec.isBasicParamReady()) {
            diagnostics.add("ApiSpec is not ready for case generation: " + apiSpec.getApiSpecId());
        }
        return List.copyOf(diagnostics);
    }

    private TestCaseDraft draftFromIntent(
        String taskId,
        PromotionMode promotionMode,
        ApiSpec apiSpec,
        ContextBundle context,
        int tokenBudget,
        ScenarioIntent intent
    ) {
        var draft = new TestCaseDraft();
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(DraftStatus.PENDING_REVIEW);
        draft.setPromotionMode(promotionMode);
        draft.setTargetApiSpecId(apiSpec.getApiSpecId());
        draft.setDedupKey(dedupKey(taskId, apiSpec.getApiSpecId(), intent));
        draft.setExpectedStatusCode(intent.expectedStatus());
        draft.setDraftContent(draftContent(apiSpec, context, tokenBudget, intent));
        return draft;
    }

    private Map<String, Object> draftContent(
        ApiSpec apiSpec,
        ContextBundle context,
        int tokenBudget,
        ScenarioIntent intent
    ) {
        var content = new LinkedHashMap<String, Object>();
        content.put("title", intent.title());
        content.put("description", intent.description());
        content.put("preconditions", List.of("A valid API client is available", "Required request data is prepared"));
        content.put("steps", List.of(Map.of(
            "order", 1,
            "action", "Call HTTP API",
            "apiSpecId", apiSpec.getApiSpecId(),
            "method", apiSpec.getHttpMethod().name(),
            "path", apiSpec.getPath(),
            "requestShape", intent.requestShape()
        )));
        content.put("expectedResult", expectedResult(intent));
        content.put("requestShape", intent.requestShape());
        content.put("expectedStatus", intent.expectedStatus());
        content.put("scenarioCategory", intent.category().name());
        content.put("scenarioName", scenarioName(intent.category()));
        content.put("moduleName", apiSpec.getModuleName());
        content.put("tags", intent.tags());
        content.put("validationHints", intent.validationHints());
        content.put("priorityHint", intent.priorityHint());
        content.put("riskHint", intent.riskHint());
        content.put("constraintSource", intent.constraintSource());
        content.put("contextCitations", intent.contextCitations());
        content.put("contextWarnings", warnings(context));
        content.put("generationMetadata", generationMetadata(apiSpec, context, tokenBudget, intent));
        return content;
    }

    private Map<String, Object> generationMetadata(
        ApiSpec apiSpec,
        ContextBundle context,
        int tokenBudget,
        ScenarioIntent intent
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("mode", TestCaseGenerationMode.SINGLE.name());
        metadata.put("generator", "deterministic-baseline");
        metadata.put("stageProfile", STAGE_PROFILE);
        metadata.put("apiSpecVersion", apiSpec.getVersion());
        metadata.put("contextBuilt", context.apiContext() != null);
        metadata.put("contextBudgetRequested", tokenBudget);
        metadata.put("contextLowConfidence", context.coverage().lowConfidence());
        metadata.put("contextConflictCount", context.conflicts().size());
        metadata.put("constraintSource", intent.constraintSource());
        metadata.put("source", CaseSource.STRUCTURE.name());
        metadata.put("scenarioIntent", Map.of(
            "category", intent.category().name(),
            "intentKey", intent.intentKey(),
            "expectedStatus", intent.expectedStatus()
        ));
        return metadata;
    }

    private ScenarioPlan planScenarioIntents(ApiSpec apiSpec, ContextBundle context, List<ScenarioCategory> requestedCategories) {
        var intents = new ArrayList<ScenarioIntent>();
        var skipped = new LinkedHashMap<ScenarioCategory, String>();
        var unsupported = new LinkedHashMap<ScenarioCategory, String>();
        var parameters = parameters(apiSpec);
        var requiredParameter = parameters.stream().filter(ParameterRef::required).findFirst();
        var constrainedParameter = firstConstrainedParameter(apiSpec, parameters);

        for (var category : requestedCategories) {
            switch (category) {
                case HAPPY_PATH -> intents.add(happyPathIntent(apiSpec));
                case MISSING_REQUIRED -> {
                    if (requiredParameter.isPresent()) {
                        intents.add(missingRequiredIntent(apiSpec, requiredParameter.get()));
                    } else {
                        skipped.put(category, "No required parameters are represented in ApiSpec metadata");
                    }
                }
                case INVALID_VALUE -> {
                    if (constrainedParameter != null) {
                        intents.add(invalidValueIntent(apiSpec, constrainedParameter));
                    } else if (!parameters.isEmpty()) {
                        intents.add(invalidValueIntent(apiSpec, parameters.getFirst()));
                    } else {
                        skipped.put(category, "No parameters are represented for invalid-value planning");
                    }
                }
                case BOUNDARY_VALUE -> {
                    if (constrainedParameter != null) {
                        intents.add(boundaryValueIntent(apiSpec, constrainedParameter));
                    } else {
                        skipped.put(category, "No parameter constraints are represented for boundary planning");
                    }
                }
                case AUTHENTICATION_FAILURE -> {
                    if (hasAuthMetadata(apiSpec)) {
                        intents.add(authenticationFailureIntent(apiSpec));
                    } else {
                        unsupported.put(category, "No authentication metadata is represented for this ApiSpec");
                    }
                }
                case PERMISSION_FAILURE -> {
                    if (hasPermissionHints(apiSpec)) {
                        intents.add(permissionFailureIntent(apiSpec));
                    } else {
                        unsupported.put(category, "No role, permission, or scope hints are represented for this ApiSpec");
                    }
                }
                case BUSINESS_RULE -> {
                    var entry = firstKnowledgeEntry(context);
                    if (entry != null) {
                        intents.add(businessRuleIntent(apiSpec, entry, context));
                    } else {
                        skipped.put(category, "No Knowledge context entries are available for business-rule planning");
                    }
                }
                case HISTORICAL_FAILURE -> {
                    var memory = firstFailureMemory(context);
                    if (memory != null) {
                        intents.add(historicalFailureIntent(apiSpec, memory, context));
                    } else {
                        skipped.put(category, "No failure-pattern memory is available for historical-failure planning");
                    }
                }
                case REGRESSION_RISK -> {
                    var memory = firstRegressionMemory(context);
                    if (memory != null) {
                        intents.add(regressionRiskIntent(apiSpec, memory, context));
                    } else if (!context.knowledgeContext().incidentHints().isEmpty()) {
                        intents.add(regressionRiskIntent(apiSpec, context.knowledgeContext().incidentHints().getFirst(), context));
                    } else {
                        skipped.put(category, "No Memory or incident context is available for regression-risk planning");
                    }
                }
            }
        }

        return new ScenarioPlan(List.copyOf(intents), Map.copyOf(skipped), Map.copyOf(unsupported));
    }

    private ScenarioIntent happyPathIntent(ApiSpec apiSpec) {
        var expectedStatus = successStatus(apiSpec.getHttpMethod());
        var titleSubject = StringUtils.hasText(apiSpec.getSummary())
            ? apiSpec.getSummary()
            : apiSpec.getHttpMethod() + " " + apiSpec.getPath();
        return new ScenarioIntent(
            ScenarioCategory.HAPPY_PATH,
            "happy-path",
            titleSubject + " happy path",
            "Verify the normal successful HTTP API behavior for " + apiSpec.getPath() + ".",
            expectedStatus,
            requestShape(apiSpec),
            List.of("Expect a successful response body"),
            tags(apiSpec, ScenarioCategory.HAPPY_PATH),
            "P1",
            "MEDIUM",
            "API_CONTRACT",
            List.of()
        );
    }

    private ScenarioIntent missingRequiredIntent(ApiSpec apiSpec, ParameterRef parameter) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of(
            "type", "omit-required-parameter",
            "section", parameter.section(),
            "parameter", parameter.name()
        ));
        return new ScenarioIntent(
            ScenarioCategory.MISSING_REQUIRED,
            "missing-required-" + parameter.name(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " missing required " + parameter.name(),
            "Verify the API rejects a request when required parameter " + parameter.name() + " is omitted.",
            400,
            shape,
            List.of("Required parameter should be validated before business processing", "Missing parameter: " + parameter.name()),
            tags(apiSpec, ScenarioCategory.MISSING_REQUIRED),
            "P1",
            "HIGH",
            "API_CONTRACT",
            List.of()
        );
    }

    private ScenarioIntent invalidValueIntent(ApiSpec apiSpec, ParameterRef parameter) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of(
            "type", "invalid-value",
            "section", parameter.section(),
            "parameter", parameter.name(),
            "invalidValue", invalidValueFor(parameter)
        ));
        return new ScenarioIntent(
            ScenarioCategory.INVALID_VALUE,
            "invalid-value-" + parameter.name(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " invalid " + parameter.name(),
            "Verify the API rejects an invalid value for parameter " + parameter.name() + ".",
            400,
            shape,
            List.of("Invalid value should produce a contract validation error", "Parameter under test: " + parameter.name()),
            tags(apiSpec, ScenarioCategory.INVALID_VALUE),
            "P1",
            "HIGH",
            "API_CONTRACT",
            List.of()
        );
    }

    private ScenarioIntent boundaryValueIntent(ApiSpec apiSpec, ParameterRef parameter) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of(
            "type", "boundary-value",
            "section", parameter.section(),
            "parameter", parameter.name(),
            "constraint", constraintSnapshot(parameter)
        ));
        return new ScenarioIntent(
            ScenarioCategory.BOUNDARY_VALUE,
            "boundary-value-" + parameter.name(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " boundary " + parameter.name(),
            "Verify parameter " + parameter.name() + " behaves correctly around documented boundaries.",
            400,
            shape,
            List.of("Exercise min/max or length boundary from ApiSpec constraints", "Parameter under test: " + parameter.name()),
            tags(apiSpec, ScenarioCategory.BOUNDARY_VALUE),
            "P2",
            "MEDIUM",
            "API_CONTRACT",
            List.of()
        );
    }

    private ScenarioIntent authenticationFailureIntent(ApiSpec apiSpec) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of("type", "omit-authentication"));
        return new ScenarioIntent(
            ScenarioCategory.AUTHENTICATION_FAILURE,
            "authentication-failure",
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " rejects missing authentication",
            "Verify the API rejects calls without required authentication credentials.",
            401,
            shape,
            List.of("Authentication metadata should cause unauthenticated requests to be rejected"),
            tags(apiSpec, ScenarioCategory.AUTHENTICATION_FAILURE),
            "P1",
            "HIGH",
            "API_CONTRACT",
            List.of()
        );
    }

    private ScenarioIntent permissionFailureIntent(ApiSpec apiSpec) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of("type", "insufficient-permission"));
        return new ScenarioIntent(
            ScenarioCategory.PERMISSION_FAILURE,
            "permission-failure",
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " rejects insufficient permissions",
            "Verify the API rejects authenticated callers without the represented role, permission, or scope.",
            403,
            shape,
            List.of("Permission metadata should cause under-privileged requests to be rejected"),
            tags(apiSpec, ScenarioCategory.PERMISSION_FAILURE),
            "P1",
            "HIGH",
            "API_CONTRACT",
            List.of()
        );
    }

    private ScenarioIntent businessRuleIntent(ApiSpec apiSpec, KnowledgeContextEntry entry, ContextBundle context) {
        var shape = requestShape(apiSpec);
        shape.put("contextRule", Map.of(
            "sourceType", "knowledge",
            "sourceId", entry.chunkId(),
            "sourceRef", entry.sourceRef(),
            "evidenceType", entry.evidenceType()
        ));
        return new ScenarioIntent(
            ScenarioCategory.BUSINESS_RULE,
            "business-rule-" + entry.chunkId(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " documented business rule",
            "Verify documented behavior from Knowledge context: " + entry.title() + ".",
            successStatus(apiSpec.getHttpMethod()),
            shape,
            List.of("Knowledge-derived business constraint should be reflected in the test plan", "Knowledge source: " + entry.sourceRef()),
            tags(apiSpec, ScenarioCategory.BUSINESS_RULE),
            "P2",
            entry.lowConfidence() ? "MEDIUM" : "HIGH",
            "KNOWLEDGE",
            citationsByType(context, "knowledge_chunk")
        );
    }

    private ScenarioIntent historicalFailureIntent(ApiSpec apiSpec, MemoryEvidence memory, ContextBundle context) {
        var shape = requestShape(apiSpec);
        shape.put("contextMemory", Map.of(
            "sourceType", memory.sourceType(),
            "sourceId", memory.sourceId(),
            "sourceRef", memory.sourceRef()
        ));
        return new ScenarioIntent(
            ScenarioCategory.HISTORICAL_FAILURE,
            "historical-failure-" + memory.sourceId(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " historical failure guard",
            "Verify the API covers known historical failure pattern: " + memory.summary() + ".",
            400,
            shape,
            List.of("Memory-derived historical failure should remain covered", "Memory source: " + memory.sourceRef()),
            tags(apiSpec, ScenarioCategory.HISTORICAL_FAILURE),
            "P1",
            "HIGH",
            "MEMORY",
            memoryCitations(context)
        );
    }

    private ScenarioIntent regressionRiskIntent(ApiSpec apiSpec, MemoryEvidence memory, ContextBundle context) {
        var shape = requestShape(apiSpec);
        shape.put("contextMemory", Map.of(
            "sourceType", memory.sourceType(),
            "sourceId", memory.sourceId(),
            "sourceRef", memory.sourceRef()
        ));
        return new ScenarioIntent(
            ScenarioCategory.REGRESSION_RISK,
            "regression-risk-" + memory.sourceId(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " regression risk",
            "Verify the API preserves behavior related to remembered risk: " + memory.summary() + ".",
            successStatus(apiSpec.getHttpMethod()),
            shape,
            List.of("Memory-derived regression risk should be reviewed before promotion", "Memory source: " + memory.sourceRef()),
            tags(apiSpec, ScenarioCategory.REGRESSION_RISK),
            "P2",
            "MEDIUM",
            "MEMORY",
            memoryCitations(context)
        );
    }

    private ScenarioIntent regressionRiskIntent(ApiSpec apiSpec, KnowledgeContextEntry entry, ContextBundle context) {
        var shape = requestShape(apiSpec);
        shape.put("contextIncident", Map.of(
            "sourceType", "knowledge",
            "sourceId", entry.chunkId(),
            "sourceRef", entry.sourceRef()
        ));
        return new ScenarioIntent(
            ScenarioCategory.REGRESSION_RISK,
            "regression-risk-" + entry.chunkId(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " incident regression risk",
            "Verify incident-derived risk from Knowledge context: " + entry.title() + ".",
            successStatus(apiSpec.getHttpMethod()),
            shape,
            List.of("Incident context should be reviewed as regression coverage", "Knowledge source: " + entry.sourceRef()),
            tags(apiSpec, ScenarioCategory.REGRESSION_RISK),
            "P2",
            "MEDIUM",
            "KNOWLEDGE",
            citationsByType(context, "knowledge_chunk")
        );
    }

    private Map<String, Object> requestShape(ApiSpec apiSpec) {
        var shape = new LinkedHashMap<String, Object>();
        shape.put("method", apiSpec.getHttpMethod().name());
        shape.put("path", apiSpec.getPath());
        shape.put("parameters", apiSpec.getParameters() == null ? Map.of() : new LinkedHashMap<>(apiSpec.getParameters()));
        shape.put("auth", apiSpec.getAuth() == null ? Map.of() : new LinkedHashMap<>(apiSpec.getAuth()));
        return shape;
    }

    private List<String> tags(ApiSpec apiSpec, ScenarioCategory category) {
        var tags = new ArrayList<String>();
        tags.add("api");
        tags.add("single");
        tags.add(category.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        tags.add(apiSpec.getModuleName());
        tags.add(apiSpec.getHttpMethod().name().toLowerCase(Locale.ROOT));
        if (category == ScenarioCategory.AUTHENTICATION_FAILURE || category == ScenarioCategory.PERMISSION_FAILURE) {
            tags.add("security");
        }
        if (category == ScenarioCategory.BUSINESS_RULE) {
            tags.add("knowledge");
        }
        if (category == ScenarioCategory.HISTORICAL_FAILURE || category == ScenarioCategory.REGRESSION_RISK) {
            tags.add("memory");
        }
        return List.copyOf(tags);
    }

    private String dedupKey(String taskId, String apiSpecId, ScenarioIntent intent) {
        return String.join(
            ":",
            "phase5",
            taskId,
            TestCaseGenerationMode.SINGLE.name().toLowerCase(Locale.ROOT),
            apiSpecId,
            intent.category().name().toLowerCase(Locale.ROOT),
            intent.intentKey(),
            String.valueOf(intent.expectedStatus())
        );
    }

    private List<ScenarioCategory> requestedCategories(TestCaseGenerationRequest request) {
        if (request.scenarioFilters() == null || request.scenarioFilters().isEmpty()) {
            return List.copyOf(EnumSet.allOf(ScenarioCategory.class));
        }
        return List.copyOf(request.scenarioFilters());
    }

    private List<String> contextTags(ApiSpec apiSpec, List<ScenarioCategory> categories) {
        return List.of(apiSpec.getModuleName());
    }

    private KnowledgeContextEntry firstKnowledgeEntry(ContextBundle context) {
        if (!context.knowledgeContext().businessRules().isEmpty()) {
            return context.knowledgeContext().businessRules().getFirst();
        }
        if (!context.knowledgeContext().apiNotes().isEmpty()) {
            return context.knowledgeContext().apiNotes().getFirst();
        }
        if (!context.knowledgeContext().testSpecs().isEmpty()) {
            return context.knowledgeContext().testSpecs().getFirst();
        }
        if (!context.knowledgeContext().errorCodeGuides().isEmpty()) {
            return context.knowledgeContext().errorCodeGuides().getFirst();
        }
        return null;
    }

    private MemoryEvidence firstFailureMemory(ContextBundle context) {
        for (var memory : context.taskMemory()) {
            if (memory.scopeType() == MemoryScopeType.FAILURE_PATTERN) {
                return MemoryEvidence.from(memory);
            }
        }
        for (var hit : context.longTermMemoryContext().hits()) {
            if (hit.scopeType() == MemoryScopeType.FAILURE_PATTERN) {
                return MemoryEvidence.from(hit);
            }
        }
        for (var memory : context.sessionContext()) {
            if (memory.scopeType() == MemoryScopeType.FAILURE_PATTERN) {
                return MemoryEvidence.from(memory);
            }
        }
        return null;
    }

    private MemoryEvidence firstRegressionMemory(ContextBundle context) {
        if (!context.taskMemory().isEmpty()) {
            return MemoryEvidence.from(context.taskMemory().getFirst());
        }
        if (!context.longTermMemoryContext().hits().isEmpty()) {
            return MemoryEvidence.from(context.longTermMemoryContext().hits().getFirst());
        }
        if (!context.sessionContext().isEmpty()) {
            return MemoryEvidence.from(context.sessionContext().getFirst());
        }
        return null;
    }

    private List<String> warnings(ContextBundle context) {
        var warnings = new ArrayList<String>();
        if (context.coverage().lowConfidence()) {
            warnings.add("LOW_CONFIDENCE_CONTEXT: generation used sparse or low-confidence context");
        }
        for (var conflict : context.conflicts()) {
            warnings.add("CONTEXT_CONFLICT: "
                + conflict.conflictType()
                + " "
                + conflict.conflictKey()
                + " preferred="
                + conflict.preferredSourceType());
        }
        return List.copyOf(warnings);
    }

    private List<ScenarioCoverage> orderedCoverage(
        List<ScenarioCategory> requestedCategories,
        Map<ScenarioCategory, ScenarioCoverage> coverageByCategory
    ) {
        return requestedCategories.stream()
            .map(coverageByCategory::get)
            .toList();
    }

    private CoverageStatus targetStatus(Map<ScenarioCategory, ScenarioCoverage> coverageByCategory) {
        if (coverageByCategory.values().stream().anyMatch(coverage -> coverage.status() == CoverageStatus.BLOCKED)) {
            return CoverageStatus.BLOCKED;
        }
        if (coverageByCategory.values().stream().anyMatch(coverage -> coverage.status() == CoverageStatus.GENERATED)) {
            return CoverageStatus.GENERATED;
        }
        if (coverageByCategory.values().stream().anyMatch(coverage -> coverage.status() == CoverageStatus.UPDATED)) {
            return CoverageStatus.UPDATED;
        }
        if (coverageByCategory.values().stream().allMatch(coverage -> coverage.status() == CoverageStatus.UNSUPPORTED)) {
            return CoverageStatus.UNSUPPORTED;
        }
        return CoverageStatus.SKIPPED;
    }

    private List<Map<String, Object>> citationsByType(ContextBundle context, String citationType) {
        return context.citations().stream()
            .filter(citation -> citationType.equals(citation.citationType()))
            .map(this::citationMap)
            .toList();
    }

    private List<Map<String, Object>> memoryCitations(ContextBundle context) {
        return context.citations().stream()
            .filter(citation -> !"knowledge_chunk".equals(citation.citationType()))
            .map(this::citationMap)
            .toList();
    }

    private Map<String, Object> citationMap(ContextCitation citation) {
        var map = new LinkedHashMap<String, Object>();
        map.put("citationType", citation.citationType());
        map.put("sourceId", citation.sourceId());
        map.put("sourceRef", citation.sourceRef());
        if (citation.confidence() != null) {
            map.put("confidence", citation.confidence());
        }
        if (citation.score() != null) {
            map.put("score", citation.score());
        }
        return map;
    }

    private List<ParameterRef> parameters(ApiSpec apiSpec) {
        if (apiSpec.getParameters() == null || apiSpec.getParameters().isEmpty()) {
            return List.of();
        }
        var refs = new ArrayList<ParameterRef>();
        for (var sectionEntry : apiSpec.getParameters().entrySet()) {
            var section = sectionEntry.getKey();
            var sectionValue = sectionEntry.getValue();
            if (sectionValue instanceof Map<?, ?> parameterMap) {
                for (var parameterEntry : parameterMap.entrySet()) {
                    if (parameterEntry.getValue() instanceof Map<?, ?> metadata) {
                        refs.add(new ParameterRef(section, String.valueOf(parameterEntry.getKey()), toStringObjectMap(metadata)));
                    }
                }
            }
        }
        return List.copyOf(refs);
    }

    private ParameterRef firstConstrainedParameter(ApiSpec apiSpec, List<ParameterRef> parameters) {
        for (var parameter : parameters) {
            var merged = new LinkedHashMap<>(parameter.metadata());
            if (apiSpec.getConstraints() != null && apiSpec.getConstraints().get(parameter.name()) instanceof Map<?, ?> constraint) {
                merged.putAll(toStringObjectMap(constraint));
            }
            if (hasBoundaryConstraint(merged)) {
                return new ParameterRef(parameter.section(), parameter.name(), merged);
            }
        }
        return null;
    }

    private boolean hasBoundaryConstraint(Map<String, Object> metadata) {
        return metadata.containsKey("min")
            || metadata.containsKey("max")
            || metadata.containsKey("minimum")
            || metadata.containsKey("maximum")
            || metadata.containsKey("minLength")
            || metadata.containsKey("maxLength");
    }

    private boolean hasAuthMetadata(ApiSpec apiSpec) {
        return apiSpec.getAuth() != null && !apiSpec.getAuth().isEmpty() && !"none".equalsIgnoreCase(String.valueOf(apiSpec.getAuth().get("type")));
    }

    private boolean hasPermissionHints(ApiSpec apiSpec) {
        if (apiSpec.getAuth() == null || apiSpec.getAuth().isEmpty()) {
            return false;
        }
        return apiSpec.getAuth().containsKey("roles")
            || apiSpec.getAuth().containsKey("role")
            || apiSpec.getAuth().containsKey("requiredRole")
            || apiSpec.getAuth().containsKey("permissions")
            || apiSpec.getAuth().containsKey("scopes")
            || apiSpec.getAuth().containsKey("scope");
    }

    private Object invalidValueFor(ParameterRef parameter) {
        var type = String.valueOf(parameter.metadata().getOrDefault("type", "string")).toLowerCase(Locale.ROOT);
        return switch (type) {
            case "integer", "number", "long", "float", "double" -> "not-a-number";
            case "boolean" -> "not-a-boolean";
            default -> "";
        };
    }

    private Map<String, Object> constraintSnapshot(ParameterRef parameter) {
        var snapshot = new LinkedHashMap<String, Object>();
        for (var key : List.of("min", "max", "minimum", "maximum", "minLength", "maxLength", "type")) {
            if (parameter.metadata().containsKey(key)) {
                snapshot.put(key, parameter.metadata().get(key));
            }
        }
        return snapshot;
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> source) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private String expectedResult(ScenarioIntent intent) {
        if (intent.category() == ScenarioCategory.HAPPY_PATH) {
            return "The API responds with HTTP " + intent.expectedStatus() + " and returns a successful response body.";
        }
        return "The API responds with HTTP " + intent.expectedStatus() + " and exposes a deterministic validation or authorization failure.";
    }

    private String scenarioName(ScenarioCategory category) {
        return category.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private int successStatus(HttpMethod method) {
        if (method == HttpMethod.POST) {
            return 201;
        }
        return 200;
    }

    private String rawQueryFor(ApiSpec apiSpec) {
        return "Generate SINGLE happy path test case drafts for "
            + apiSpec.getHttpMethod()
            + " "
            + apiSpec.getPath();
    }

    private int tokenBudget(TestCaseGenerationRequest request) {
        return request.tokenBudget() == null ? DEFAULT_TOKEN_BUDGET : request.tokenBudget();
    }

    private record ParameterRef(String section, String name, Map<String, Object> metadata) {
        boolean required() {
            return Boolean.TRUE.equals(metadata.get("required"));
        }
    }

    private record ScenarioPlan(
        List<ScenarioIntent> intents,
        Map<ScenarioCategory, String> skippedCategories,
        Map<ScenarioCategory, String> unsupportedCategories
    ) {
    }

    private record MemoryEvidence(String sourceType, String sourceId, String sourceRef, String summary) {
        static MemoryEvidence from(TaskMemoryView memory) {
            return new MemoryEvidence("task_memory", memory.memoryId(), memory.sourceRef(), memory.summary());
        }

        static MemoryEvidence from(SessionMemoryView memory) {
            return new MemoryEvidence("session_memory", memory.memoryId(), memory.sourceRef(), memory.summary());
        }

        static MemoryEvidence from(LongTermMemoryRetrievalHit hit) {
            return new MemoryEvidence("long_term_memory", hit.memoryId(), hit.sourceRef(), hit.summary());
        }
    }

    private enum DraftPersistenceAction {
        CREATED,
        UPDATED,
        DUPLICATE_SUPPRESSED,
        PROTECTED_SKIPPED
    }

    private record DraftPersistenceResult(DraftPersistenceAction action, String draftId) {
    }
}
