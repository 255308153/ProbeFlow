package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V5Phase5LlmRerankAcceptanceBoundaryGuardTests {

    private static final Path BACKEND_ROOT = Path.of("").toAbsolutePath();
    private static final Path DOC = BACKEND_ROOT.resolve("docs/v5-5-llm-rerank-manual-profile.md");

    @Test
    void llmRerankManualProfileDocumentsDeepSeekPolicyAndDefaultFallback() throws Exception {
        var doc = Files.readString(DOC);
        var readme = Files.readString(BACKEND_ROOT.resolve("README.md"));

        assertThat(readme)
            .contains("V5-5 LLM Rerank Manual Profile")
            .contains("docs/v5-5-llm-rerank-manual-profile.md");
        assertThat(doc)
            .contains("disabled by default")
            .contains("safe candidate summaries only")
            .contains("structured JSON")
            .contains("unknown candidate ids")
            .contains("evidence refs that were not present in the input candidate")
            .contains("deterministic rerank baseline")
            .contains("fake LLM is only a CI/no-key fallback")
            .contains("not sufficient as the only acceptance path")
            .contains("DeepSeek V4 Pro")
            .contains("PROBEFLOW_RERANK_LLM_ENABLED=true")
            .contains("PROBEFLOW_RERANK_LLM_MODEL=deepseek-v4-pro");
    }

    @Test
    void defaultConfigDoesNotEnableRealLlmRerankProvider() throws Exception {
        var applicationYaml = Files.readString(BACKEND_ROOT.resolve("src/main/resources/application.yml"));
        var testYaml = Files.readString(BACKEND_ROOT.resolve("src/test/resources/application-test.yml"));

        assertThat(applicationYaml)
            .contains("llm:")
            .contains("enabled: ${PROBEFLOW_RERANK_LLM_ENABLED:false}")
            .contains("provider: ${PROBEFLOW_RERANK_LLM_PROVIDER:disabled}")
            .contains("model: ${PROBEFLOW_RERANK_LLM_MODEL:deepseek-v4-pro}");
        assertThat(testYaml)
            .contains("llm:")
            .contains("enabled: false")
            .contains("provider: disabled")
            .contains("model: deepseek-v4-pro");
    }

    @Test
    void llmRerankUsesExistingLlmProviderSeamWithoutExternalClientDependency() throws Exception {
        var rerankSources = sourceText(BACKEND_ROOT.resolve("src/main/java/com/probeflow/testagent/rerank"));
        var pom = Files.readString(BACKEND_ROOT.resolve("pom.xml"));

        assertThat(rerankSources)
            .contains("LlmProvider")
            .contains("LlmRequest")
            .contains("DeterministicRerankEngine")
            .doesNotContain("WebClient")
            .doesNotContain("RestClient")
            .doesNotContain("HttpClient")
            .doesNotContain("URLConnection")
            .doesNotContain("Socket")
            .doesNotContain("setBearerAuth");

        assertThat(presentTerms(pom, List.of(
            "deepseek",
            "openai",
            "anthropic",
            "langchain4j",
            "spring-ai",
            "cohere",
            "voyageai"
        ))).isEmpty();
    }

    private String sourceText(Path sourceRoot) throws IOException {
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
