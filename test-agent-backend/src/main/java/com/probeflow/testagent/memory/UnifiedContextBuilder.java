package com.probeflow.testagent.memory;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UnifiedContextBuilder {

    private static final int DEFAULT_TOKEN_BUDGET = 1200;

    private final TaskRepository tasks;
    private final ApiSpecRepository apiSpecs;
    private final SessionMemoryService sessionMemoryService;
    private final TaskMemoryService taskMemoryService;
    private final KnowledgeRetrievalApplicationService knowledgeRetrieval;
    private final LongTermMemoryRetrievalService longTermMemoryRetrieval;

    public UnifiedContextBuilder(
        TaskRepository tasks,
        ApiSpecRepository apiSpecs,
        SessionMemoryService sessionMemoryService,
        TaskMemoryService taskMemoryService,
        KnowledgeRetrievalApplicationService knowledgeRetrieval,
        LongTermMemoryRetrievalService longTermMemoryRetrieval
    ) {
        this.tasks = tasks;
        this.apiSpecs = apiSpecs;
        this.sessionMemoryService = sessionMemoryService;
        this.taskMemoryService = taskMemoryService;
        this.knowledgeRetrieval = knowledgeRetrieval;
        this.longTermMemoryRetrieval = longTermMemoryRetrieval;
    }

    @Transactional
    public ContextBundle build(UnifiedContextQuery query) {
        validate(query);
        var normalized = normalize(query);

        var apiSpec = resolveApiSpec(normalized);
        var task = resolveTask(normalized.taskId());
        var apiContext = toApiContext(apiSpec);
        var taskState = toTaskState(task);
        var sessionContext = loadSessionContext(normalized.sessionId());
        var taskMemory = loadTaskMemory(normalized.taskId(), normalized.stageProfile());
        var knowledge = loadKnowledge(apiSpec, normalized);
        var longTermMemory = loadLongTermMemory(apiSpec, normalized);
        var constraints = buildConstraints(apiSpec, normalized.stageProfile());
        var citations = buildCitations(sessionContext, taskMemory, knowledge, longTermMemory);
        var budget = buildBudget(normalized.tokenBudget(), apiContext, sessionContext, taskMemory, knowledge, longTermMemory);
        var coverage = new ContextCoverage(
            apiContext != null,
            taskState != null,
            !sessionContext.isEmpty(),
            !taskMemory.isEmpty(),
            !knowledge.knowledgeContext().isEmpty(),
            !longTermMemory.isEmpty(),
            knowledge.coverage(),
            knowledge.lowConfidence() || longTermMemory.hits().stream().anyMatch(LongTermMemoryRetrievalHit::lowConfidence)
        );

        return new ContextBundle(
            apiContext,
            taskState,
            sessionContext,
            taskMemory,
            knowledge.knowledgeContext(),
            longTermMemory,
            constraints,
            citations,
            coverage,
            budget
        );
    }

    private ApiSpec resolveApiSpec(UnifiedContextQuery query) {
        if (query.apiSpec() != null) {
            return query.apiSpec();
        }
        return apiSpecs.findById(query.apiSpecId())
            .orElseThrow(() -> new IllegalArgumentException("ApiSpec not found: " + query.apiSpecId()));
    }

    private Task resolveTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        return tasks.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private ApiContextSnapshot toApiContext(ApiSpec apiSpec) {
        return new ApiContextSnapshot(
            apiSpec.getApiSpecId(),
            apiSpec.getSystemName(),
            apiSpec.getModuleName(),
            apiSpec.getHttpMethod(),
            apiSpec.getPath(),
            apiSpec.getSummary(),
            apiSpec.getDescription(),
            apiSpec.getOperationId(),
            new LinkedHashMap<>(apiSpec.getParameters()),
            new LinkedHashMap<>(apiSpec.getConstraints()),
            new LinkedHashMap<>(apiSpec.getAuth()),
            apiSpec.getSourceType(),
            apiSpec.getSourceRef(),
            apiSpec.isKnowledgeContextReady()
        );
    }

    private TaskStateSnapshot toTaskState(Task task) {
        if (task == null) {
            return null;
        }
        return new TaskStateSnapshot(
            task.getTaskId(),
            task.getTaskType(),
            task.getTaskName(),
            task.getStatus(),
            task.getSourceType(),
            task.getSourceRef(),
            List.copyOf(task.getTargetApiSpecIds()),
            task.getPromotionMode(),
            task.getMemoryRefinementStatus(),
            task.getPriority(),
            task.getCreator(),
            new LinkedHashMap<>(task.getMetadata()),
            task.getCreatedAt(),
            task.getUpdatedAt()
        );
    }

    private List<SessionMemoryView> loadSessionContext(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        return sessionMemoryService.readActiveSessionMemories(sessionId);
    }

    private List<TaskMemoryView> loadTaskMemory(String taskId, String stageProfile) {
        if (!StringUtils.hasText(taskId)) {
            return List.of();
        }
        var prioritized = stageProfile == null
            ? List.<TaskMemoryView>of()
            : taskMemoryService.readActiveTaskMemories(taskId, stageProfile);
        var all = taskMemoryService.readActiveTaskMemories(taskId);
        var ordered = new ArrayList<TaskMemoryView>();
        var seen = new LinkedHashSet<String>();
        for (var memory : prioritized) {
            if (seen.add(memory.memoryId())) {
                ordered.add(memory);
            }
        }
        for (var memory : all) {
            if (seen.add(memory.memoryId())) {
                ordered.add(memory);
            }
        }
        return List.copyOf(ordered);
    }

    private KnowledgeRetrievalResult loadKnowledge(ApiSpec apiSpec, UnifiedContextQuery query) {
        var knowledgeQuery = new KnowledgeQuery(
            query.rawQuery(),
            firstNonBlank(query.systemName(), apiSpec.getSystemName()),
            firstNonBlank(query.moduleName(), apiSpec.getModuleName()),
            firstNonBlank(query.apiPath(), apiSpec.getPath()),
            apiSpec.getHttpMethod() == null ? null : apiSpec.getHttpMethod().name(),
            null,
            null,
            query.stageProfile(),
            query.tags(),
            6,
            query.tokenBudget()
        );
        if (StringUtils.hasText(query.apiSpecId())) {
            return knowledgeRetrieval.retrieveForApiSpec(query.apiSpecId(), knowledgeQuery);
        }
        return knowledgeRetrieval.retrieve(knowledgeQuery);
    }

    private LongTermMemoryRetrievalResult loadLongTermMemory(ApiSpec apiSpec, UnifiedContextQuery query) {
        return longTermMemoryRetrieval.retrieve(new LongTermMemoryQuery(
            query.stageProfile(),
            query.rawQuery(),
            firstNonBlank(query.systemName(), apiSpec.getSystemName()),
            firstNonBlank(query.moduleName(), apiSpec.getModuleName()),
            firstNonBlank(query.apiPath(), apiSpec.getPath()),
            query.errorCode(),
            query.tags(),
            List.of(),
            6,
            query.tokenBudget()
        ));
    }

    private Map<String, Object> buildConstraints(ApiSpec apiSpec, String stageProfile) {
        var constraints = new LinkedHashMap<String, Object>();
        constraints.put("apiConstraints", new LinkedHashMap<>(apiSpec.getConstraints()));
        constraints.put("apiAuth", new LinkedHashMap<>(apiSpec.getAuth()));
        constraints.put("stageProfile", stageProfile);
        return constraints;
    }

    private List<ContextCitation> buildCitations(
        List<SessionMemoryView> sessionContext,
        List<TaskMemoryView> taskMemory,
        KnowledgeRetrievalResult knowledge,
        LongTermMemoryRetrievalResult longTermMemory
    ) {
        var citations = new ArrayList<ContextCitation>();
        for (var memory : sessionContext) {
            citations.add(new ContextCitation(
                "session_memory",
                memory.memoryId(),
                memory.sourceRef(),
                asDouble(memory.confidence()),
                null
            ));
        }
        for (var memory : taskMemory) {
            citations.add(new ContextCitation(
                "task_memory",
                memory.memoryId(),
                memory.sourceRef(),
                asDouble(memory.confidence()),
                null
            ));
        }
        for (var entry : knowledge.knowledgeContext().citedChunks()) {
            citations.add(new ContextCitation(
                "knowledge_chunk",
                entry.chunkId(),
                entry.sourceRef(),
                null,
                entry.score()
            ));
        }
        for (var hit : longTermMemory.hits()) {
            citations.add(new ContextCitation(
                "long_term_memory",
                hit.memoryId(),
                hit.sourceRef(),
                asDouble(hit.confidence()),
                hit.score()
            ));
        }
        return List.copyOf(citations);
    }

    private ContextBudget buildBudget(
        int requestedTokenBudget,
        ApiContextSnapshot apiContext,
        List<SessionMemoryView> sessionContext,
        List<TaskMemoryView> taskMemory,
        KnowledgeRetrievalResult knowledge,
        LongTermMemoryRetrievalResult longTermMemory
    ) {
        var apiTokens = estimateTokens(
            joinNonBlank(apiContext.summary(), apiContext.description(), apiContext.path(), apiContext.operationId())
        );
        var sessionTokens = sessionContext.stream()
            .mapToInt(memory -> estimateTokens(joinNonBlank(memory.summary(), memory.content())))
            .sum();
        var taskMemoryTokens = taskMemory.stream()
            .mapToInt(memory -> estimateTokens(joinNonBlank(memory.summary(), memory.content())))
            .sum();
        var knowledgeTokens = knowledge.totalTokens();
        var longTermMemoryTokens = longTermMemory.totalTokens();
        return new ContextBudget(
            requestedTokenBudget,
            apiTokens,
            sessionTokens,
            taskMemoryTokens,
            knowledgeTokens,
            longTermMemoryTokens,
            apiTokens + sessionTokens + taskMemoryTokens + knowledgeTokens + longTermMemoryTokens
        );
    }

    private void validate(UnifiedContextQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (!StringUtils.hasText(query.rawQuery())) {
            throw new IllegalArgumentException("rawQuery must not be blank");
        }
        if (query.apiSpec() == null && !StringUtils.hasText(query.apiSpecId())) {
            throw new IllegalArgumentException("apiSpec or apiSpecId must be provided");
        }
        if (query.tokenBudget() != null && query.tokenBudget() <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
    }

    private UnifiedContextQuery normalize(UnifiedContextQuery query) {
        return new UnifiedContextQuery(
            normalizeNullable(query.taskId()),
            normalizeNullable(query.sessionId()),
            normalizeNullable(query.apiSpecId()),
            query.apiSpec(),
            normalizeStage(query.stageProfile()),
            query.rawQuery().trim(),
            normalizeNullable(query.systemName()),
            normalizeNullable(query.moduleName()),
            normalizeNullable(query.apiPath()),
            normalizeNullable(query.errorCode()),
            normalizeTags(query.tags()),
            query.tokenBudget() == null ? DEFAULT_TOKEN_BUDGET : query.tokenBudget()
        );
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
            .filter(StringUtils::hasText)
            .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList();
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeStage(String stageProfile) {
        return StringUtils.hasText(stageProfile) ? stageProfile.trim().toLowerCase(Locale.ROOT) : "general";
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : normalizeNullable(fallback);
    }

    private Double asDouble(Float value) {
        return value == null ? null : value.doubleValue();
    }

    private int estimateTokens(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(value.trim().length() / 24.0d));
    }

    private String joinNonBlank(String... parts) {
        var joined = new ArrayList<String>();
        for (var part : parts) {
            if (StringUtils.hasText(part)) {
                joined.add(part.trim());
            }
        }
        return String.join(" ", joined);
    }
}
