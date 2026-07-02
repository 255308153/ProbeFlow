package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.knowledge.ChunkStatus;
import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.DocumentSourceType;
import com.probeflow.testagent.knowledge.DocumentStatus;
import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.knowledge.KnowledgeChunk;
import com.probeflow.testagent.knowledge.KnowledgeChunkRepository;
import com.probeflow.testagent.knowledge.KnowledgeDocument;
import com.probeflow.testagent.knowledge.KnowledgeDocumentRepository;
import com.probeflow.testagent.knowledge.KnowledgeDocumentRevision;
import com.probeflow.testagent.knowledge.KnowledgeDocumentRevisionRepository;
import com.probeflow.testagent.knowledge.RevisionStatus;
import com.probeflow.testagent.memory.LongTermMemory;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.MemoryStatus;
import com.probeflow.testagent.memory.TaskMemoryItem;
import com.probeflow.testagent.memory.TaskMemoryItemRepository;
import com.probeflow.testagent.observation.AnalysisLevel;
import com.probeflow.testagent.observation.Observation;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.observation.ObservationRiskLevel;
import com.probeflow.testagent.observation.ObservationSource;
import com.probeflow.testagent.observation.ObservationType;
import com.probeflow.testagent.report.Report;
import com.probeflow.testagent.report.ReportRepository;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterial;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcase.CaseCategory;
import com.probeflow.testagent.testcase.CasePriority;
import com.probeflow.testagent.testcase.CaseRiskLevel;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcase.CaseStatus;
import com.probeflow.testagent.testcase.DetailType;
import com.probeflow.testagent.testcase.StaleStatus;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcase.TestCaseRepository;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class Phase1AcceptanceRepositoryTests {

    @Autowired
    private SourceMaterialRepository sourceMaterials;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TestCaseRepository testCases;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ExecutionRecordRepository executionRecords;

    @Autowired
    private ObservationRepository observations;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeDocumentRevisionRepository revisions;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Autowired
    private TaskMemoryItemRepository taskMemories;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private ReportRepository reports;

    @Autowired
    private EntityManager entityManager;

    @Test
    void phase1RepresentativePersistencePathsSaveAndLoadTogether() {
        var task = tasks.save(newTask());
        var sourceMaterial = sourceMaterials.save(newSourceMaterial(task.getTaskId()));
        var apiSpec = apiSpecs.save(newApiSpec());
        var testCase = testCases.save(newTestCase(apiSpec.getApiSpecId()));
        var draft = drafts.save(newDraft(task.getTaskId(), apiSpec.getApiSpecId()));
        var executionRecord = executionRecords.save(newExecutionRecord(task.getTaskId(), testCase.getCaseId()));
        var observation = observations.save(newObservation(task.getTaskId(), executionRecord.getExecutionId()));
        var document = documents.save(newKnowledgeDocument());
        var revision = revisions.save(newKnowledgeRevision(document.getDocumentId()));
        var chunk = chunks.save(newKnowledgeChunk(document.getDocumentId(), revision.getDocumentRevisionId()));
        var taskMemory = taskMemories.save(newTaskMemory(task.getTaskId(), observation.getObservationId()));
        var longTermMemory = longTermMemories.save(newLongTermMemory(task.getTaskId()));
        var report = reports.save(newReport(task.getTaskId()));

        entityManager.flush();
        entityManager.clear();

        assertThat(tasks.findById(task.getTaskId())).isPresent();
        assertThat(sourceMaterials.findById(sourceMaterial.getMaterialId())).isPresent();
        assertThat(apiSpecs.findById(apiSpec.getApiSpecId())).isPresent();
        assertThat(testCases.findById(testCase.getCaseId())).isPresent();
        assertThat(drafts.findById(draft.getDraftId())).isPresent();
        assertThat(executionRecords.findById(executionRecord.getExecutionId())).isPresent();
        assertThat(observations.findById(observation.getObservationId())).isPresent();
        assertThat(documents.findById(document.getDocumentId())).isPresent();
        assertThat(revisions.findById(revision.getDocumentRevisionId())).isPresent();
        assertThat(chunks.findById(chunk.getChunkId()).orElseThrow().getEmbedding()).hasSize(1024);
        assertThat(taskMemories.findById(taskMemory.getMemoryId())).isPresent();
        assertThat(longTermMemories.findById(longTermMemory.getMemoryId()).orElseThrow().getEmbedding()).hasSize(1024);
        assertThat(reports.findById(report.getReportId())).isPresent();
    }

    private Task newTask() {
        var task = new Task();
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 1 acceptance task");
        task.setStatus(TaskStatus.EXECUTING);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setTargetApiSpecIds(List.of("api-spec-phase-1"));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("phase-1-acceptance");
        task.setMetadata(Map.of("phase", "1"));
        return task;
    }

    private SourceMaterial newSourceMaterial(String taskId) {
        var material = new SourceMaterial();
        material.setTaskId(taskId);
        material.setMaterialType(MaterialType.OPENAPI_FILE);
        material.setOriginalName("orders.yaml");
        material.setOriginalRef("file://orders.yaml");
        material.setStoragePath("/tmp/probeflow/orders.yaml");
        material.setIngestStatus(IngestStatus.READY);
        return material;
    }

    private ApiSpec newApiSpec() {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/api/orders");
        apiSpec.setSummary("Create order");
        apiSpec.setParameters(Map.of("body", Map.of("skuId", "string")));
        apiSpec.setConstraints(Map.of("skuId", Map.of("required", true)));
        apiSpec.setAuth(Map.of("type", "bearer"));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("orders.yaml#/paths/~1api~1orders/post");
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        return apiSpec;
    }

    private TestCase newTestCase(String apiSpecId) {
        var testCase = new TestCase();
        testCase.setPrimaryApiSpecId(apiSpecId);
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(TestCaseMode.SINGLE);
        testCase.setTitle("create-order-happy-path");
        testCase.setDescription("Create an order with valid input.");
        testCase.setPreconditions(List.of("tenant exists"));
        testCase.setExpectedResult("order is created");
        testCase.setPriority(CasePriority.HIGH);
        testCase.setRiskLevel(CaseRiskLevel.HIGH);
        testCase.setTags(List.of("order", "smoke"));
        testCase.setScenarioName("create-order");
        testCase.setModuleName("order");
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(CaseSource.MANUAL);
        testCase.setManualEdited(false);
        testCase.setLocked(false);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(Map.of("method", "POST", "path", "/api/orders"));
        testCase.setSteps(List.of());
        testCase.setStaleStatus(StaleStatus.FRESH);
        testCase.setBasedOnApiSpecVersions(Map.of(apiSpecId, 1));
        testCase.setGeneratedFromSingleCaseIds(List.of());
        testCase.setUpdatedBy("phase-1-acceptance");
        return testCase;
    }

    private TestCaseDraft newDraft(String taskId, String apiSpecId) {
        var draft = new TestCaseDraft();
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(DraftStatus.PENDING_REVIEW);
        draft.setPromotionMode(PromotionMode.MANUAL);
        draft.setTargetApiSpecId(apiSpecId);
        draft.setDedupKey("phase1:create-order:happy");
        draft.setExpectedStatusCode(201);
        draft.setDraftContent(Map.of("title", "create-order-happy-path"));
        return draft;
    }

    private ExecutionRecord newExecutionRecord(String taskId, String caseId) {
        var record = new ExecutionRecord();
        record.setTaskId(taskId);
        record.setCaseId(caseId);
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment("test");
        record.setRequestSnapshot(Map.of("method", "POST", "path", "/api/orders"));
        record.setResponseSnapshot(Map.of("statusCode", 201));
        record.setAssertionResults(List.of(Map.of("assertion", "status", "passed", true)));
        record.setOverallStatus(OverallStatus.PASSED);
        record.setCriticalFailed(false);
        record.setDurationMs(42L);
        record.setStatusCode(201);
        return record;
    }

    private Observation newObservation(String taskId, String executionId) {
        var observation = new Observation();
        observation.setTaskId(taskId);
        observation.setExecutionId(executionId);
        observation.setObservationType(ObservationType.GENERAL_COMMENT);
        observation.setAnalysisLevel(AnalysisLevel.BASIC);
        observation.setSummary("Phase 1 storage acceptance observation.");
        observation.setFailureReason("none");
        observation.setRiskLevel(ObservationRiskLevel.LOW);
        observation.setNextSuggestion("Continue with behavior slices.");
        observation.setSource(ObservationSource.SYSTEM);
        return observation;
    }

    private KnowledgeDocument newKnowledgeDocument() {
        var document = new KnowledgeDocument();
        document.setTitle("Order API note");
        document.setSystemName("order-platform");
        document.setModuleName("order");
        document.setDocType(DocumentType.API_NOTE);
        document.setBizEntity("order");
        document.setSourceType(DocumentSourceType.MANUAL);
        document.setSourceRef("manual://order-api-note");
        document.setAuthority(DocumentAuthority.MEDIUM);
        document.setStatus(DocumentStatus.ACTIVE);
        document.setMetadata(Map.of("phase", "1"));
        document.setRawContent("Order API accepts valid tenant-scoped requests.");
        return document;
    }

    private KnowledgeDocumentRevision newKnowledgeRevision(String documentId) {
        var revision = new KnowledgeDocumentRevision();
        revision.setDocumentId(documentId);
        revision.setVersion(1);
        revision.setLatest(true);
        revision.setRevisionStatus(RevisionStatus.ACTIVE);
        revision.setSourceHash("phase-1-revision");
        revision.setMetadata(Map.of("phase", "1"));
        return revision;
    }

    private KnowledgeChunk newKnowledgeChunk(String documentId, String revisionId) {
        var chunk = new KnowledgeChunk();
        chunk.setDocumentId(documentId);
        chunk.setDocumentRevisionId(revisionId);
        chunk.setChunkStatus(ChunkStatus.ACTIVE);
        chunk.setChunkTitle("Tenant rule");
        chunk.setChunkContent("Order APIs require tenant context.");
        chunk.setChunkOrder(1);
        chunk.setTags(List.of("tenant"));
        chunk.setApplicableStages(List.of("api_analysis"));
        chunk.setMetadata(Map.of("phase", "1"));
        chunk.setTokenCount(6);
        chunk.setEmbedding(testEmbedding(1000.0f));
        return chunk;
    }

    private TaskMemoryItem newTaskMemory(String taskId, String sourceRef) {
        var memory = new TaskMemoryItem();
        memory.setTaskId(taskId);
        memory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        memory.setSummary("No failure pattern yet");
        memory.setContent("Task memory can hold scoped execution facts.");
        memory.setTags(List.of("phase-1"));
        memory.setSourceType(MemorySourceType.OBSERVATION);
        memory.setSourceRef(sourceRef);
        memory.setConfidence(0.7f);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setLifecycleStage("running");
        memory.setMetadata(Map.of("phase", "1"));
        return memory;
    }

    private LongTermMemory newLongTermMemory(String sourceRef) {
        var memory = new LongTermMemory();
        memory.setScopeType(MemoryScopeType.TESTING_PATTERN);
        memory.setSummary("Tenant context is a reusable order testing pattern");
        memory.setContent("Order tests should prepare tenant context first.");
        memory.setFullContent("Phase 1 stores this only as a long-term memory record.");
        memory.setTags(List.of("order", "tenant"));
        memory.setSourceType(MemorySourceType.MANUAL);
        memory.setSourceRef(sourceRef);
        memory.setConfidence(0.8f);
        memory.setImportance(0.75f);
        memory.setHitCount(0);
        memory.setSuccessContribution(0.3f);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(Map.of("phase", "1"));
        memory.setEmbedding(testEmbedding(500.0f));
        return memory;
    }

    private Report newReport(String taskId) {
        var report = new Report();
        report.setTaskId(taskId);
        report.setSummary("Phase 1 persistence acceptance report.");
        report.setCaseCount(1);
        report.setPassCount(1);
        report.setFailCount(0);
        report.setWarningCount(0);
        report.setRiskSummary("No persistence risk.");
        report.setFindings(List.of(Map.of("type", "storage", "message", "records saved")));
        report.setSuggestions(List.of(Map.of("type", "next", "message", "implement behavior later")));
        return report;
    }

    private float[] testEmbedding(float divisor) {
        var embedding = new float[1024];
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] = (index + 1) / divisor;
        }
        return embedding;
    }
}
