package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.memory.LongTermMemoryQuery;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalService;
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemoryRefineryService;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VectorWriteProfileMetadataIssue03Tests {

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Autowired
    private MemoryRefineryService memoryRefinery;

    @Autowired
    private LongTermMemoryRetrievalService memoryRetrieval;

    @Autowired
    private LongTermMemoryRepository memories;

    @Autowired
    private MutableProfileEmbeddingService embeddingService;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void resetEmbeddingProfile() {
        embeddingService.reset();
    }

    @Test
    void knowledgeChunksRecordEmbeddingProfileAndNewRevisionsUseCurrentProfile() {
        embeddingService.useProfile("knowledge-profile-a", "bge-a");
        var first = knowledgeIngest.ingest(knowledgeRequest(
            "Payment retry guide",
            "POST /api/orders/{orderId}/pay retries gateway timeout after checking order state."
        ));

        embeddingService.useProfile("knowledge-profile-b", "bge-b");
        var second = knowledgeIngest.ingest(knowledgeRequest(
            "Payment retry guide",
            "POST /api/orders/{orderId}/pay retries gateway timeout only after refreshing payment status."
        ));

        entityManager.flush();
        entityManager.clear();

        var firstChunks = chunks.findByDocumentRevisionIdOrderByChunkOrderAsc(first.documentRevisionId());
        var secondChunks = chunks.findByDocumentRevisionIdOrderByChunkOrderAsc(second.documentRevisionId());

        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(firstChunks).isNotEmpty();
        assertThat(firstChunks).allSatisfy(chunk -> {
            assertThat(chunk.getChunkStatus()).isEqualTo(ChunkStatus.SUPERSEDED);
            assertProfile(chunk.getMetadata(), "knowledge-profile-a", "bge-a");
        });
        assertThat(secondChunks).isNotEmpty();
        assertThat(secondChunks).allSatisfy(chunk -> {
            assertThat(chunk.getChunkStatus()).isEqualTo(ChunkStatus.ACTIVE);
            assertProfile(chunk.getMetadata(), "knowledge-profile-b", "bge-b");
        });
    }

    @Test
    void memoryWritesAndMergesRecordCurrentProfileAndReembedMergedSummaryContent() {
        embeddingService.useProfile("memory-profile-a", "bge-a");
        var first = memoryRefinery.refine(memoryRequest(
            "Gateway timeout requires shorter retry windows",
            "Retry gateway timeouts with a tighter window.",
            "execution-merge-profile-1"
        ));
        var firstDocumentInput = embeddingService.documentInputs().getLast();

        embeddingService.useProfile("memory-profile-b", "bge-b");
        var second = memoryRefinery.refine(memoryRequest(
            "Gateway timeout requires shorter retry windows",
            "Retry gateway timeouts with a tighter assertion and retry window.",
            "execution-merge-profile-2"
        ));

        assertThat(second.accepted()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.memory().memoryId()).isEqualTo(first.memory().memoryId());
        assertProfile(first.memory().metadata(), "memory-profile-a", "bge-a");
        assertProfile(second.memory().metadata(), "memory-profile-b", "bge-b");
        assertThat(embeddingService.documentInputs().getLast())
            .contains(second.memory().summary())
            .contains(second.memory().content())
            .contains(second.memory().fullContent())
            .contains("Execution showed GW_TIMEOUT on POST /api/orders/{orderId}/pay.")
            .isNotEqualTo(firstDocumentInput);
    }

    @Test
    void retrievalMarksProfileMismatchAsReindexRequiredForKnowledgeAndMemory() {
        embeddingService.useProfile("stored-profile", "bge-stored");
        knowledgeIngest.ingest(knowledgeRequest(
            "Payment timeout guide",
            "POST /api/orders/{orderId}/pay handles GW_TIMEOUT with a retry window."
        ));
        memoryRefinery.refine(memoryRequest(
            "Payment timeout retry guidance",
            "Retry GW_TIMEOUT with a tighter assertion window.",
            "execution-profile-mismatch"
        ));

        embeddingService.useProfile("current-profile", "bge-current");

        var knowledgeResult = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "POST /api/orders/{orderId}/pay GW_TIMEOUT retry window",
            "order-platform",
            "payment",
            "/api/orders/{orderId}/pay",
            "POST",
            "order",
            DocumentType.ERROR_CODE_GUIDE,
            "failure_analysis",
            List.of("payment"),
            3,
            600
        ));
        var memoryResult = memoryRetrieval.retrieve(new LongTermMemoryQuery(
            "failure_analysis",
            "GW_TIMEOUT retry window",
            "order-platform",
            "payment",
            "/api/orders/{orderId}/pay",
            "GW_TIMEOUT",
            List.of("payment"),
            List.of(MemoryScopeType.FAILURE_PATTERN),
            3,
            600
        ));

        assertThat(knowledgeResult.hits()).isNotEmpty();
        assertThat(knowledgeResult.hits().getFirst().metadata())
            .containsEntry(EmbeddingProfileMetadata.EMBEDDING_PROFILE_MISMATCH, true)
            .containsEntry(EmbeddingProfileMetadata.REINDEX_REQUIRED, true)
            .containsEntry(EmbeddingProfileMetadata.REINDEX_REASON, "embedding-profile-mismatch");
        assertThat(knowledgeResult.hits().getFirst().matchReasons()).contains("reindex-required");
        assertThat(knowledgeResult.hits().getFirst().lowConfidence()).isTrue();
        assertProfile(knowledgeResult.hits().getFirst().metadata(), "stored-profile", "bge-stored");
        assertCurrentProfile(knowledgeResult.hits().getFirst().metadata(), "current-profile", "bge-current");

        assertThat(memoryResult.hits()).isNotEmpty();
        assertThat(memoryResult.hits().getFirst().metadata())
            .containsEntry(EmbeddingProfileMetadata.EMBEDDING_PROFILE_MISMATCH, true)
            .containsEntry(EmbeddingProfileMetadata.REINDEX_REQUIRED, true)
            .containsEntry(EmbeddingProfileMetadata.REINDEX_REASON, "embedding-profile-mismatch");
        assertThat(memoryResult.hits().getFirst().matchReasons()).contains("reindex-required");
        assertThat(memoryResult.hits().getFirst().lowConfidence()).isTrue();
        assertProfile(memoryResult.hits().getFirst().metadata(), "stored-profile", "bge-stored");
        assertCurrentProfile(memoryResult.hits().getFirst().metadata(), "current-profile", "bge-current");
    }

    @Test
    void memoryWriteRejectsEmbeddingDimensionMismatch() {
        embeddingService.useVectorDimension(8);

        assertThatThrownBy(() -> memoryRefinery.refine(memoryRequest(
            "Gateway timeout retry guidance",
            "Retry gateway timeouts with a smaller assertion window.",
            "execution-dimension-mismatch"
        )))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("embedding dimension mismatch")
            .hasMessageContaining("expected 1024")
            .hasMessageContaining("but was 8")
            .extracting("failureCode")
            .isEqualTo(EmbeddingFailureCode.DIMENSION_MISMATCH);
    }

    @SuppressWarnings("unchecked")
    private void assertProfile(Map<String, Object> metadata, String profileId, String model) {
        var profile = (Map<String, Object>) metadata.get(EmbeddingProfileMetadata.EMBEDDING_PROFILE);
        assertThat(profile)
            .containsEntry("profileId", profileId)
            .containsEntry("providerMode", "REAL")
            .containsEntry("model", model)
            .containsEntry("dimension", 1024);
    }

    @SuppressWarnings("unchecked")
    private void assertCurrentProfile(Map<String, Object> metadata, String profileId, String model) {
        var profile = (Map<String, Object>) metadata.get(EmbeddingProfileMetadata.CURRENT_EMBEDDING_PROFILE);
        assertThat(profile)
            .containsEntry("profileId", profileId)
            .containsEntry("providerMode", "REAL")
            .containsEntry("model", model)
            .containsEntry("dimension", 1024);
    }

    private KnowledgeIngestRequest knowledgeRequest(String title, String content) {
        return new KnowledgeIngestRequest(
            title,
            KnowledgeContentFormat.PLAIN_TEXT,
            content,
            DocumentSourceType.WIKI,
            "wiki/payment-timeout-guide.md",
            DocumentType.ERROR_CODE_GUIDE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "timeout"),
            List.of("failure_analysis"),
            Map.of(
                "apiPathHints", List.of("/api/orders/{orderId}/pay"),
                "httpMethodHints", List.of("POST"),
                "errorCodeHints", List.of("GW_TIMEOUT")
            )
        );
    }

    private MemoryCandidateRequest memoryRequest(String summary, String content, String sourceRef) {
        return new MemoryCandidateRequest(
            summary,
            content,
            MemorySourceType.EXECUTION_RESULT,
            sourceRef,
            "task-v5-1-03",
            List.of("payment", "timeout"),
            0.88f,
            "Execution showed GW_TIMEOUT on POST /api/orders/{orderId}/pay.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "GW_TIMEOUT"
            )
        );
    }

    @TestConfiguration
    static class MutableEmbeddingConfig {

        @Bean
        @Primary
        MutableProfileEmbeddingService mutableProfileEmbeddingService() {
            return new MutableProfileEmbeddingService();
        }
    }

    static class MutableProfileEmbeddingService implements EmbeddingService {

        private String profileId = "test-profile";
        private String model = "test-model";
        private int vectorDimension = 1024;
        private final List<String> documentInputs = new ArrayList<>();

        void reset() {
            profileId = "test-profile";
            model = "test-model";
            vectorDimension = 1024;
            documentInputs.clear();
        }

        void useProfile(String profileId, String model) {
            this.profileId = profileId;
            this.model = model;
            this.vectorDimension = 1024;
        }

        void useVectorDimension(int vectorDimension) {
            this.vectorDimension = vectorDimension;
        }

        List<String> documentInputs() {
            return documentInputs;
        }

        @Override
        public float[] embedDocument(String text) {
            documentInputs.add(text);
            return vector("document", text, vectorDimension);
        }

        @Override
        public float[] embedQuery(String text) {
            return vector("query", text, dimensions());
        }

        @Override
        public int dimensions() {
            return 1024;
        }

        @Override
        public EmbeddingProfile profile() {
            return new EmbeddingProfile(
                EmbeddingProviderMode.REAL,
                profileId,
                model,
                dimensions(),
                "test-profile",
                "query: ",
                "document: "
            );
        }

        private float[] vector(String usage, String text, int dimension) {
            var vector = new float[dimension];
            var seed = (usage + "\n" + text).getBytes(StandardCharsets.UTF_8);
            for (var index = 0; index < dimension; index++) {
                vector[index] = ((hash(seed, index) & 0xff) + 1) / 256.0f;
            }
            return vector;
        }

        private byte hash(byte[] seed, int index) {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                digest.update(seed);
                digest.update((byte) index);
                return digest.digest()[0];
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
