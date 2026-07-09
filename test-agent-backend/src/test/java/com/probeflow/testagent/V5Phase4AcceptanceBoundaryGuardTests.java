package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.retrieval.DeterministicQueryRewriteService;
import com.probeflow.testagent.retrieval.QueryRewriteRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V5Phase4AcceptanceBoundaryGuardTests {

    private static final Path BACKEND_ROOT = Path.of("").toAbsolutePath();
    private static final Path V5_4_DOC = BACKEND_ROOT.resolve("docs/v5-4-query-rewrite-multi-route-retrieval.md");

    @Test
    void chineseDesignDocumentExplainsV5_4FlowEvidenceAndPhaseBoundaries() throws Exception {
        var doc = Files.readString(V5_4_DOC);
        var readme = Files.readString(BACKEND_ROOT.resolve("README.md"));

        assertThat(readme)
            .contains("V5-4 Query Rewrite And Multi-route Retrieval")
            .contains("docs/v5-4-query-rewrite-multi-route-retrieval.md");
        assertThat(doc)
            .contains("Deterministic Query Rewrite")
            .contains("QueryVariant")
            .contains("Retrieval Route")
            .contains("Route Fusion")
            .contains("Stage Profile")
            .contains("RoutingBudgetPolicy")
            .contains("Unified Context citation evidence")
            .contains("Memory Graph 只是 `graph_memory` 这一条 graph route 的来源之一")
            .contains("不让它替代 Knowledge RAG 或普通 Long-term Memory retrieval")
            .contains("V5-4 的关键词是 recall coverage 和 route explanation")
            .contains("V5-5 的关键词是 rerank 和 Small-to-Big")
            .contains("默认 Query Rewrite 由 `DeterministicQueryRewriteService` 完成")
            .contains("DeepSeek V4 Pro")
            .contains("fake LLM 不能作为 internal alpha 唯一验收");
    }

    @Test
    void defaultQueryRewriteIsDeterministicAndDoesNotNeedRealLlmOrExternalNetwork() throws Exception {
        var applicationYaml = Files.readString(BACKEND_ROOT.resolve("src/main/resources/application.yml"));
        var testYaml = Files.readString(BACKEND_ROOT.resolve("src/test/resources/application-test.yml"));
        var retrievalSources = sourceText(BACKEND_ROOT.resolve("src/main/java/com/probeflow/testagent/retrieval"));
        var rewrite = new DeterministicQueryRewriteService();

        var first = rewrite.rewrite(queryRewriteRequest());
        var second = rewrite.rewrite(queryRewriteRequest());

        assertThat(first.variants()).isEqualTo(second.variants());
        assertThat(first.variants()).allSatisfy(variant -> assertThat(variant.deterministicId()).startsWith("qv-"));
        assertThat(applicationYaml)
            .contains("allow-real-providers: ${PROBEFLOW_LLM_ALLOW_REAL_PROVIDERS:false}")
            .contains("allowed-providers: ${PROBEFLOW_LLM_ALLOWED_PROVIDERS:fake}")
            .contains("enabled: ${PROBEFLOW_LLM_MANUAL_REAL_ENABLED:false}");
        assertThat(testYaml)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake")
            .contains("enabled: false");
        assertThat(retrievalSources)
            .doesNotContain("com.probeflow.testagent.llm")
            .doesNotContain("WebClient")
            .doesNotContain("RestClient")
            .doesNotContain("HttpClient")
            .doesNotContain("URLConnection")
            .doesNotContain("Socket");
    }

    @Test
    void v5_4ReadPathDoesNotDependOnV5_5RerankOrExternalGraphDatabaseImplementations() throws Exception {
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
        var unifiedContextBuilder = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/UnifiedContextBuilder.java"
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
            .doesNotContain("com.probeflow.testagent.rerank")
            .doesNotContain("CrossEncoder")
            .doesNotContain("Cross Encoder")
            .doesNotContain("LlmRerank")
            .doesNotContain("LLM Rerank")
            .doesNotContain("SmallToBig")
            .doesNotContain("Small-to-Big")
            .doesNotContain("ParentChild")
            .doesNotContain("parent-child");
        assertThat(unifiedContextBuilder)
            .contains("loadKnowledge(apiSpec, normalized)")
            .contains("loadLongTermMemory(apiSpec, normalized)")
            .contains("usePostRerankExpandedContext(normalized)")
            .contains("prunePostRerankToBudget")
            .contains("pruneToBudget");
    }

    @Test
    void memoryRefineryRemainsLongTermMemoryWriteEntryAndV5_4OnlyExtendsReadPath() throws Exception {
        var feedbackSource = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackApplicationService.java"
        ));
        var refinerySource = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/MemoryRefineryService.java"
        ));
        var memoryRetrievalSource = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/LongTermMemoryRetrievalService.java"
        ));

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

    private QueryRewriteRequest queryRewriteRequest() {
        return new QueryRewriteRequest(
            "failure_analysis",
            "payment timeout during charge",
            "explain payment failure",
            "billing",
            "payment",
            "/api/payments/charge",
            "post",
            "PaymentOrder",
            "GW_TIMEOUT",
            "transport_timeout",
            "suite-payment-happy-path",
            "paymentId",
            "retry requires approval",
            "http.execute",
            List.of("payment", "timeout")
        );
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
