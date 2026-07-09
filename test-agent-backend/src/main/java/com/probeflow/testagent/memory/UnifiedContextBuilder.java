package com.probeflow.testagent.memory;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRouteEvidence;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalHit;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.retrieval.RetrievalRouteDiagnostic;
import com.probeflow.testagent.retrieval.RetrievalRouteEvidence;
import com.probeflow.testagent.rerank.KnowledgeExpansionContext;
import com.probeflow.testagent.rerank.KnowledgeExpansionPruning;
import com.probeflow.testagent.rerank.KnowledgeExpansionSource;
import com.probeflow.testagent.rerank.MemoryConflictAuditEvidence;
import com.probeflow.testagent.rerank.MemoryEvidenceExpansionItem;
import com.probeflow.testagent.rerank.MemoryEvidenceRole;
import com.probeflow.testagent.rerank.MemoryGraphRelationExpansion;
import com.probeflow.testagent.rerank.RerankCandidate;
import com.probeflow.testagent.rerank.RerankCorpusType;
import com.probeflow.testagent.rerank.RerankFeatureDiagnostic;
import com.probeflow.testagent.rerank.RerankFeatureLedger;
import com.probeflow.testagent.rerank.RerankOutputItem;
import com.probeflow.testagent.rerank.RerankRouteEvidence;
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
        var requestedPostRerankContext = normalized.postRerankContext();
        var postRerankContext = usePostRerankExpandedContext(normalized) ? requestedPostRerankContext : null;
        var knowledge = postRerankContext == null
            ? loadKnowledge(apiSpec, normalized)
            : buildPostRerankKnowledge(postRerankContext);
        var longTermMemory = postRerankContext == null
            ? loadLongTermMemory(apiSpec, normalized)
            : buildPostRerankLongTermMemory(postRerankContext);
        var pruned = postRerankContext == null
            ? pruneToBudget(normalized.tokenBudget(), apiContext, sessionContext, taskMemory, knowledge, longTermMemory)
            : prunePostRerankToBudget(normalized.tokenBudget(), apiContext, sessionContext, taskMemory, knowledge, longTermMemory);
        var constraints = buildConstraints(
            apiSpec,
            normalized.stageProfile(),
            pruned.knowledge(),
            pruned.longTermMemory(),
            postRerankContext,
            requestedPostRerankContext
        );
        var citations = buildCitations(
            pruned.sessionContext(),
            pruned.taskMemory(),
            pruned.knowledge(),
            pruned.longTermMemory()
        );
        memoryUsageRecording.recordLongTermMemoryUsage(normalized, pruned.longTermMemory(), citations);
        var conflicts = detectConflicts(pruned.knowledge(), pruned.taskMemory(), pruned.longTermMemory(), normalized);
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

    private boolean usePostRerankExpandedContext(UnifiedContextQuery query) {
        var postRerankContext = query.postRerankContext();
        return postRerankContext != null
            && postRerankContext.hasRerankOutput()
            && postRerankContext.hasExpandedMaterial();
    }

    private KnowledgeRetrievalResult buildPostRerankKnowledge(PostRerankExpandedContext postRerankContext) {
        var rerankItems = rerankItemsByCandidateId(postRerankContext, RerankCorpusType.KNOWLEDGE);
        var contexts = postRerankContext.knowledgeExpansion().contexts().stream()
            .filter(context -> rerankItems.containsKey(context.anchorChunkId()))
            .sorted(Comparator
                .comparingInt((KnowledgeExpansionContext context) -> rerankItems.get(context.anchorChunkId()).afterRank())
                .thenComparing(KnowledgeExpansionContext::anchorChunkId))
            .toList();

        var hits = new ArrayList<KnowledgeRetrievalHit>();
        var seenParents = new LinkedHashSet<String>();
        for (var context : contexts) {
            if (!seenParents.add(context.parentIdentity())) {
                continue;
            }
            hits.add(postRerankKnowledgeHit(context, rerankItems.get(context.anchorChunkId())));
        }
        return new KnowledgeRetrievalResult(
            "post-rerank-expanded",
            List.copyOf(hits),
            buildPostRerankKnowledgeContext(hits),
            hits.isEmpty() ? 0.0d : 1.0d,
            contexts.size(),
            hits.stream().mapToInt(KnowledgeRetrievalHit::tokenCount).sum(),
            hits.stream().anyMatch(KnowledgeRetrievalHit::lowConfidence),
            postRerankContext.diagnostics()
        );
    }

    private KnowledgeRetrievalHit postRerankKnowledgeHit(KnowledgeExpansionContext context, RerankOutputItem rerankItem) {
        var candidate = rerankItem.candidate();
        var anchor = context.sources().stream()
            .filter(source -> source.chunkId().equals(context.anchorChunkId()))
            .findFirst()
            .or(() -> context.sources().stream().findFirst())
            .orElseThrow(() -> new IllegalArgumentException("knowledge expansion must include at least one source"));
        var metadata = new LinkedHashMap<String, Object>(candidate.metadata());
        metadata.putAll(postRerankEvidence(rerankItem));
        metadata.putAll(knowledgeExpansionEvidence(context));
        return new KnowledgeRetrievalHit(
            context.anchorChunkId(),
            anchor.documentId(),
            anchor.documentRevisionId(),
            anchor.title(),
            expandedKnowledgeContent(context),
            anchor.sourceRef(),
            anchor.documentType(),
            DocumentAuthority.HIGH,
            stringMetadata(candidate.metadata(), "systemName"),
            stringMetadata(candidate.metadata(), "moduleName"),
            firstNonBlank(anchor.businessEntity(), stringMetadata(candidate.metadata(), "businessEntity")),
            listMetadata(candidate.metadata(), "tags"),
            listMetadata(candidate.metadata(), "applicableStages"),
            metadata,
            context.tokenCost(),
            rerankItem.rerankScore(),
            featureScores(candidate.featureLedger()),
            postRerankMatchReasons(rerankItem),
            candidate.featureLedger().lowConfidence(),
            List.of()
        );
    }

    private String expandedKnowledgeContent(KnowledgeExpansionContext context) {
        return context.sources().stream()
            .map(source -> joinNonBlank(source.title(), source.content()))
            .filter(StringUtils::hasText)
            .distinct()
            .toList()
            .stream()
            .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private KnowledgeContext buildPostRerankKnowledgeContext(List<KnowledgeRetrievalHit> hits) {
        if (hits.isEmpty()) {
            return KnowledgeContext.empty(true, true);
        }
        var businessRules = new ArrayList<KnowledgeContextEntry>();
        var apiNotes = new ArrayList<KnowledgeContextEntry>();
        var testSpecs = new ArrayList<KnowledgeContextEntry>();
        var errorCodeGuides = new ArrayList<KnowledgeContextEntry>();
        var environmentNotes = new ArrayList<KnowledgeContextEntry>();
        var incidentHints = new ArrayList<KnowledgeContextEntry>();
        var citedChunks = new ArrayList<KnowledgeContextEntry>();
        for (var hit : hits) {
            var entry = toKnowledgeContextEntry(hit);
            citedChunks.add(entry);
            switch (hit.documentType()) {
                case BUSINESS_FLOW, DOMAIN_RULE -> businessRules.add(entry);
                case API_NOTE -> apiNotes.add(entry);
                case TEST_SPEC -> testSpecs.add(entry);
                case ERROR_CODE_GUIDE -> errorCodeGuides.add(entry);
                case ENV_GUIDE -> environmentNotes.add(entry);
                case INCIDENT_POSTMORTEM -> incidentHints.add(entry);
            }
        }
        return new KnowledgeContext(
            List.copyOf(businessRules),
            List.copyOf(apiNotes),
            List.copyOf(testSpecs),
            List.copyOf(errorCodeGuides),
            List.copyOf(environmentNotes),
            List.copyOf(incidentHints),
            List.copyOf(citedChunks),
            hits.stream().anyMatch(KnowledgeRetrievalHit::lowConfidence),
            false
        );
    }

    private KnowledgeContextEntry toKnowledgeContextEntry(KnowledgeRetrievalHit hit) {
        return new KnowledgeContextEntry(
            hit.chunkId(),
            hit.documentId(),
            hit.documentRevisionId(),
            hit.chunkTitle(),
            hit.score(),
            evidenceType(hit.documentType()),
            hit.sourceRef(),
            hit.metadata(),
            hit.matchReasons(),
            hit.lowConfidence()
        );
    }

    private String evidenceType(DocumentType documentType) {
        return switch (documentType) {
            case BUSINESS_FLOW, DOMAIN_RULE -> "business-rule";
            case API_NOTE -> "api-note";
            case TEST_SPEC -> "test-spec";
            case ERROR_CODE_GUIDE -> "error-code-guide";
            case ENV_GUIDE -> "environment-note";
            case INCIDENT_POSTMORTEM -> "incident-hint";
        };
    }

    private LongTermMemoryRetrievalResult buildPostRerankLongTermMemory(PostRerankExpandedContext postRerankContext) {
        var rerankItems = rerankItemsByCandidateId(postRerankContext, RerankCorpusType.MEMORY);
        var items = postRerankContext.memoryExpansion().items().stream()
            .filter(item -> rerankItems.containsKey(item.anchor().memoryAnchor()))
            .sorted(Comparator
                .comparingInt((MemoryEvidenceExpansionItem item) -> rerankItems.get(item.anchor().memoryAnchor()).afterRank())
                .thenComparing(item -> item.anchor().memoryAnchor()))
            .toList();
        var hits = new ArrayList<LongTermMemoryRetrievalHit>();
        var seenMemoryEvidence = new LinkedHashSet<String>();
        for (var item : items) {
            if (!seenMemoryEvidence.add(item.anchor().memoryAnchor())) {
                continue;
            }
            hits.add(postRerankMemoryHit(item, rerankItems.get(item.anchor().memoryAnchor())));
        }
        return new LongTermMemoryRetrievalResult(
            List.copyOf(hits),
            items.size(),
            hits.stream().mapToInt(LongTermMemoryRetrievalHit::tokenCount).sum(),
            List.of()
        );
    }

    private LongTermMemoryRetrievalHit postRerankMemoryHit(MemoryEvidenceExpansionItem item, RerankOutputItem rerankItem) {
        var candidate = rerankItem.candidate();
        var metadata = new LinkedHashMap<String, Object>(candidate.metadata());
        metadata.putAll(postRerankEvidence(rerankItem));
        metadata.putAll(memoryExpansionEvidence(item));
        return new LongTermMemoryRetrievalHit(
            item.anchor().memoryAnchor(),
            memoryScopeType(candidate),
            firstNonBlank(item.title(), candidate.title()),
            memoryContent(item, candidate),
            firstNonBlank(item.fullContent(), candidate.content()),
            listMetadata(candidate.metadata(), "tags"),
            memorySourceType(candidate),
            memorySourceRef(item, candidate),
            asFloat(candidate.featureLedger().memoryConfidence()),
            asFloat(candidate.featureLedger().memoryImportance()),
            asFloat(candidate.featureLedger().memorySuccessContribution()),
            intMetadata(candidate.metadata(), "hitCount"),
            null,
            metadata,
            item.estimatedTokens(),
            rerankItem.rerankScore(),
            featureScores(candidate.featureLedger()),
            postRerankMatchReasons(rerankItem),
            item.lowConfidence() || candidate.featureLedger().lowConfidence(),
            memoryRouteEvidence(candidate.routeEvidence())
        );
    }

    private String memoryContent(MemoryEvidenceExpansionItem item, RerankCandidate candidate) {
        var evidence = item.evidenceSummaries().isEmpty()
            ? candidate.content()
            : String.join(" ", item.evidenceSummaries());
        return firstNonBlank(evidence, item.fullContent());
    }

    private String memorySourceRef(MemoryEvidenceExpansionItem item, RerankCandidate candidate) {
        if (!item.sourceRefs().isEmpty()) {
            return item.sourceRefs().getFirst();
        }
        return candidate.sourceIdentity().sourceRef();
    }

    private LinkedHashMap<String, RerankOutputItem> rerankItemsByCandidateId(
        PostRerankExpandedContext postRerankContext,
        RerankCorpusType corpusType
    ) {
        var items = new LinkedHashMap<String, RerankOutputItem>();
        for (var item : postRerankContext.rerankOutput().items()) {
            if (item.candidate().corpusType() == corpusType) {
                items.putIfAbsent(item.candidate().candidateIdentity().candidateId(), item);
            }
        }
        return items;
    }

    private Map<String, Object> buildConstraints(
        ApiSpec apiSpec,
        String stageProfile,
        KnowledgeRetrievalResult knowledge,
        LongTermMemoryRetrievalResult longTermMemory,
        PostRerankExpandedContext postRerankContext,
        PostRerankExpandedContext requestedPostRerankContext
    ) {
        var constraints = new LinkedHashMap<String, Object>();
        constraints.put("apiConstraints", new LinkedHashMap<>(apiSpec.getConstraints()));
        constraints.put("apiAuth", new LinkedHashMap<>(apiSpec.getAuth()));
        constraints.put("stageProfile", stageProfile);
        var diagnostics = retrievalDiagnostics(knowledge, longTermMemory);
        if (postRerankContext != null) {
            var postRerankDiagnostics = postRerankDiagnostics(postRerankContext);
            if (!postRerankDiagnostics.isEmpty()) {
                diagnostics.put("postRerank", postRerankDiagnostics);
            }
        } else {
            var postRerankDiagnostics = postRerankFallbackDiagnostics(requestedPostRerankContext);
            if (!postRerankDiagnostics.isEmpty()) {
                diagnostics.put("postRerank", postRerankDiagnostics);
            }
        }
        if (!diagnostics.isEmpty()) {
            constraints.put("retrievalDiagnostics", diagnostics);
        }
        return constraints;
    }

    private Map<String, Object> retrievalDiagnostics(
        KnowledgeRetrievalResult knowledge,
        LongTermMemoryRetrievalResult longTermMemory
    ) {
        var diagnostics = new LinkedHashMap<String, Object>();
        if (knowledge != null && !knowledge.diagnostics().isEmpty()) {
            diagnostics.put("knowledge", List.copyOf(knowledge.diagnostics()));
        }
        if (longTermMemory != null && !longTermMemory.routeDiagnostics().isEmpty()) {
            diagnostics.put("longTermMemory", longTermMemory.routeDiagnostics().stream()
                .map(this::routeDiagnosticEvidence)
                .toList());
        }
        return diagnostics;
    }

    private List<String> postRerankDiagnostics(PostRerankExpandedContext context) {
        var diagnostics = new ArrayList<String>(context.diagnostics());
        if (context.knowledgeExpansion().pruned()) {
            diagnostics.add("knowledge-expansion-pruned");
        }
        if (context.memoryExpansion().pruned()) {
            diagnostics.add("memory-expansion-pruned");
        }
        diagnostics.addAll(context.memoryExpansion().pruningReasons());
        return diagnostics.stream()
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private List<String> postRerankFallbackDiagnostics(PostRerankExpandedContext context) {
        if (context == null) {
            return List.of();
        }
        var diagnostics = new ArrayList<String>(context.diagnostics());
        diagnostics.add("post-rerank-expanded-context-fallback");
        if (!context.hasRerankOutput()) {
            diagnostics.add("post-rerank-output-missing");
        }
        if (!context.hasExpandedMaterial()) {
            diagnostics.add("post-rerank-expansion-missing");
        }
        return diagnostics.stream()
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private Map<String, Object> routeDiagnosticEvidence(RetrievalRouteDiagnostic diagnostic) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("routeName", diagnostic.routeName());
        putIfPresent(evidence, "queryVariantId", diagnostic.queryVariantId());
        evidence.put("routeLimit", diagnostic.routeLimit());
        evidence.put("confidenceGate", diagnostic.confidenceGate());
        evidence.put("candidateCount", diagnostic.candidateCount());
        evidence.put("diagnostic", diagnostic.diagnostic());
        return Map.copyOf(evidence);
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
            copyFusionEvidence(evidence, metadata);
            copyPostRerankEvidence(evidence, metadata);
        }
        var matchReasons = entry.matchReasons() == null ? List.<String>of() : List.copyOf(entry.matchReasons());
        if (!matchReasons.isEmpty()) {
            evidence.put("matchReasons", matchReasons);
        }
        if (hit != null && hit.componentScores() != null && !hit.componentScores().isEmpty()) {
            evidence.put("componentScores", new LinkedHashMap<>(hit.componentScores()));
        }
        if (hit != null) {
            copyKnowledgeRouteEvidence(evidence, hit.routeEvidence());
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
            copyFusionEvidence(evidence, metadata);
            copyPostRerankEvidence(evidence, metadata);
        }
        if (hit.componentScores() != null && !hit.componentScores().isEmpty()) {
            evidence.put("componentScores", new LinkedHashMap<>(hit.componentScores()));
        }
        var matchReasons = hit.matchReasons() == null ? List.<String>of() : List.copyOf(hit.matchReasons());
        if (!matchReasons.isEmpty()) {
            evidence.put("matchReasons", matchReasons);
        }
        copyMemoryRouteEvidence(evidence, hit.routeEvidence());
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
        copyMetadataValue(evidence, metadata, "graphMatchReason");
        copyMetadataValue(evidence, metadata, "graphRelationPath");
        copyMetadataValue(evidence, metadata, "graphRelationConfidence");
        copyMetadataValue(evidence, metadata, "graphSourceMemoryIds");
        copyMetadataValue(evidence, metadata, "graphSourceRefs");
        copyMetadataValue(evidence, metadata, "graphFactFingerprints");
        copyMetadataValue(evidence, metadata, "graphEvidenceSummaries");
    }

    private void copyFusionEvidence(Map<String, Object> evidence, Map<String, Object> metadata) {
        copyMetadataValue(evidence, metadata, "routeEvidence");
        copyMetadataValue(evidence, metadata, "routeNames");
        copyMetadataValue(evidence, metadata, "retrievalRoutes");
        copyMetadataValue(evidence, metadata, "queryVariantIds");
        copyMetadataValue(evidence, metadata, "queryVariantIntents");
        copyMetadataValue(evidence, metadata, "queryVariantIntentById");
        copyMetadataValue(evidence, metadata, "preFusionRoute");
        copyMetadataValue(evidence, metadata, "preFusionRank");
        copyMetadataValue(evidence, metadata, "preFusionRanks");
        copyMetadataValue(evidence, metadata, "preFusionScore");
        copyMetadataValue(evidence, metadata, "fusedScore");
        copyMetadataValue(evidence, metadata, "fusionExplanation");
    }

    private void copyPostRerankEvidence(Map<String, Object> evidence, Map<String, Object> metadata) {
        copyMetadataValue(evidence, metadata, "postRerank");
        copyMetadataValue(evidence, metadata, "preRerankRank");
        copyMetadataValue(evidence, metadata, "postRerankRank");
        copyMetadataValue(evidence, metadata, "rerankScore");
        copyMetadataValue(evidence, metadata, "scoreExplanation");
        copyMetadataValue(evidence, metadata, "rerankReasons");
        copyMetadataValue(evidence, metadata, "rerankPenalties");
        copyMetadataValue(evidence, metadata, "featureDiagnostics");
        copyMetadataValue(evidence, metadata, "conflictSignals");
        copyMetadataValue(evidence, metadata, "smallToBigAnchor");
        copyMetadataValue(evidence, metadata, "parentIdentity");
        copyMetadataValue(evidence, metadata, "expansionReason");
        copyMetadataValue(evidence, metadata, "expansionTokenCost");
        copyMetadataValue(evidence, metadata, "expandedSources");
        copyMetadataValue(evidence, metadata, "expandedSourceRefs");
        copyMetadataValue(evidence, metadata, "expansionCitations");
        copyMetadataValue(evidence, metadata, "expansionPruningReasons");
        copyMetadataValue(evidence, metadata, "positiveRecommendationEligible");
        copyMetadataValue(evidence, metadata, "lowConfidenceReason");
        copyMetadataValue(evidence, metadata, "evidenceSummaries");
        copyMetadataValue(evidence, metadata, "sourceRefs");
        copyMetadataValue(evidence, metadata, "mergedSourceRefs");
        copyMetadataValue(evidence, metadata, "evidenceCount");
        copyMetadataValue(evidence, metadata, "identityHints");
        copyMetadataValue(evidence, metadata, "graphRelation");
        copyMetadataValue(evidence, metadata, "conflictAudit");
    }

    private Map<String, Object> postRerankEvidence(RerankOutputItem item) {
        var evidence = new LinkedHashMap<String, Object>();
        var routeEvidence = item.candidate().routeEvidence();
        evidence.put("postRerank", true);
        evidence.put("preRerankRank", item.beforeRank());
        evidence.put("postRerankRank", item.afterRank());
        evidence.put("rerankScore", item.rerankScore());
        putIfPresent(evidence, "scoreExplanation", item.scoreExplanation());
        evidence.put("rerankReasons", List.copyOf(item.reasons()));
        evidence.put("rerankPenalties", List.copyOf(item.penalties()));
        putIfPresent(evidence, "fusedScore", item.candidate().fusedScore());
        if (!routeEvidence.queryVariants().isEmpty()) {
            evidence.put("queryVariantIds", List.copyOf(routeEvidence.queryVariants()));
        }
        if (!routeEvidence.matchedRoutes().isEmpty()) {
            evidence.put("routeEvidence", routeEvidence.matchedRoutes().stream()
                .map(route -> {
                    var routeMap = new LinkedHashMap<String, Object>();
                    routeMap.put("routeName", route.routeName());
                    routeMap.put("routeRank", route.rank());
                    routeMap.put("routeScore", route.score());
                    return Map.copyOf(routeMap);
                })
                .toList());
            evidence.put("routeNames", routeEvidence.matchedRoutes().stream()
                .map(route -> route.routeName())
                .distinct()
                .toList());
        }
        if (!routeEvidence.routeRanks().isEmpty()) {
            evidence.put("routeRanks", routeEvidence.routeRanks());
        }
        if (!routeEvidence.routeScores().isEmpty()) {
            evidence.put("routeScores", routeEvidence.routeScores());
        }
        var ledger = item.candidate().featureLedger();
        if (!ledger.diagnostics().isEmpty()) {
            evidence.put("featureDiagnostics", ledger.diagnostics().stream()
                .map(this::featureDiagnosticEvidence)
                .toList());
        }
        if (!ledger.conflictSignals().isEmpty()) {
            evidence.put("conflictSignals", List.copyOf(ledger.conflictSignals()));
        }
        evidence.put("lowConfidence", ledger.lowConfidence());
        return evidence;
    }

    private Map<String, Object> featureDiagnosticEvidence(RerankFeatureDiagnostic diagnostic) {
        var evidence = new LinkedHashMap<String, Object>();
        putIfPresent(evidence, "code", diagnostic.code());
        putIfPresent(evidence, "feature", diagnostic.feature());
        putIfPresent(evidence, "message", diagnostic.message());
        return Map.copyOf(evidence);
    }

    private Map<String, Object> knowledgeExpansionEvidence(KnowledgeExpansionContext context) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("smallToBigAnchor", context.anchorChunkId());
        evidence.put("parentIdentity", context.parentIdentity());
        evidence.put("expansionReason", context.expansionReason().name());
        evidence.put("sourceRevision", context.sourceRevision());
        evidence.put("expansionTokenCost", context.tokenCost());
        evidence.put("expandedSources", context.sources().stream()
            .map(this::knowledgeExpansionSourceEvidence)
            .toList());
        evidence.put("expandedSourceRefs", context.sources().stream()
            .map(KnowledgeExpansionSource::sourceRef)
            .filter(StringUtils::hasText)
            .distinct()
            .toList());
        if (!context.citations().isEmpty()) {
            evidence.put("expansionCitations", context.citations().stream()
                .map(citation -> {
                    var citationEvidence = new LinkedHashMap<String, Object>();
                    citationEvidence.put("sourceChunkId", citation.sourceChunkId());
                    citationEvidence.put("sourceRevision", citation.sourceRevision());
                    putIfPresent(citationEvidence, "sourceRef", citation.sourceRef());
                    citationEvidence.put("role", citation.role().name());
                    return Map.copyOf(citationEvidence);
                })
                .toList());
        }
        if (!context.pruningReasons().isEmpty()) {
            evidence.put("expansionPruningReasons", context.pruningReasons().stream()
                .map(this::knowledgeExpansionPruningEvidence)
                .toList());
        }
        return evidence;
    }

    private Map<String, Object> knowledgeExpansionSourceEvidence(KnowledgeExpansionSource source) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("chunkId", source.chunkId());
        evidence.put("documentId", source.documentId());
        evidence.put("documentRevisionId", source.documentRevisionId());
        evidence.put("documentType", source.documentType().name());
        evidence.put("title", source.title());
        evidence.put("sourceRef", source.sourceRef());
        evidence.put("parentIdentity", source.parentIdentity());
        putIfPresent(evidence, "heading", source.heading());
        putIfPresent(evidence, "entryKey", source.entryKey());
        putIfPresent(evidence, "businessEntity", source.businessEntity());
        putIfPresent(evidence, "flowId", source.flowId());
        evidence.put("tokenCost", source.tokenCost());
        return Map.copyOf(evidence);
    }

    private Map<String, Object> knowledgeExpansionPruningEvidence(KnowledgeExpansionPruning pruning) {
        return Map.of(
            "sourceChunkId", pruning.sourceChunkId(),
            "reason", pruning.reason().name()
        );
    }

    private Map<String, Object> memoryExpansionEvidence(MemoryEvidenceExpansionItem item) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("smallToBigAnchor", item.anchor().memoryAnchor());
        evidence.put("memoryAnchorType", item.anchor().anchorType().name());
        evidence.put("factFingerprint", item.anchor().factFingerprint());
        evidence.put("graphRelationPath", item.anchor().graphRelationPath());
        evidence.put("expansionTokenCost", item.estimatedTokens());
        evidence.put("evidenceSummaries", List.copyOf(item.evidenceSummaries()));
        evidence.put("sourceRefs", List.copyOf(item.sourceRefs()));
        evidence.put("mergedSourceRefs", List.copyOf(item.mergedSourceRefs()));
        evidence.put("evidenceCount", item.evidenceCount());
        evidence.put("identityHints", item.identityHints().asMap());
        evidence.put("positiveRecommendationEligible", item.positiveRecommendationEligible());
        evidence.put("lowConfidence", item.lowConfidence());
        putIfPresent(evidence, "lowConfidenceReason", item.lowConfidenceReason());
        if (item.graphRelation() != null) {
            evidence.put("graphRelation", graphRelationEvidence(item.graphRelation()));
        }
        if (!item.conflictAudit().isEmpty()) {
            evidence.put("conflictAudit", item.conflictAudit().stream()
                .map(this::conflictAuditEvidence)
                .toList());
        }
        if (!item.pruningReasons().isEmpty()) {
            evidence.put("expansionPruningReasons", List.copyOf(item.pruningReasons()));
        }
        if (!item.citations().isEmpty()) {
            evidence.put("expansionCitations", item.citations().stream()
                .map(citation -> {
                    var citationEvidence = new LinkedHashMap<String, Object>();
                    citationEvidence.put("memoryAnchor", citation.memoryAnchor());
                    citationEvidence.put("factFingerprint", citation.factFingerprint());
                    citationEvidence.put("evidenceSource", citation.evidenceSource());
                    citationEvidence.put("role", citation.role().name());
                    return Map.copyOf(citationEvidence);
                })
                .toList());
        }
        return evidence;
    }

    private Map<String, Object> graphRelationEvidence(MemoryGraphRelationExpansion graphRelation) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("relationPath", graphRelation.relationPath());
        putIfPresent(evidence, "relationConfidence", graphRelation.relationConfidence());
        evidence.put("sourceMemoryIds", graphRelation.sourceMemoryIds());
        evidence.put("sourceRefs", graphRelation.sourceRefs());
        evidence.put("factFingerprints", graphRelation.factFingerprints());
        putIfPresent(evidence, "graphEvidenceSummary", graphRelation.graphEvidenceSummary());
        putIfPresent(evidence, "explanation", graphRelation.explanation());
        return Map.copyOf(evidence);
    }

    private Map<String, Object> conflictAuditEvidence(MemoryConflictAuditEvidence audit) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("role", audit.role().name());
        putIfPresent(evidence, "summary", audit.summary());
        evidence.put("sourceRefs", audit.sourceRefs());
        return Map.copyOf(evidence);
    }

    private List<String> postRerankMatchReasons(RerankOutputItem item) {
        var reasons = new ArrayList<String>();
        reasons.add("post-rerank-expanded");
        reasons.addAll(item.reasons());
        if (!item.penalties().isEmpty()) {
            reasons.addAll(item.penalties().stream()
                .map(penalty -> "penalty:" + penalty)
                .toList());
        }
        return List.copyOf(reasons);
    }

    private Map<String, Double> featureScores(RerankFeatureLedger ledger) {
        var scores = new LinkedHashMap<String, Double>();
        putScore(scores, "semantic", ledger.semantic());
        putScore(scores, "metadata", ledger.metadata());
        putScore(scores, "lexical", ledger.lexical());
        putScore(scores, "routeAgreement", ledger.routeAgreement());
        putScore(scores, "exactEntity", ledger.exactEntity());
        putScore(scores, "stageFit", ledger.stageFit());
        putScore(scores, "authority", ledger.authority());
        putScore(scores, "freshness", ledger.freshness());
        putScore(scores, "memoryConfidence", ledger.memoryConfidence());
        putScore(scores, "memoryImportance", ledger.memoryImportance());
        putScore(scores, "memorySuccessContribution", ledger.memorySuccessContribution());
        putScore(scores, "graphConfidence", ledger.graphConfidence());
        if (ledger.graphPathLength() != null) {
            scores.put("graphPathLength", ledger.graphPathLength().doubleValue());
        }
        scores.put("tokenCost", (double) ledger.tokenCost());
        return Map.copyOf(scores);
    }

    private void putScore(Map<String, Double> scores, String name, Double value) {
        if (value != null) {
            scores.put(name, value);
        }
    }

    private List<RetrievalRouteEvidence> memoryRouteEvidence(RerankRouteEvidence routeEvidence) {
        if (routeEvidence == null || routeEvidence.matchedRoutes().isEmpty()) {
            return List.of();
        }
        var queryVariantId = routeEvidence.queryVariants().isEmpty() ? null : routeEvidence.queryVariants().getFirst();
        return routeEvidence.matchedRoutes().stream()
            .map(route -> new RetrievalRouteEvidence(
                route.routeName(),
                queryVariantId,
                null,
                route.rank(),
                route.score(),
                "post-rerank matched route",
                Map.of()
            ))
            .toList();
    }

    private void copyKnowledgeRouteEvidence(Map<String, Object> evidence, List<KnowledgeRouteEvidence> routeEvidence) {
        if (routeEvidence == null || routeEvidence.isEmpty()) {
            return;
        }
        evidence.putIfAbsent("routeEvidence", routeEvidence.stream()
            .map(this::knowledgeRouteEvidence)
            .toList());
        copyRouteEvidenceSummary(
            evidence,
            routeEvidence.stream().map(KnowledgeRouteEvidence::routeName).toList(),
            routeEvidence.stream().map(KnowledgeRouteEvidence::queryVariantId).toList(),
            routeEvidence.stream().map(KnowledgeRouteEvidence::queryIntent).toList(),
            routeEvidence.stream().map(KnowledgeRouteEvidence::matchReason).toList(),
            routeEvidence.stream().collect(java.util.stream.Collectors.toMap(
                item -> item.routeName() + ":" + item.queryVariantId(),
                KnowledgeRouteEvidence::routeRank,
                (left, right) -> left,
                LinkedHashMap::new
            ))
        );
        putIfAbsentNonEmpty(evidence, "queryVariantIntentById", queryVariantIntentById(routeEvidence.stream()
            .map(item -> new RouteVariantIntent(item.queryVariantId(), item.queryIntent()))
            .toList()));
    }

    private void copyMemoryRouteEvidence(Map<String, Object> evidence, List<RetrievalRouteEvidence> routeEvidence) {
        if (routeEvidence == null || routeEvidence.isEmpty()) {
            return;
        }
        evidence.putIfAbsent("routeEvidence", routeEvidence.stream()
            .map(this::memoryRouteEvidence)
            .toList());
        copyRouteEvidenceSummary(
            evidence,
            routeEvidence.stream().map(RetrievalRouteEvidence::routeName).toList(),
            routeEvidence.stream().map(RetrievalRouteEvidence::queryVariantId).toList(),
            routeEvidence.stream().map(RetrievalRouteEvidence::queryIntent).toList(),
            routeEvidence.stream().map(RetrievalRouteEvidence::matchReason).toList(),
            routeEvidence.stream().collect(java.util.stream.Collectors.toMap(
                item -> item.routeName() + ":" + item.queryVariantId(),
                RetrievalRouteEvidence::routeRank,
                (left, right) -> left,
                LinkedHashMap::new
            ))
        );
        putIfAbsentNonEmpty(evidence, "queryVariantIntentById", queryVariantIntentById(routeEvidence.stream()
            .map(item -> new RouteVariantIntent(item.queryVariantId(), item.queryIntent()))
            .toList()));
    }

    private void copyRouteEvidenceSummary(
        Map<String, Object> evidence,
        List<String> routeNames,
        List<String> queryVariantIds,
        List<String> queryVariantIntents,
        List<String> matchReasons,
        Map<String, Integer> routeRanks
    ) {
        putIfAbsentNonEmpty(evidence, "routeNames", distinctNonBlank(routeNames));
        putIfAbsentNonEmpty(evidence, "queryVariantIds", distinctNonBlank(queryVariantIds));
        putIfAbsentNonEmpty(evidence, "queryVariantIntents", distinctNonBlank(queryVariantIntents));
        putIfAbsentNonEmpty(evidence, "routeMatchReasons", distinctNonBlank(matchReasons));
        if (!routeRanks.isEmpty()) {
            evidence.putIfAbsent("routeRanks", routeRanks);
        }
    }

    private Map<String, Object> knowledgeRouteEvidence(KnowledgeRouteEvidence routeEvidence) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("routeName", routeEvidence.routeName());
        evidence.put("queryVariantId", routeEvidence.queryVariantId());
        putIfPresent(evidence, "queryIntent", routeEvidence.queryIntent());
        evidence.put("routeRank", routeEvidence.routeRank());
        evidence.put("routeScore", routeEvidence.routeScore());
        evidence.put("matchReason", routeEvidence.matchReason());
        return Map.copyOf(evidence);
    }

    private Map<String, Object> memoryRouteEvidence(RetrievalRouteEvidence routeEvidence) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("routeName", routeEvidence.routeName());
        putIfPresent(evidence, "queryVariantId", routeEvidence.queryVariantId());
        putIfPresent(evidence, "queryIntent", routeEvidence.queryIntent());
        evidence.put("routeRank", routeEvidence.routeRank());
        evidence.put("routeScore", routeEvidence.routeScore());
        evidence.put("matchReason", routeEvidence.matchReason());
        if (!routeEvidence.sourceEvidence().isEmpty()) {
            evidence.put("sourceEvidence", routeEvidence.sourceEvidence());
        }
        return Map.copyOf(evidence);
    }

    private List<String> distinctNonBlank(List<String> values) {
        return values.stream()
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private void putIfAbsentNonEmpty(Map<String, Object> evidence, String key, List<String> values) {
        if (!values.isEmpty()) {
            evidence.putIfAbsent(key, values);
        }
    }

    private void putIfAbsentNonEmpty(Map<String, Object> evidence, String key, Map<String, String> values) {
        if (!values.isEmpty()) {
            evidence.putIfAbsent(key, values);
        }
    }

    private Map<String, String> queryVariantIntentById(List<RouteVariantIntent> values) {
        var byId = new LinkedHashMap<String, String>();
        for (var value : values) {
            if (StringUtils.hasText(value.queryVariantId()) && StringUtils.hasText(value.queryIntent())) {
                byId.putIfAbsent(value.queryVariantId(), value.queryIntent());
            }
        }
        return Map.copyOf(byId);
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
            keptLongTermHits.stream().mapToInt(LongTermMemoryRetrievalHit::tokenCount).sum(),
            longTermMemory.routeDiagnostics()
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

    private PrunedContext prunePostRerankToBudget(
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
        var sessionTokens = sessionContext.stream()
            .mapToInt(memory -> estimateTokens(joinNonBlank(memory.summary(), memory.content())))
            .sum();
        var baseTokens = apiTokens + taskMemoryTokens;
        var originalEstimatedTokens = baseTokens + sessionTokens + knowledge.totalTokens() + longTermMemory.totalTokens();
        var remaining = Math.max(0, tokenBudget - baseTokens);

        var keptKnowledgeIds = new LinkedHashSet<String>();
        var keptMemoryIds = new LinkedHashSet<String>();
        for (var candidate : postBudgetCandidates(knowledge, longTermMemory)) {
            if (candidate.tokens() <= remaining) {
                if (candidate.corpusType() == RerankCorpusType.KNOWLEDGE) {
                    keptKnowledgeIds.add(candidate.sourceId());
                } else {
                    keptMemoryIds.add(candidate.sourceId());
                }
                remaining -= candidate.tokens();
            }
        }

        var keptSession = new ArrayList<SessionMemoryView>();
        for (var memory : sessionContext) {
            var tokens = estimateTokens(joinNonBlank(memory.summary(), memory.content()));
            if (tokens <= remaining) {
                keptSession.add(memory);
                remaining -= tokens;
            }
        }

        var keptKnowledgeHits = knowledge.hits().stream()
            .filter(hit -> keptKnowledgeIds.contains(hit.chunkId()))
            .sorted(Comparator
                .comparingInt((KnowledgeRetrievalHit hit) -> postRerankRank(hit))
                .thenComparing(KnowledgeRetrievalHit::chunkId))
            .toList();
        var keptMemoryHits = longTermMemory.hits().stream()
            .filter(hit -> keptMemoryIds.contains(hit.memoryId()))
            .sorted(Comparator
                .comparingInt((LongTermMemoryRetrievalHit hit) -> postRerankRank(hit))
                .thenComparing(LongTermMemoryRetrievalHit::memoryId))
            .toList();
        var prunedKnowledge = rebuildKnowledgeResult(knowledge, keptKnowledgeHits);
        var prunedLongTerm = new LongTermMemoryRetrievalResult(
            List.copyOf(keptMemoryHits),
            longTermMemory.totalCandidates(),
            keptMemoryHits.stream().mapToInt(LongTermMemoryRetrievalHit::tokenCount).sum(),
            longTermMemory.routeDiagnostics()
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

    private List<PostBudgetCandidate> postBudgetCandidates(
        KnowledgeRetrievalResult knowledge,
        LongTermMemoryRetrievalResult longTermMemory
    ) {
        var candidates = new ArrayList<PostBudgetCandidate>();
        for (var hit : knowledge.hits()) {
            candidates.add(new PostBudgetCandidate(
                RerankCorpusType.KNOWLEDGE,
                hit.chunkId(),
                hit.tokenCount(),
                postRerankRank(hit),
                hit.score()
            ));
        }
        for (var hit : longTermMemory.hits()) {
            candidates.add(new PostBudgetCandidate(
                RerankCorpusType.MEMORY,
                hit.memoryId(),
                hit.tokenCount(),
                postRerankRank(hit),
                hit.score()
            ));
        }
        return candidates.stream()
            .sorted(Comparator
                .comparingInt(PostBudgetCandidate::postRerankRank)
                .thenComparing(Comparator.comparingDouble(PostBudgetCandidate::score).reversed())
                .thenComparing(candidate -> candidate.corpusType().name())
                .thenComparing(PostBudgetCandidate::sourceId))
            .toList();
    }

    private int postRerankRank(KnowledgeRetrievalHit hit) {
        return intValue(hit.metadata().get("postRerankRank"), Integer.MAX_VALUE);
    }

    private int postRerankRank(LongTermMemoryRetrievalHit hit) {
        return intValue(hit.metadata().get("postRerankRank"), Integer.MAX_VALUE);
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
            keptHits.isEmpty() || original.lowConfidence(),
            original.diagnostics()
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
            normalizeNullable(query.usageSourceRef()),
            query.postRerankContext()
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

    private String stringMetadata(Map<String, Object> metadata, String key) {
        var value = metadata == null ? null : metadata.get(key);
        return value == null ? null : normalizeNullable(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<String> listMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) {
            return List.of();
        }
        var value = metadata.get(key);
        if (value instanceof List<?> values) {
            return values.stream()
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return List.of(text.trim());
        }
        return List.of();
    }

    private Integer intMetadata(Map<String, Object> metadata, String key) {
        return metadata == null ? null : intValue(metadata.get(key), null);
    }

    private int intValue(Object value, int fallback) {
        var parsed = intValue(value, (Integer) null);
        return parsed == null ? fallback : parsed;
    }

    private Integer intValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Float asFloat(Double value) {
        return value == null ? null : value.floatValue();
    }

    private Double asDouble(Float value) {
        return value == null ? null : value.doubleValue();
    }

    private MemoryScopeType memoryScopeType(RerankCandidate candidate) {
        var scopeType = stringMetadata(candidate.metadata(), "scopeType");
        if (!StringUtils.hasText(scopeType)) {
            scopeType = candidate.sourceIdentity().sourceType();
        }
        try {
            return MemoryScopeType.valueOf(scopeType);
        } catch (Exception ignored) {
            return MemoryScopeType.PROJECT_KNOWLEDGE;
        }
    }

    private MemorySourceType memorySourceType(RerankCandidate candidate) {
        var sourceType = stringMetadata(candidate.metadata(), "sourceType");
        try {
            return StringUtils.hasText(sourceType)
                ? MemorySourceType.valueOf(sourceType)
                : MemorySourceType.MEMORY_REFINERY;
        } catch (Exception ignored) {
            return MemorySourceType.MEMORY_REFINERY;
        }
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

    private record RouteVariantIntent(String queryVariantId, String queryIntent) {
    }

    private record PostBudgetCandidate(
        RerankCorpusType corpusType,
        String sourceId,
        int tokens,
        int postRerankRank,
        double score
    ) {
    }
}
