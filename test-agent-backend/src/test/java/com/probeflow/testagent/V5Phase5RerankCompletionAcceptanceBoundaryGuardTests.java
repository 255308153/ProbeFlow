package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V5Phase5RerankCompletionAcceptanceBoundaryGuardTests {

    private static final Path BACKEND_ROOT = Path.of("").toAbsolutePath();
    private static final Path COMPLETION_DOC = BACKEND_ROOT.resolve("docs/v5-5-rerank-small-to-big-completion.md");

    @Test
    void chineseCompletionDocumentExplainsMainChainProfilesAndV5Boundary() throws Exception {
        var doc = Files.readString(COMPLETION_DOC);
        var readme = Files.readString(BACKEND_ROOT.resolve("README.md"));

        assertThat(readme)
            .contains("V5-5 Rerank Small-to-Big Completion")
            .contains("docs/v5-5-rerank-small-to-big-completion.md");
        assertThat(doc)
            .contains("multi-route candidates -> rerank -> Small-to-Big -> Unified Context")
            .contains("deterministic rerank 是默认路径")
            .contains("Cross Encoder / LLM rerank 是显式 profile")
            .contains("DeepSeek V4 Pro")
            .contains("默认测试不能依赖真实 LLM")
            .contains("fake LLM 不能作为 internal alpha 唯一验收")
            .contains("V5 完成边界")
            .contains("V5-1 到 V5-5 形成 RAG / Memory / Context Engine 闭环")
            .contains("V6-1 才进入本地项目导入")
            .contains("V5-5 不改变 V5-4 route recall")
            .contains("Memory Refinery 仍然是长期记忆写入口");
    }

    @Test
    void defaultCompletionPathDoesNotEnableRealDeepSeekCrossEncoderOrExternalRerankService() throws Exception {
        var applicationYaml = Files.readString(BACKEND_ROOT.resolve("src/main/resources/application.yml"));
        var testYaml = Files.readString(BACKEND_ROOT.resolve("src/test/resources/application-test.yml"));
        var rerankSources = sourceText(BACKEND_ROOT.resolve("src/main/java/com/probeflow/testagent/rerank"));

        assertThat(applicationYaml)
            .contains("allow-real-providers: ${PROBEFLOW_LLM_ALLOW_REAL_PROVIDERS:false}")
            .contains("allowed-providers: ${PROBEFLOW_LLM_ALLOWED_PROVIDERS:fake}")
            .contains("enabled: ${PROBEFLOW_LLM_MANUAL_REAL_ENABLED:false}")
            .contains("enabled: ${PROBEFLOW_RERANK_CROSS_ENCODER_ENABLED:false}")
            .contains("provider: ${PROBEFLOW_RERANK_CROSS_ENCODER_PROVIDER:disabled}")
            .contains("enabled: ${PROBEFLOW_RERANK_LLM_ENABLED:false}")
            .contains("provider: ${PROBEFLOW_RERANK_LLM_PROVIDER:disabled}")
            .contains("model: ${PROBEFLOW_RERANK_LLM_MODEL:deepseek-v4-pro}");
        assertThat(testYaml)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake")
            .contains("enabled: false")
            .contains("cross-encoder:")
            .contains("provider: disabled")
            .contains("llm:")
            .contains("model: deepseek-v4-pro");
        assertThat(rerankSources)
            .contains("DeterministicRerankEngine")
            .doesNotContain("WebClient")
            .doesNotContain("RestClient")
            .doesNotContain("HttpClient")
            .doesNotContain("URLConnection")
            .doesNotContain("Socket")
            .doesNotContain("setBearerAuth")
            .doesNotContain("Authorization");
    }

    @Test
    void completionBoundaryDoesNotAddExternalMemoryGraphDatabasesOrReplaceV54AndMemoryWriteSeams()
        throws Exception {
        var productionSurface = Files.readString(BACKEND_ROOT.resolve("pom.xml"))
            + "\n"
            + sourceText(BACKEND_ROOT.resolve("src/main/resources"))
            + "\n"
            + sourceText(BACKEND_ROOT.resolve("src/main/java"));
        var v5_4RecallSources = String.join("\n",
            sourceText(BACKEND_ROOT.resolve("src/main/java/com/probeflow/testagent/retrieval")),
            Files.readString(BACKEND_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/knowledge/KnowledgeRetrievalApplicationService.java"
            )),
            Files.readString(BACKEND_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/memory/LongTermMemoryRetrievalService.java"
            ))
        );
        var feedbackSource = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackApplicationService.java"
        ));
        var refinerySource = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/MemoryRefineryService.java"
        ));
        var memoryRetrievalSource = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/LongTermMemoryRetrievalService.java"
        ));

        assertThat(presentTerms(productionSurface, List.of(
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
        assertThat(v5_4RecallSources)
            .contains("retrieveWithQueryVariants")
            .contains("graph_memory")
            .contains("routeEvidence")
            .doesNotContain("com.probeflow.testagent.rerank")
            .doesNotContain("CrossEncoder")
            .doesNotContain("Cross Encoder")
            .doesNotContain("LlmRerank")
            .doesNotContain("LLM Rerank")
            .doesNotContain("Small-to-Big");
        assertThat(feedbackSource)
            .contains("MemoryRefineryService")
            .doesNotContain("new LongTermMemory(");
        assertThat(refinerySource)
            .contains("new LongTermMemory()")
            .contains("longTermMemories.save(memory)");
        assertThat(memoryRetrievalSource)
            .contains("retrieveWithQueryVariants")
            .contains("graph_memory")
            .contains("routeEvidence")
            .doesNotContain("new LongTermMemory(")
            .doesNotContain("MemoryRefineryService");
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
