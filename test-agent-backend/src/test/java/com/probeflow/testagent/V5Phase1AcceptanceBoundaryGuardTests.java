package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.knowledge.EmbeddingException;
import com.probeflow.testagent.knowledge.EmbeddingFailureCode;
import com.probeflow.testagent.knowledge.EmbeddingProviderMode;
import com.probeflow.testagent.knowledge.EmbeddingService;
import com.probeflow.testagent.knowledge.FakeEmbeddingService;
import com.probeflow.testagent.knowledge.ManualRealEmbeddingClientException;
import com.probeflow.testagent.knowledge.ManualRealEmbeddingProvider;
import com.probeflow.testagent.knowledge.ManualRealEmbeddingProviderConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class V5Phase1AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void defaultEmbeddingProviderIsFakeAndDoesNotRequireRealEmbeddingConfiguration() throws Exception {
        var applicationYaml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/application.yml"));
        var profile = embeddingService.profile();

        assertThat(embeddingService).isInstanceOf(FakeEmbeddingService.class);
        assertThat(profile.providerMode()).isEqualTo(EmbeddingProviderMode.FAKE);
        assertThat(profile.profileId()).isEqualTo("fake-default");
        assertThat(profile.model()).isEqualTo("deterministic-sha256-v1");
        assertThat(profile.dimension()).isEqualTo(1024);
        assertThat(applicationYaml)
            .contains("enabled: ${PROBEFLOW_EMBEDDING_MANUAL_REAL_ENABLED:false}")
            .contains("key: ${PROBEFLOW_EMBEDDING_MANUAL_REAL_KEY:}")
            .contains("endpoint: ${PROBEFLOW_EMBEDDING_MANUAL_REAL_ENDPOINT:}");
        assertThat(applicationContext.getBeansOfType(ManualRealEmbeddingProvider.class)).isEmpty();
    }

    @Test
    void manualRealEmbeddingFailureIsExplicitAndDoesNotSilentlyFallbackToFake() {
        var provider = new ManualRealEmbeddingProvider(
            new ManualRealEmbeddingProviderConfig(
                "manual-real",
                "",
                "",
                "",
                0,
                0,
                0,
                "query: ",
                "",
                0,
                ""
            ),
            request -> {
                throw new ManualRealEmbeddingClientException(
                    EmbeddingFailureCode.REMOTE_ERROR,
                    "should not be reached"
                );
            }
        );

        assertThatThrownBy(() -> provider.embedQuery("payment auth failure"))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("Manual real embedding config missing required fields")
            .hasMessageContaining("endpoint", "key", "model", "dimension", "timeoutMs", "maxInputTokens", "batchSize", "failurePolicy")
            .extracting("failureCode")
            .isEqualTo(EmbeddingFailureCode.MISSING_CONFIG);
    }

    @Test
    void postgresMigrationsKeepPgvectorExtensionVectorColumnsAndHnswIndexes() throws Exception {
        var pgvectorMigration = Files.readString(PROJECT_ROOT.resolve(
            "src/main/resources/db/migration/postgresql/V2__enable_pgvector.sql"
        ));
        var knowledgeMigration = Files.readString(PROJECT_ROOT.resolve(
            "src/main/resources/db/migration/postgresql/V7__knowledge_document_revision_chunk.sql"
        ));
        var memoryMigration = Files.readString(PROJECT_ROOT.resolve(
            "src/main/resources/db/migration/postgresql/V8__memory_item_scoped_storage.sql"
        ));

        assertThat(pgvectorMigration).contains("CREATE EXTENSION IF NOT EXISTS vector");
        assertThat(knowledgeMigration)
            .contains("embedding vector(1024) NOT NULL")
            .contains("idx_knowledge_chunk_embedding")
            .contains("USING hnsw (embedding vector_cosine_ops)");
        assertThat(memoryMigration)
            .contains("embedding vector(1024) NOT NULL")
            .contains("idx_long_term_memory_embedding")
            .contains("USING hnsw (embedding vector_cosine_ops)");
    }

    @Test
    void knowledgeAndLongTermMemoryRetrievalUsePgvectorCandidatePaths() throws Exception {
        var knowledgeRepository = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/knowledge/KnowledgeChunkVectorRepository.java"
        ));
        var knowledgeRepositoryImpl = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/knowledge/KnowledgeChunkVectorRepositoryImpl.java"
        ));
        var knowledgeService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/knowledge/KnowledgeRetrievalApplicationService.java"
        ));
        var memoryRepository = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/LongTermMemoryVectorRepository.java"
        ));
        var memoryRepositoryImpl = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/LongTermMemoryVectorRepositoryImpl.java"
        ));
        var memoryService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/LongTermMemoryRetrievalService.java"
        ));

        assertThat(knowledgeRepository).contains("findPgvectorCandidates");
        assertThat(knowledgeRepositoryImpl)
            .contains("chunk.embedding <=> cast(:queryEmbedding as vector)")
            .contains("order by vector_distance asc")
            .contains("new KnowledgeVectorCandidate");
        assertThat(knowledgeService)
            .contains("embeddingService.embedQuery")
            .contains("chunks.findPgvectorCandidates")
            .contains("\"retrievalChannel\", \"pgvector\"")
            .contains("\"vectorDistance\"")
            .contains("\"candidateRank\"");

        assertThat(memoryRepository).contains("findPgvectorCandidates");
        assertThat(memoryRepositoryImpl)
            .contains("memory.embedding <=> cast(:queryEmbedding as vector)")
            .contains("order by vector_distance asc")
            .contains("new LongTermMemoryVectorCandidate");
        assertThat(memoryService)
            .contains("embeddingService.embedQuery")
            .contains("longTermMemories.findPgvectorCandidates")
            .contains("\"retrievalChannel\", \"pgvector\"")
            .contains("\"vectorDistance\"")
            .contains("\"candidateRank\"");
    }

    @Test
    void v5Phase1DoesNotAddMem0VikingDbOrLaterRagProImplementations() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phaseOneSources = String.join("\n",
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/knowledge/EmbeddingService.java")),
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/knowledge/FakeEmbeddingService.java")),
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/knowledge/ManualRealEmbeddingProvider.java")),
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/knowledge/KnowledgeChunkVectorRepository.java")),
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/knowledge/KnowledgeChunkVectorRepositoryImpl.java")),
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/knowledge/KnowledgeRetrievalApplicationService.java")),
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/memory/LongTermMemoryVectorRepository.java")),
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/memory/LongTermMemoryVectorRepositoryImpl.java")),
            Files.readString(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/memory/LongTermMemoryRetrievalService.java"))
        );

        assertThat(presentTerms(pom + "\n" + phaseOneSources, List.of(
            "mem0",
            "vikingdb",
            "viking-db",
            "volcengine-viking",
            "com.volcengine"
        ))).isEmpty();

        assertThat(presentTerms(phaseOneSources, List.of(
            "MultiRecall",
            "MultiRouteRetrieval",
            "ReciprocalRankFusion",
            "CrossEncoder",
            "LlmRerank",
            "LlmReranker",
            "SmallToBig",
            "MemoryFactExtraction",
            "MemoryEntityGraph"
        ))).isEmpty();
    }

    private String sourceText(Path sourceRoot) throws Exception {
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::readUnchecked)
                .collect(Collectors.joining("\n"));
        }
    }

    private List<String> presentTerms(String text, List<String> terms) {
        var normalized = text.toLowerCase(Locale.ROOT);
        return terms.stream()
            .filter(term -> normalized.contains(term.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
