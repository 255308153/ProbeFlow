package com.probeflow.testagent.memory;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalHit;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final MemoryUsageRecordingService memoryUsageRecording;

    public UnifiedContextBuilder(
        TaskRepository tasks,
        ApiSpecRepository apiSpecs,
        SessionMemoryService sessionMemoryService,
        TaskMemoryService taskMemoryService,
        KnowledgeRetrievalApplicationService knowledgeRetrieval,
        LongTermMemoryRetrievalService longTermMemoryRetrieval,
        MemoryUsageRecordingService memoryUsageRecording
    ) {
        this.tasks = tasks;
        this.apiSpecs = apiSpecs;
        this.sessionMemoryService = sessionMemoryService;
        this.taskMemoryService = taskMemoryService;
        this.knowledgeRetrieval = knowledgeRetrieval;
        this.longTermMemoryRetrieval = longTermMemoryRetrieval;
        this.memoryUsageRecording = memoryUsageRecording;
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
        var pruned = pruneToBudget(normalized.tokenBudget(), apiContext, sessionContext, taskMemory, knowledge, longTermMemory);
        var constraints = buildConstraints(apiSpec, normalized.stageProfile());
        var citations = buildCitations(
            pruned.sessionContext(),
            pruned.taskMemory(),
            pruned.knowledge(),
            pruned.longTermMemory()
        );
        memoryUsageRecording.recordLongTermMemoryUsage(normalized, pruned.longTermMemory(), citations);
        var conflicts = detectConflicts(knowledge, taskMemory, longTermMemory, normalized);
        var budget = buildBudget(
            normalized.tokenBudget(),
            apiContext,
            pruned.sessionContext(),
            pruned.taskMemory(),
            pruned.knowledge(),
            pruned.longTermMemory(),
            pruned.originalEstimatedTokens(),
            pruned.pruned()
        );
        var coverage = new ContextCoverage(
            apiContext != null,
            taskState != null,
            !pruned.sessionContext().isEmpty(),
            !pruned.taskMemory().isEmpty(),
            !pruned.knowledge().knowledgeContext().isEmpty(),
            !pruned.longTermMemory().isEmpty(),
            pruned.knowledge().coverage(),
            pruned.knowledge().lowConfidence() || pruned.longTermMemory().hits().stream().anyMatch(LongTermMemoryRetrievalHit::lowConfidence)
        );

        return new ContextBundle(
            apiContext,
            taskState,
            pruned.sessionContext(),
            pruned.taskMemory(),
            pruned.knowledge().knowledgeContext(),
            pruned.longTermMemory(),
            constraints,
            citations,
            conflicts,
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
        var knowledgeHitsByChunkId = new LinkedHashMap<String, KnowledgeRetrievalHit>();
        for (var hit : knowledge.hits()) {
            knowledgeHitsByChunkId.putIfAbsent(hit.chunkId(), hit);
        }
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
                entry.score(),
                knowledgeEvidence(entry, knowledgeHitsByChunkId.get(entry.chunkId()))
            ));
        }
        for (var hit : longTermMemory.hits()) {
            citations.add(new ContextCitation(
                "long_term_memory",
                hit.memoryId(),
                hit.sourceRef(),
                asDouble(hit.confidence()),
                hit.score(),
                longTermMemoryEvidence(hit)
            ));
        }
        return List.copyOf(citations);
    }

    private Map<String, Object> knowledgeEvidence(KnowledgeContextEntry entry, KnowledgeRetrievalHit hit) {
        var evidence = new LinkedHashMap<String, Object>();
        putIfPresent(evidence, "documentId", entry.documentId());
        putIfPresent(evidence, "documentRevisionId", entry.documentRevisionId());
        putIfPresent(evidence, "evidenceType", entry.evidenceType());
        evidence.put("lowConfidence", entry.lowConfidence());
        var metadata = copyMetadata(entry.metadata());
        if (!metadata.isEmpty()) {
            evidence.put("metadata", metadata);
            copySemanticEvidence(evidence, metadata);
        }
        var matchReasons = entry.matchReasons() == null ? List.<String>of() : List.copyOf(entry.matchReasons());
        if (!matchReasons.isEmpty()) {
            evidence.put("matchReasons", matchReasons);
        }
        if (hit != null && hit.componentScores() != null && !hit.componentScores().isEmpty()) {
            evidence.put("componentScores", new LinkedHashMap<>(hit.componentScores()));
        }
        return evidence;
    }

    private Map<String, Object> longTermMemoryEvidence(LongTermMemoryRetrievalHit hit) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("memoryScopeType", hit.scopeType().name());
        evidence.put("memorySourceType", hit.sourceType().name());
        putIfPresent(evidence, "confidence", hit.confidence());
        putIfPresent(evidence, "importance", hit.importance());
        putIfPresent(evidence, "successContribution", hit.successContribution());
        evidence.put("lowConfidence", hit.lowConfidence());
        var metadata = copyMetadata(hit.metadata());
        if (!metadata.isEmpty()) {
            evidence.put("metadata", metadata);
            copySemanticEvidence(evidence, metadata);
        }
        if (hit.componentScores() != null && !hit.componentScores().isEmpty()) {
            evidence.put("componentScores", new LinkedHashMap<>(hit.componentScores()));
        }
        var matchReasons = hit.matchReasons() == null ? List.<String>of() : List.copyOf(hit.matchReasons());
        if (!matchReasons.isEmpty()) {
            evidence.put("matchReasons", matchReasons);
        }
        return evidence;
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        return metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    private void copySemanticEvidence(Map<String, Object> evidence, Map<String, Object> metadata) {
        copyMetadataValue(evidence, metadata, EmbeddingProfileMetadata.EMBEDDING_PROFILE);
        copyMetadataValue(evidence, metadata, EmbeddingProfileMetadata.CURRENT_EMBEDDING_PROFILE);
        copyMetadataValue(evidence, metadata, EmbeddingProfileMetadata.EMBEDDING_PROFILE_MISMATCH);
        copyMetadataValue(evidence, metadata, EmbeddingProfileMetadata.REINDEX_REQUIRED);
        copyMetadataValue(evidence, metadata, EmbeddingProfileMetadata.REINDEX_REASON);
        copyMetadataValue(evidence, metadata, "retrievalChannel");
        copyMetadataValue(evidence, metadata, "vectorDistance");
        copyMetadataValue(evidence, metadata, "candidateRank");
        copyMetadataValue(evidence, metadata, "lexicalScore");
        copyMetadataValue(evidence, metadata, "metadataScore");
        copyMetadataValue(evidence, metadata, "parentChunkId");
        copyMetadataValue(evidence, metadata, "lowConfidenceReason");
    }

    private void copyMetadataValue(Map<String, Object> evidence, Map<String, Object> metadata, String key) {
        if (metadata.containsKey(key)) {
            evidence.put(key, metadata.get(key));
        }
    }

    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private ContextBudget buildBudget(
        int requestedTokenBudget,
        ApiContextSnapshot apiContext,
        List<SessionMemoryView> sessionContext,
        List<TaskMemoryView> taskMemory,
        KnowledgeRetrievalResult knowledge,
        LongTermMemoryRetrievalResult longTermMemory,
        int originalEstimatedTokens,
        boolean pruned
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
            apiTokens + sessionTokens + taskMemoryTokens + knowledgeTokens + longTermMemoryTokens,
            originalEstimatedTokens,
            pruned
        );
    }

    private PrunedContext pruneToBudget(
        int tokenBudget,
        ApiContextSnapshot apiContext,
        List<SessionMemoryView> sessionContext,
        List<TaskMemoryView> taskMemory,
        KnowledgeRetrievalResult knowledge,
        LongTermMemoryRetrievalResult longTermMemory
    ) {
        var apiTokens = estimateTokens(joinNonBlank(apiContext.summary(), apiContext.description(), apiContext.path(), apiContext.operationId()));
        var taskMemoryTokens = taskMemory.stream()
            .mapToInt(memory -> estimateTokens(joinNonBlank(memory.summary(), memory.content())))
            .sum();
        var baseTokens = apiTokens + taskMemoryTokens;
        var originalEstimatedTokens = baseTokens
            + sessionContext.stream().mapToInt(memory -> estimateTokens(joinNonBlank(memory.summary(), memory.content()))).sum()
            + knowledge.totalTokens()
            + longTermMemory.totalTokens();
        var remaining = Math.max(0, tokenBudget - baseTokens);

        var keptKnowledgeHits = new ArrayList<KnowledgeRetrievalHit>();
        for (var hit : sortKnowledgeForBudget(knowledge.hits())) {
            if (hit.tokenCount() <= remaining) {
                keptKnowledgeHits.add(hit);
                remaining -= hit.tokenCount();
            }
        }
        var prunedKnowledge = rebuildKnowledgeResult(knowledge, keptKnowledgeHits);

        var keptSession = new ArrayList<SessionMemoryView>();
        for (var memory : sessionContext) {
            var tokens = estimateTokens(joinNonBlank(memory.summary(), memory.content()));
            if (tokens <= remaining) {
                keptSession.add(memory);
                remaining -= tokens;
            }
        }

        var keptLongTermHits = new ArrayList<LongTermMemoryRetrievalHit>();
        for (var hit : sortLongTermForBudget(longTermMemory.hits())) {
            if (hit.tokenCount() <= remaining) {
                keptLongTermHits.add(hit);
                remaining -= hit.tokenCount();
            }
        }
        keptLongTermHits.sort(Comparator
            .comparingDouble(LongTermMemoryRetrievalHit::score).reversed()
            .thenComparing(LongTermMemoryRetrievalHit::summary)
            .thenComparing(LongTermMemoryRetrievalHit::memoryId));
        var prunedLongTerm = new LongTermMemoryRetrievalResult(
            List.copyOf(keptLongTermHits),
            longTermMemory.totalCandidates(),
            keptLongTermHits.stream().mapToInt(LongTermMemoryRetrievalHit::tokenCount).sum()
        );

        return new PrunedContext(
            List.copyOf(keptSession),
            taskMemory,
            prunedKnowledge,
            prunedLongTerm,
            originalEstimatedTokens,
            originalEstimatedTokens > tokenBudget
        );
    }

    private List<KnowledgeRetrievalHit> sortKnowledgeForBudget(List<KnowledgeRetrievalHit> hits) {
        return hits.stream()
            .sorted(Comparator
                .comparingInt((KnowledgeRetrievalHit hit) -> authorityWeight(hit.authority())).reversed()
                .thenComparingDouble(KnowledgeRetrievalHit::score).reversed()
                .thenComparingInt(KnowledgeRetrievalHit::tokenCount)
                .thenComparing(KnowledgeRetrievalHit::chunkId))
            .toList();
    }

    private int authorityWeight(DocumentAuthority authority) {
        if (authority == null) {
            return 0;
        }
        return switch (authority) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    private List<LongTermMemoryRetrievalHit> sortLongTermForBudget(List<LongTermMemoryRetrievalHit> hits) {
        return hits.stream()
            .sorted(Comparator
                .comparing((LongTermMemoryRetrievalHit hit) -> !isRiskHint(hit))
                .thenComparingDouble(LongTermMemoryRetrievalHit::score).reversed()
                .thenComparingDouble(hit -> hit.successContribution() == null ? 0.0d : hit.successContribution())
                .reversed()
                .thenComparingInt(LongTermMemoryRetrievalHit::tokenCount)
                .thenComparing(LongTermMemoryRetrievalHit::memoryId))
            .toList();
    }

    private boolean isRiskHint(LongTermMemoryRetrievalHit hit) {
        return hit.scopeType() == MemoryScopeType.FAILURE_PATTERN
            && hit.confidence() != null && hit.confidence() >= 0.75f
            && hit.successContribution() != null && hit.successContribution() >= 0.45f;
    }

    private KnowledgeRetrievalResult rebuildKnowledgeResult(KnowledgeRetrievalResult original, List<KnowledgeRetrievalHit> keptHits) {
        var keptIds = keptHits.stream().map(KnowledgeRetrievalHit::chunkId).collect(java.util.stream.Collectors.toSet());
        var context = new KnowledgeContext(
            filterEntries(original.knowledgeContext().businessRules(), keptIds),
            filterEntries(original.knowledgeContext().apiNotes(), keptIds),
            filterEntries(original.knowledgeContext().testSpecs(), keptIds),
            filterEntries(original.knowledgeContext().errorCodeGuides(), keptIds),
            filterEntries(original.knowledgeContext().environmentNotes(), keptIds),
            filterEntries(original.knowledgeContext().incidentHints(), keptIds),
            filterEntries(original.knowledgeContext().citedChunks(), keptIds),
            original.knowledgeContext().lowConfidence(),
            original.knowledgeContext().lowCoverage()
        );
        return new KnowledgeRetrievalResult(
            original.rawQuery(),
            List.copyOf(keptHits),
            context,
            keptHits.isEmpty() ? 0.0d : original.coverage(),
            original.totalCandidates(),
            keptHits.stream().mapToInt(KnowledgeRetrievalHit::tokenCount).sum(),
            keptHits.isEmpty() || original.lowConfidence()
        );
    }

    private List<KnowledgeContextEntry> filterEntries(List<KnowledgeContextEntry> entries, Set<String> keptIds) {
        return entries.stream()
            .filter(entry -> keptIds.contains(entry.chunkId()))
            .toList();
    }

    private List<ContextConflict> detectConflicts(
        KnowledgeRetrievalResult knowledge,
        List<TaskMemoryView> taskMemory,
        LongTermMemoryRetrievalResult longTermMemory,
        UnifiedContextQuery query
    ) {
        var conflicts = new ArrayList<ContextConflict>();
        for (var hit : knowledge.hits()) {
            for (var memory : taskMemory) {
                var conflictType = detectConflictType(hit.chunkContent(), memory.content());
                if (conflictType != null && sharesScope(hit, memory, query)) {
                    conflicts.add(new ContextConflict(
                        conflictType,
                        conflictKey(hit.chunkContent(), memory.content(), query),
                        new ContextConflictSide(
                            "knowledge_chunk",
                            hit.chunkId(),
                            hit.sourceRef(),
                            null,
                            hit.score(),
                            hit.chunkTitle(),
                            hit.chunkContent()
                        ),
                        new ContextConflictSide(
                            "task_memory",
                            memory.memoryId(),
                            memory.sourceRef(),
                            asDouble(memory.confidence()),
                            null,
                            memory.summary(),
                            memory.content()
                        ),
                        preferredSourceType(hit.authority(), memory.confidence(), true)
                    ));
                }
            }
            for (var memory : longTermMemory.hits()) {
                var conflictType = detectConflictType(hit.chunkContent(), memory.content());
                if (conflictType != null && sharesScope(hit, memory, query)) {
                    conflicts.add(new ContextConflict(
                        conflictType,
                        conflictKey(hit.chunkContent(), memory.content(), query),
                        new ContextConflictSide(
                            "knowledge_chunk",
                            hit.chunkId(),
                            hit.sourceRef(),
                            null,
                            hit.score(),
                            hit.chunkTitle(),
                            hit.chunkContent()
                        ),
                        new ContextConflictSide(
                            "long_term_memory",
                            memory.memoryId(),
                            memory.sourceRef(),
                            asDouble(memory.confidence()),
                            memory.score(),
                            memory.summary(),
                            memory.content()
                        ),
                        preferredSourceType(hit.authority(), memory.confidence(), false)
                    ));
                }
            }
        }
        return conflicts.stream()
            .distinct()
            .toList();
    }

    private boolean sharesScope(KnowledgeRetrievalHit hit, TaskMemoryView memory, UnifiedContextQuery query) {
        return matchesNullable(hit.moduleName(), query.moduleName())
            || overlap(hit.tags(), memory.tags()) > 0
            || metadataMatches(memory.metadata(), "errorCode", query.errorCode());
    }

    private boolean sharesScope(KnowledgeRetrievalHit hit, LongTermMemoryRetrievalHit memory, UnifiedContextQuery query) {
        return matchesNullable(hit.moduleName(), query.moduleName())
            || overlap(hit.tags(), memory.tags()) > 0
            || metadataMatches(memory.metadata(), "errorCode", query.errorCode())
            || metadataMatches(memory.metadata(), "apiPath", query.apiPath());
    }

    private int overlap(List<String> left, List<String> right) {
        var rightSet = new LinkedHashSet<>(right);
        var count = 0;
        for (var candidate : left) {
            if (rightSet.contains(candidate)) {
                count++;
            }
        }
        return count;
    }

    private boolean metadataMatches(Map<String, Object> metadata, String key, String expected) {
        if (!StringUtils.hasText(expected)) {
            return false;
        }
        var value = metadata.get(key);
        return value != null && expected.equalsIgnoreCase(String.valueOf(value).trim());
    }

    private boolean matchesNullable(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right) && left.equalsIgnoreCase(right.trim());
    }

    private String detectConflictType(String knowledgeText, String memoryText) {
        var left = normalizeText(knowledgeText);
        var right = normalizeText(memoryText);
        if (hasRequirementAffirmation(left) && hasRequirementNegation(right)) {
            return "requirement-conflict";
        }
        if (hasRequirementNegation(left) && hasRequirementAffirmation(right)) {
            return "requirement-conflict";
        }
        if ((left.contains("enabled") && right.contains("disabled")) || (left.contains("disabled") && right.contains("enabled"))) {
            return "status-conflict";
        }
        if ((left.contains("before") && right.contains("after")) || (left.contains("after") && right.contains("before"))) {
            return "ordering-conflict";
        }
        if ((left.contains("valid") && right.contains("invalid")) || (left.contains("invalid") && right.contains("valid"))) {
            return "validation-conflict";
        }
        return null;
    }

    private boolean hasRequirementAffirmation(String text) {
        return text.contains("required")
            || text.contains("requires")
            || text.contains("must")
            || text.contains("should");
    }

    private boolean hasRequirementNegation(String text) {
        return text.contains("not required")
            || text.contains("does not require")
            || text.contains("not needed")
            || text.contains("optional");
    }

    private String conflictKey(String knowledgeText, String memoryText, UnifiedContextQuery query) {
        if (StringUtils.hasText(query.errorCode())) {
            return query.errorCode();
        }
        if (StringUtils.hasText(query.apiPath())) {
            return query.apiPath();
        }
        var left = normalizedTokens(knowledgeText);
        for (var token : normalizedTokens(memoryText)) {
            if (left.contains(token)) {
                return token;
            }
        }
        return "general";
    }

    private String preferredSourceType(DocumentAuthority authority, Float memoryConfidence, boolean taskMemory) {
        if (!taskMemory && authority == DocumentAuthority.HIGH && (memoryConfidence == null || memoryConfidence < 0.8f)) {
            return "knowledge_chunk";
        }
        if (taskMemory) {
            return "task_memory";
        }
        return authority == DocumentAuthority.HIGH ? "knowledge_chunk" : "long_term_memory";
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
            query.tokenBudget() == null ? DEFAULT_TOKEN_BUDGET : query.tokenBudget(),
            query.consumer() == null ? inferConsumer(query.stageProfile()) : query.consumer(),
            normalizeNullable(query.usageSourceRef())
        );
    }

    private MemoryUsageConsumer inferConsumer(String stageProfile) {
        return switch (normalizeStage(stageProfile)) {
            case "case_generation" -> MemoryUsageConsumer.TEST_CASE_GENERATION;
            case "failure_analysis" -> MemoryUsageConsumer.FAILURE_ANALYSIS;
            case "execution_preparation" -> MemoryUsageConsumer.PLANNER;
            case "report_generation" -> MemoryUsageConsumer.REPORT_GENERATION;
            default -> MemoryUsageConsumer.CONTEXT_BUILDER;
        };
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

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Set<String> normalizedTokens(String value) {
        var tokens = new LinkedHashSet<String>();
        for (var token : normalizeText(value).split("[^a-z0-9_]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
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

    private record PrunedContext(
        List<SessionMemoryView> sessionContext,
        List<TaskMemoryView> taskMemory,
        KnowledgeRetrievalResult knowledge,
        LongTermMemoryRetrievalResult longTermMemory,
        int originalEstimatedTokens,
        boolean pruned
    ) {
    }
}
