package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Phase1BoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void phase1InfrastructureDeclaresPostgresPgvectorAndRedisOnly() throws Exception {
        var compose = Files.readString(PROJECT_ROOT.resolve("docker-compose.yml"));
        var pgvectorMigration = Files.readString(
            PROJECT_ROOT.resolve("src/main/resources/db/migration/postgresql/V2__enable_pgvector.sql")
        );
        var knowledgeVectorMigration = Files.readString(
            PROJECT_ROOT.resolve("src/main/resources/db/migration/postgresql/V7__knowledge_document_revision_chunk.sql")
        );
        var memoryVectorMigration = Files.readString(
            PROJECT_ROOT.resolve("src/main/resources/db/migration/postgresql/V8__memory_item_scoped_storage.sql")
        );

        assertThat(compose).contains("pgvector/pgvector:pg16", "redis:7.4-alpine", "healthcheck");
        assertThat(pgvectorMigration).contains("CREATE EXTENSION IF NOT EXISTS vector");
        assertThat(knowledgeVectorMigration).contains("embedding vector(1024)", "USING hnsw", "vector_cosine_ops");
        assertThat(memoryVectorMigration).contains("embedding vector(1024)", "USING hnsw", "vector_cosine_ops");
    }

    @Test
    void backendKeepsStorageFoundationAndApiAnalysisPackagesOnly() throws Exception {
        var packageRoot = PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent");
        var immediatePackages = Files.list(packageRoot)
            .filter(Files::isDirectory)
            .map(path -> path.getFileName().toString())
            .collect(Collectors.toSet());

        assertThat(immediatePackages).isEqualTo(Set.of(
            "agentevaluation",
            "agentmemoryfeedback",
            "agentpolicy",
            "analysis",
            "apispec",
            "businessflowdiscovery",
            "changelog",
            "controlledplanner",
            "executionrecord",
            "failureanalysis",
            "humanintheloop",
            "httpexecution",
            "knowledge",
            "llm",
            "manualsuiteagent",
            "memory",
            "observation",
            "orchestration",
            "policyvalidator",
            "replanning",
            "report",
            "sourcematerial",
            "suitedraft",
            "suiteruntime",
            "task",
            "taskcaseexecution",
            "testcase",
            "testcasegeneration",
            "testcasedraft"
        ));
    }

    @Test
    void phase1BackendDoesNotAdoptOutOfScopeStacksOrOldProjectCode() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var sourceText = Files.walk(PROJECT_ROOT.resolve("src/main/java"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));
        var controllerAnnotations = sourceText.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();

        assertThat(pom)
            .doesNotContain("bun")
            .doesNotContain("typescript")
            .doesNotContain("playwright")
            .doesNotContain("selenium")
            .doesNotContain("milvus")
            .doesNotContain("elasticsearch")
            .doesNotContain("kafka")
            .doesNotContain("neo4j");

        assertThat(sourceText)
            .doesNotContain("AGI-saber-java")
            .doesNotContain("ClaudeCode")
            .doesNotContain("WebDriver")
            .doesNotContain("Playwright")
            .doesNotContain("Milvus")
            .doesNotContain("Elasticsearch")
            .doesNotContain("Kafka")
            .doesNotContain("Neo4j")
            .doesNotContain("KnowledgeRetriever");
        assertThat(controllerAnnotations).isEmpty();
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
