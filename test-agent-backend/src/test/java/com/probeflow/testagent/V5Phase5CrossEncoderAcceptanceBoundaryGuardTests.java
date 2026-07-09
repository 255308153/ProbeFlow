package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V5Phase5CrossEncoderAcceptanceBoundaryGuardTests {

    private static final Path BACKEND_ROOT = Path.of("").toAbsolutePath();
    private static final Path DOC = BACKEND_ROOT.resolve("docs/v5-5-cross-encoder-rerank-adapter.md");

    @Test
    void crossEncoderAdapterIsDocumentedAsDisabledExtensionPoint() throws Exception {
        var doc = Files.readString(DOC);
        var readme = Files.readString(BACKEND_ROOT.resolve("README.md"));

        assertThat(readme)
            .contains("V5-5 Cross Encoder Rerank Adapter")
            .contains("docs/v5-5-cross-encoder-rerank-adapter.md");
        assertThat(doc)
            .contains("extension point")
            .contains("not a default runtime dependency")
            .contains("does not recall knowledge, memory, graph data")
            .contains("safe diagnostic metadata")
            .contains("API keys")
            .contains("Authorization headers")
            .contains("tokens")
            .contains("fall back to the deterministic rerank baseline")
            .contains("enabled: false")
            .contains("provider: disabled")
            .contains("fake providers and local stub responses only");
    }

    @Test
    void defaultConfigDoesNotEnableRealCrossEncoderProvider() throws Exception {
        var applicationYaml = Files.readString(BACKEND_ROOT.resolve("src/main/resources/application.yml"));
        var testYaml = Files.readString(BACKEND_ROOT.resolve("src/test/resources/application-test.yml"));

        assertThat(applicationYaml)
            .contains("enabled: ${PROBEFLOW_RERANK_CROSS_ENCODER_ENABLED:false}")
            .contains("provider: ${PROBEFLOW_RERANK_CROSS_ENCODER_PROVIDER:disabled}");
        assertThat(testYaml)
            .contains("cross-encoder:")
            .contains("enabled: false")
            .contains("provider: disabled");
    }

    @Test
    void defaultCrossEncoderAdapterHasNoExternalServiceClientOrProviderSdkDependency() throws Exception {
        var rerankSources = sourceText(BACKEND_ROOT.resolve("src/main/java/com/probeflow/testagent/rerank"));
        var pom = Files.readString(BACKEND_ROOT.resolve("pom.xml"));

        assertThat(rerankSources)
            .contains("CrossEncoderRerankProvider")
            .contains("DeterministicRerankEngine")
            .doesNotContain("WebClient")
            .doesNotContain("RestClient")
            .doesNotContain("HttpClient")
            .doesNotContain("URLConnection")
            .doesNotContain("Socket")
            .doesNotContain("setBearerAuth")
            .doesNotContain("Authorization");

        assertThat(presentTerms(pom, List.of(
            "cross-encoder",
            "sentence-transformers",
            "onnxruntime",
            "huggingface",
            "cohere",
            "jina-ai",
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
