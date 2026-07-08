package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.knowledge.EmbeddingProfile;
import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalHit;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.task.TaskRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnifiedContextSemanticEvidenceIssue06Tests {

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
    void contextCitationsExposeKnowledgeAndMemorySemanticEvidenceForReports() {
        var profileMetadata = EmbeddingProfileMetadata.toMetadata(EmbeddingProfile.fake(1024));
        var knowledgeMetadata = Map.<String, Object>of(
            EmbeddingProfileMetadata.EMBEDDING_PROFILE, profileMetadata,
            "retrievalChannel", "pgvector",
            "vectorDistance", 0.04d,
            "candidateRank", 1
        );
        var memoryMetadata = Map.<String, Object>of(
            EmbeddingProfileMetadata.EMBEDDING_PROFILE, profileMetadata,
            "retrievalChannel", "pgvector",
            "vectorDistance", 0.08d,
            "candidateRank", 2,
            "errorCode", "PAY_401"
        );

        when(knowledgeRetrieval.retrieve(any(KnowledgeQuery.class))).thenReturn(knowledgeResult(knowledgeMetadata));
        when(longTermMemoryRetrieval.retrieve(any(LongTermMemoryQuery.class))).thenReturn(memoryResult(memoryMetadata));

        var bundle = builder().build(new UnifiedContextQuery(
            null,
            null,
            null,
            apiSpec(),
            "failure_analysis",
            "payment auth failure PAY_401",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "auth"),
            400
        ));

        var knowledgeCitation = bundle.citations().stream()
            .filter(citation -> "knowledge_chunk".equals(citation.citationType()))
            .findFirst()
            .orElseThrow();
        assertThat(knowledgeCitation.evidence())
            .containsEntry("retrievalChannel", "pgvector")
            .containsEntry("vectorDistance", 0.04d)
            .containsEntry("candidateRank", 1)
            .containsEntry("lowConfidence", true)
            .containsEntry("documentRevisionId", "rev-06");
        assertThat(knowledgeCitation.evidence().get(EmbeddingProfileMetadata.EMBEDDING_PROFILE))
            .isEqualTo(profileMetadata);
        assertThat(knowledgeCitation.evidence().get("matchReasons"))
            .asList()
            .contains("semantic-match", "api-path");
        assertThat(knowledgeCitation.evidence().get("componentScores"))
            .isEqualTo(Map.of("vector", 0.96d, "structure", 1.0d));

        var memoryCitation = bundle.citations().stream()
            .filter(citation -> "long_term_memory".equals(citation.citationType()))
            .findFirst()
            .orElseThrow();
        assertThat(memoryCitation.evidence())
            .containsEntry("retrievalChannel", "pgvector")
            .containsEntry("vectorDistance", 0.08d)
            .containsEntry("candidateRank", 2)
            .containsEntry("lowConfidence", true)
            .containsEntry("memoryScopeType", MemoryScopeType.FAILURE_PATTERN.name());
        assertThat(memoryCitation.evidence().get(EmbeddingProfileMetadata.EMBEDDING_PROFILE))
            .isEqualTo(profileMetadata);
        assertThat(memoryCitation.evidence().get("matchReasons"))
            .asList()
            .contains("semantic-match", "stage-fit");
        assertThat(memoryCitation.evidence().get("componentScores"))
            .isEqualTo(Map.of("vector", 0.92d, "confidence", 0.58d));

        assertThat(bundle.coverage().hasKnowledgeContext()).isTrue();
        assertThat(bundle.coverage().hasLongTermMemory()).isTrue();
        assertThat(bundle.coverage().hasTaskMemory()).isFalse();
        assertThat(bundle.coverage().hasSessionContext()).isFalse();
        assertThat(bundle.coverage().lowConfidence()).isTrue();
        assertThat(bundle.budget().totalEstimatedTokens()).isLessThanOrEqualTo(bundle.budget().requestedTokenBudget());
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

    private KnowledgeRetrievalResult knowledgeResult(Map<String, Object> metadata) {
        var hit = new KnowledgeRetrievalHit(
            "chunk-06",
            "doc-06",
            "rev-06",
            "PAY_401 payment auth guide",
            "PAY_401 means payment auth should check tenant bootstrap.",
            "wiki/payment-auth.md",
            DocumentType.ERROR_CODE_GUIDE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "auth"),
            List.of("failure_analysis"),
            metadata,
            12,
            0.91d,
            Map.of("vector", 0.96d, "structure", 1.0d),
            List.of("semantic-match", "api-path"),
            true
        );
        var entry = new KnowledgeContextEntry(
            hit.chunkId(),
            hit.documentId(),
            hit.documentRevisionId(),
            hit.chunkTitle(),
            hit.score(),
            "error_code_guide",
            hit.sourceRef(),
            metadata,
            hit.matchReasons(),
            hit.lowConfidence()
        );
        var context = new KnowledgeContext(
            List.of(),
            List.of(),
            List.of(),
            List.of(entry),
            List.of(),
            List.of(),
            List.of(entry),
            true,
            false
        );
        return new KnowledgeRetrievalResult("payment auth failure PAY_401", List.of(hit), context, 1.0d, 1, 12, true);
    }

    private LongTermMemoryRetrievalResult memoryResult(Map<String, Object> metadata) {
        var hit = new LongTermMemoryRetrievalHit(
            "memory-06",
            MemoryScopeType.FAILURE_PATTERN,
            "PAY_401 tenant bootstrap pattern",
            "Historical PAY_401 failures were fixed by bootstrapping tenant auth context.",
            "Historical PAY_401 failures were fixed by bootstrapping tenant auth context before payment.",
            List.of("payment", "auth"),
            MemorySourceType.OBSERVATION,
            "task-2026-issue-06",
            0.58f,
            0.7f,
            0.6f,
            3,
            null,
            metadata,
            14,
            0.82d,
            Map.of("vector", 0.92d, "confidence", 0.58d),
            List.of("semantic-match", "stage-fit"),
            true
        );
        return new LongTermMemoryRetrievalResult(List.of(hit), 1, 14);
    }

    private ApiSpec apiSpec() {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId("api-issue-06");
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/api/orders/{orderId}/pay");
        apiSpec.setSummary("Pay order");
        apiSpec.setDescription("Pay an order by id.");
        apiSpec.setOperationId("payOrder");
        apiSpec.setParameters(Map.of());
        apiSpec.setConstraints(Map.of());
        apiSpec.setAuth(Map.of());
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("orders-openapi.yaml");
        return apiSpec;
    }
}
