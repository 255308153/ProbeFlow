package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.knowledge.EmbeddingProfile;
import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
import com.probeflow.testagent.knowledge.EmbeddingService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryPgvectorIssue05Tests {

    @Mock
    private LongTermMemoryRepository longTermMemories;

    @Mock
    private EmbeddingService embeddingService;

    @Test
    void retrievalUsesPgvectorCandidatesWithStructuredFiltersAndExplainableHitUpdates() {
        var profile = EmbeddingProfile.fake(1024);
        when(embeddingService.dimensions()).thenReturn(1024);
        when(embeddingService.profile()).thenReturn(profile);
        when(embeddingService.embedQuery("GW_TIMEOUT payment retry guidance")).thenReturn(vector(1.0f));

        var memory = memory("mem-1", profile);
        when(longTermMemories.findPgvectorCandidates(
                eq(List.of(MemoryScopeType.FAILURE_PATTERN)),
                eq("order-platform"),
                eq("payment"),
                eq("/api/pay"),
                eq("GW_TIMEOUT"),
                eq(List.of("payment", "timeout")),
                eq("failure_analysis"),
                any(float[].class),
                anyInt()
            ))
            .thenReturn(List.of(new LongTermMemoryVectorCandidate(memory, 0.03d, 1)));
        when(longTermMemories.findById("mem-1")).thenReturn(Optional.of(memory));
        when(longTermMemories.save(memory)).thenReturn(memory);

        var result = new LongTermMemoryRetrievalService(longTermMemories, embeddingService)
            .retrieve(new LongTermMemoryQuery(
                "failure_analysis",
                "GW_TIMEOUT payment retry guidance",
                "order-platform",
                "payment",
                "/api/pay",
                "GW_TIMEOUT",
                List.of("timeout", "payment"),
                List.of(MemoryScopeType.FAILURE_PATTERN),
                2,
                120
            ));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.totalCandidates()).isEqualTo(1);
        assertThat(result.totalTokens()).isLessThanOrEqualTo(120);

        var hit = result.hits().getFirst();
        assertThat(hit.memoryId()).isEqualTo("mem-1");
        assertThat(hit.hitCount()).isEqualTo(3);
        assertThat(hit.lastUsedAt()).isNotNull();
        assertThat(Instant.parse(hit.metadata().get("selectedAt").toString())).isBeforeOrEqualTo(Instant.now());
        assertThat(hit.metadata())
            .containsEntry("retrievalChannel", "pgvector")
            .containsEntry("vectorDistance", 0.03d)
            .containsEntry("candidateRank", 1)
            .containsEntry(EmbeddingProfileMetadata.REINDEX_REQUIRED, false);
        assertThat(hit.metadata()).containsKey(EmbeddingProfileMetadata.EMBEDDING_PROFILE);
        assertThat(hit.componentScores())
            .containsKeys(
                "vector",
                "structure",
                "tag",
                "importance",
                "confidence",
                "successContribution",
                "hitCount",
                "freshness",
                "stageFit"
            );
        assertThat(hit.componentScores().get("vector")).isGreaterThan(0.0d);
        assertThat(hit.matchReasons()).contains("semantic-match", "structure-match", "tag-match", "stage-fit");
        assertThat(hit.lowConfidence()).isFalse();

        verify(longTermMemories, never()).findAllByStatusOrderByCreatedAtAscMemoryIdAsc(any());
    }

    private LongTermMemory memory(String memoryId, EmbeddingProfile profile) {
        var memory = new LongTermMemory();
        memory.setMemoryId(memoryId);
        memory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        memory.setSummary("GW_TIMEOUT payment retry guidance");
        memory.setContent("Retry payment gateway timeout with a narrower assertion window.");
        memory.setFullContent("Observed GW_TIMEOUT on /api/pay; retry narrowed the assertion window.");
        memory.setTags(List.of("payment", "timeout"));
        memory.setSourceType(MemorySourceType.EXECUTION_RESULT);
        memory.setSourceRef("task-123");
        memory.setConfidence(0.91f);
        memory.setImportance(0.86f);
        memory.setSuccessContribution(0.77f);
        memory.setHitCount(2);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(EmbeddingProfileMetadata.withProfile(Map.of(
            "systemName", "order-platform",
            "module", "payment",
            "apiPath", "/api/pay",
            "errorCode", "GW_TIMEOUT"
        ), profile));
        memory.setEmbedding(vector(1.0f));
        memory.onCreate();
        return memory;
    }

    private float[] vector(float firstValue) {
        var vector = new float[1024];
        vector[0] = firstValue;
        return vector;
    }
}
