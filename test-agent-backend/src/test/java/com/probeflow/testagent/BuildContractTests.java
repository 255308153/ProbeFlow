package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class BuildContractTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void mavenBuildIsSingleModuleJava21Project() throws Exception {
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(PROJECT_ROOT.resolve("pom.xml").toFile());

        assertThat(document.getElementsByTagName("module").getLength()).isZero();
        assertThat(document.getElementsByTagName("java.version").item(0).getTextContent()).isEqualTo("21");
    }

    @Test
    void mavenBuildDeclaresRequiredBackendFoundationDependencies() throws Exception {
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(PROJECT_ROOT.resolve("pom.xml").toFile());

        var dependencies = document.getElementsByTagName("dependency");

        assertThat(hasDependency(dependencies, "org.springframework.boot", "spring-boot-starter-web")).isTrue();
        assertThat(hasDependency(dependencies, "org.springframework.boot", "spring-boot-starter-data-jpa")).isTrue();
        assertThat(hasDependency(dependencies, "org.springframework.boot", "spring-boot-starter-data-redis")).isTrue();
        assertThat(hasDependency(dependencies, "org.flywaydb", "flyway-core")).isTrue();
        assertThat(hasDependency(dependencies, "org.postgresql", "postgresql")).isTrue();
        assertThat(hasDependency(dependencies, "com.pgvector", "pgvector")).isTrue();
        assertThat(hasDependency(dependencies, "com.fasterxml.jackson.core", "jackson-databind")).isTrue();
    }

    @Test
    void mavenBuildDoesNotIncludeOutOfScopeInfrastructureStacks() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(pom)
            .doesNotContain("milvus")
            .doesNotContain("elasticsearch")
            .doesNotContain("kafka")
            .doesNotContain("neo4j")
            .doesNotContain("selenium")
            .doesNotContain("playwright");
    }

    @Test
    void dockerComposeDefinesOnlyPostgresWithPgvectorAndRedis() throws Exception {
        var compose = Files.readString(PROJECT_ROOT.resolve("docker-compose.yml"));

        assertThat(compose).contains("pgvector/pgvector");
        assertThat(compose).contains("redis:");
        assertThat(compose).contains("postgres:");
        assertThat(compose)
            .doesNotContain("milvus")
            .doesNotContain("elasticsearch")
            .doesNotContain("kafka")
            .doesNotContain("neo4j")
            .doesNotContain("selenium")
            .doesNotContain("playwright");
    }

    private static boolean hasDependency(
        org.w3c.dom.NodeList dependencies,
        String expectedGroupId,
        String expectedArtifactId
    ) {
        for (int index = 0; index < dependencies.getLength(); index++) {
            var dependency = (Element) dependencies.item(index);
            var groupId = dependency.getElementsByTagName("groupId").item(0).getTextContent();
            var artifactId = dependency.getElementsByTagName("artifactId").item(0).getTextContent();

            if (expectedGroupId.equals(groupId) && expectedArtifactId.equals(artifactId)) {
                return true;
            }
        }

        return false;
    }
}
