package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.task.TaskRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnifiedContextGraphEvidenceIssue07Tests {

    @Mock
    private TaskRepository tasks;

    @Mock
    private ApiSpecRepository apiSpecs;

    @Mock
    private SessionMemoryService sessionMemoryService;

    @Mock
    private TaskMemoryService taskMemoryService;

    @Mock
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    @Mock
    private LongTermMemoryRetrievalService longTermMemoryRetrieval;

    @Mock
    private MemoryUsageRecordingService memoryUsageRecording;

    @Test
    void unifiedContextCarriesGraphExpandedMemoryEvidenceIntoCitationsAndBudget() {
        when(knowledgeRetrieval.retrieve(any(KnowledgeQuery.class))).thenReturn(emptyKnowledge());
        when(longTermMemoryRetrieval.retrieve(any(LongTermMemoryQuery.class))).thenReturn(new LongTermMemoryRetrievalResult(
            List.of(
                semanticHit("memory-semantic-07", 8, 0.92d),
                graphHit("memory-graph-07", 9, 0.71d)
            ),
            2,
            17
        ));

        var bundle = builder().build(new UnifiedContextQuery(
            null,
            null,
            null,
            apiSpec(),
            "failure_analysis",
            "why does PAY_401 happen on charge",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "auth"),
            160
        ));

        assertThat(bundle.longTermMemoryContext().hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .containsExactly("memory-semantic-07", "memory-graph-07");
        assertThat(bundle.budget().longTermMemoryTokens()).isEqualTo(17);
        assertThat(bundle.budget().totalEstimatedTokens()).isLessThanOrEqualTo(bundle.budget().requestedTokenBudget());

        var graphCitation = bundle.citations().stream()
            .filter(citation -> "memory-graph-07".equals(citation.sourceId()))
            .findFirst()
            .orElseThrow();
        assertThat(graphCitation.citationType()).isEqualTo("long_term_memory");
        assertThat(graphCitation.evidence())
            .containsEntry("retrievalChannel", "graph")
            .containsEntry("graphMatchReason", "graph-related-error-code")
            .containsEntry("graphRelationConfidence", 0.83d)
            .containsEntry("lowConfidence", false)
            .containsEntry("memoryScopeType", MemoryScopeType.TESTING_PATTERN.name());
        assertThat(graphCitation.evidence().get("matchReasons"))
            .asList()
            .containsExactly("graph-related-error-code");
        assertThat(graphCitation.evidence().get("graphRelationPath").toString())
            .contains("ERROR_OBSERVED_ON_API");
        assertThat(graphCitation.evidence().get("graphSourceMemoryIds"))
            .isEqualTo(List.of("memory-semantic-07", "memory-graph-07"));
        assertThat(graphCitation.evidence().get("graphSourceRefs"))
            .isEqualTo(List.of("src:memory-semantic-07", "src:memory-graph-07"));
        assertThat(graphCitation.evidence().get("graphFactFingerprints"))
            .isEqualTo(List.of("fact:semantic-07", "fact:graph-07"));
        assertThat(graphCitation.evidence().get("graphEvidenceSummaries"))
            .isEqualTo(List.of("PAY_401 was observed on the charge API."));
    }

    @Test
    void graphExpandedMemoryPrunedByUnifiedBudgetLeavesNoCitationOrBudgetTokens() {
        when(knowledgeRetrieval.retrieve(any(KnowledgeQuery.class))).thenReturn(emptyKnowledge());
        when(longTermMemoryRetrieval.retrieve(any(LongTermMemoryQuery.class))).thenReturn(new LongTermMemoryRetrievalResult(
            List.of(
                semanticHit("memory-semantic-kept-07", 10, 0.94d),
                graphHit("memory-graph-pruned-07", 20, 0.70d)
            ),
            2,
            30
        ));

        var bundle = builder().build(new UnifiedContextQuery(
            null,
            null,
            null,
            tinyApiSpec(),
            "failure_analysis",
            "PAY_401 charge",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment"),
            14
        ));

        assertThat(bundle.longTermMemoryContext().hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .containsExactly("memory-semantic-kept-07");
        assertThat(bundle.citations())
            .extracting(ContextCitation::sourceId)
            .doesNotContain("memory-graph-pruned-07");
        assertThat(bundle.budget().longTermMemoryTokens()).isEqualTo(10);
        assertThat(bundle.budget().totalEstimatedTokens()).isLessThanOrEqualTo(bundle.budget().requestedTokenBudget());
        assertThat(bundle.budget().originalEstimatedTokens()).isGreaterThan(bundle.budget().requestedTokenBudget());
        assertThat(bundle.budget().pruned()).isTrue();
    }

    private UnifiedContextBuilder builder() {
        return new UnifiedContextBuilder(
            tasks,
            apiSpecs,
            sessionMemoryService,
            taskMemoryService,
            knowledgeRetrieval,
            longTermMemoryRetrieval,
            memoryUsageRecording
        );
    }

    private KnowledgeRetrievalResult emptyKnowledge() {
        return new KnowledgeRetrievalResult(
            "PAY_401 charge",
            List.of(),
            new KnowledgeContext(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, false),
            0.0d,
            0,
            0,
            false
        );
    }

    private LongTermMemoryRetrievalHit semanticHit(String memoryId, int tokens, double score) {
        return new LongTermMemoryRetrievalHit(
            memoryId,
            MemoryScopeType.FAILURE_PATTERN,
            "PAY_401 seed memory",
            "PAY_401 seed memory says charge failures need tenant bootstrap.",
            "PAY_401 seed memory says charge failures need tenant bootstrap before POST /api/payments/charge.",
            List.of("payment", "auth"),
            MemorySourceType.MEMORY_REFINERY,
            "src:" + memoryId,
            0.88f,
            0.80f,
            0.65f,
            2,
            null,
            Map.of(
                "retrievalChannel", "pgvector",
                "errorCode", "PAY_401",
                "apiPath", "/api/payments/charge",
                "factFingerprint", "fact:semantic-07"
            ),
            tokens,
            score,
            Map.of("vector", 0.9d, "structure", 1.0d),
            List.of("semantic-match", "structure-match"),
            false
        );
    }

    private LongTermMemoryRetrievalHit graphHit(String memoryId, int tokens, double score) {
        return new LongTermMemoryRetrievalHit(
            memoryId,
            MemoryScopeType.TESTING_PATTERN,
            "Prepare payable order fixture",
            "Graph-expanded memory says prepare a payable order fixture before charge.",
            "Graph-expanded memory says prepare a payable order fixture before charge because PAY_401 can mean missing auth state.",
            List.of("payment", "fixture"),
            MemorySourceType.MEMORY_REFINERY,
            "src:" + memoryId,
            0.86f,
            0.76f,
            0.72f,
            1,
            null,
            Map.of(
                "retrievalChannel", "graph",
                "graphMatchReason", "graph-related-error-code",
                "graphRelationPath", List.of("ERROR_OBSERVED_ON_API", "ENTITY_RELATED_TO_MEMORY"),
                "graphRelationConfidence", 0.83d,
                "graphSourceMemoryIds", List.of("memory-semantic-07", "memory-graph-07"),
                "graphSourceRefs", List.of("src:memory-semantic-07", "src:memory-graph-07"),
                "graphFactFingerprints", List.of("fact:semantic-07", "fact:graph-07"),
                "graphEvidenceSummaries", List.of("PAY_401 was observed on the charge API.")
            ),
            tokens,
            score,
            Map.of("graphRelation", 0.42d, "confidence", 0.13d),
            List.of("graph-related-error-code"),
            false
        );
    }

    private ApiSpec apiSpec() {
        var apiSpec = tinyApiSpec();
        apiSpec.setSystemName("billing");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/api/payments/charge");
        apiSpec.setSummary("Charge payment");
        apiSpec.setDescription("Charge a payment order.");
        apiSpec.setOperationId("chargePayment");
        return apiSpec;
    }

    private ApiSpec tinyApiSpec() {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId("api-issue-07");
        apiSpec.setSystemName("billing");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/p");
        apiSpec.setSummary("P");
        apiSpec.setDescription(null);
        apiSpec.setOperationId("p");
        apiSpec.setParameters(Map.of());
        apiSpec.setConstraints(Map.of());
        apiSpec.setAuth(Map.of());
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("issue-07-openapi.yaml");
        return apiSpec;
    }
}
