package com.probeflow.testagent.memorygraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MemoryGraphAcceptanceBoundaryIssue08Tests {

    private static final Path BACKEND_ROOT = Path.of("").toAbsolutePath();
    private static final Path REPO_ROOT = BACKEND_ROOT.getParent();
    private static final Path V5_3_DOC = REPO_ROOT.resolve("测试Agent设计文档/V5-3-Memory-Entity-Graph设计文档.md");

    @Test
    void productionDependenciesDoNotIncludeExternalMemoryOrGraphDatabases() throws Exception {
        var pom = Files.readString(BACKEND_ROOT.resolve("pom.xml"));
        var mainResources = sourceText(BACKEND_ROOT.resolve("src/main/resources"));

        assertThat(presentTerms(pom + "\n" + mainResources, List.of(
            "mem0",
            "vikingdb",
            "viking-db",
            "volcengine-viking",
            "com.volcengine",
            "org.neo4j",
            "neo4j-java-driver",
            "janusgraph",
            "arangodb",
            "tinkerpop"
        ))).isEmpty();
    }

    @Test
    void defaultConfigurationKeepsFakeDeterministicProviders() throws Exception {
        var applicationYaml = Files.readString(BACKEND_ROOT.resolve("src/main/resources/application.yml"));
        var testYaml = Files.readString(BACKEND_ROOT.resolve("src/test/resources/application-test.yml"));

        assertThat(applicationYaml)
            .contains("allow-real-providers: ${PROBEFLOW_LLM_ALLOW_REAL_PROVIDERS:false}")
            .contains("allowed-providers: ${PROBEFLOW_LLM_ALLOWED_PROVIDERS:fake}")
            .contains("mode: ${PROBEFLOW_MEMORY_FACT_EXTRACTOR_MODE:deterministic}")
            .contains("provider: ${PROBEFLOW_MEMORY_FACT_EXTRACTOR_PROVIDER:fake}")
            .contains("model: ${PROBEFLOW_MEMORY_FACT_EXTRACTOR_MODEL:fake-model}");
        assertThat(testYaml)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake")
            .contains("mode: deterministic")
            .contains("provider: fake")
            .contains("model: fake-model");
    }

    @Test
    void v5_3DoesNotImplementFutureRetrievalStages() throws Exception {
        var mainSources = sourceText(BACKEND_ROOT.resolve("src/main/java"));

        assertThat(presentTerms(mainSources, List.of(
            "QueryRewrite",
            "QueryRewriter",
            "MultiRecall",
            "MultiRouteRetrieval",
            "ReciprocalRankFusion",
            "CrossEncoder",
            "LlmRerank",
            "LlmReranker",
            "SmallToBig",
            "GraphRag"
        ))).isEmpty();
    }

    @Test
    void knowledgeRagAndMemoryGraphKeepSeparateResponsibilities() throws Exception {
        var knowledgeRetrieval = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/knowledge/KnowledgeRetrievalApplicationService.java"
        ));
        var knowledgeChunking = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/knowledge/KnowledgeChunkingService.java"
        ));
        var memoryGraphSources = sourceText(BACKEND_ROOT.resolve("src/main/java/com/probeflow/testagent/memorygraph"));

        assertThat(knowledgeRetrieval)
            .contains("chunks.findPgvectorCandidates")
            .doesNotContain("memorygraph")
            .doesNotContain("MemoryGraph");
        assertThat(knowledgeChunking)
            .doesNotContain("memorygraph")
            .doesNotContain("MemoryGraph");
        assertThat(memoryGraphSources)
            .doesNotContain("KnowledgeRetrievalApplicationService")
            .doesNotContain("KnowledgeChunk")
            .doesNotContain("KnowledgeDocument");
    }

    @Test
    void memoryGraphProjectsFromActiveLongTermMemoryAndDoesNotWriteLongTermMemory() throws Exception {
        var projection = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memorygraph/MemoryGraphProjectionService.java"
        ));
        var memoryGraphSources = sourceText(BACKEND_ROOT.resolve("src/main/java/com/probeflow/testagent/memorygraph"));
        var refinery = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/MemoryRefineryService.java"
        ));

        assertThat(projection)
            .contains("findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE)")
            .contains("memory.getStatus() != MemoryStatus.ACTIVE");
        assertThat(memoryGraphSources)
            .doesNotContain("new LongTermMemory(")
            .doesNotContain("longTermMemories.save(")
            .doesNotContain("MemoryRefineryService");
        assertThat(refinery)
            .contains("new LongTermMemory()")
            .contains("longTermMemories.save(memory)");
    }

    @Test
    void chineseDesignDocumentExplainsV5_3BoundariesAndInterviewStory() throws Exception {
        var doc = Files.readString(V5_3_DOC);

        assertThat(doc)
            .contains("Memory Graph 是 Long-term Memory 的 projection，不是新的长期记忆 source of truth")
            .contains("长期记忆写入仍然只有一个入口：Memory Refinery")
            .contains("Memory Graph 和 Knowledge RAG 是两个不同来源、不同职责的上下文系统")
            .contains("ProbeFlow 借鉴 mem0 的思想")
            .contains("不引入 mem0 SDK，不依赖 VikingDB，不接 Neo4j")
            .contains("Memory Candidate")
            .contains("Memory Fact")
            .contains("Long-term Memory")
            .contains("Memory Entity Graph")
            .contains("Graph-expanded Unified Context")
            .contains("Query Rewrite")
            .contains("Small-to-Big");
    }

    private String sourceText(Path sourceRoot) throws IOException {
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    var file = path.toString();
                    return file.endsWith(".java") || file.endsWith(".xml") || file.endsWith(".yml") || file.endsWith(".sql");
                })
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
