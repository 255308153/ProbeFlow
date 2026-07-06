package com.probeflow.testagent.agentpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ToolContractRegistryTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    private final ToolContractRegistry registry = new ToolContractRegistry();

    @Test
    void registryListsAllInternalPlannerVisibleToolContracts() {
        assertThat(registry.listAll())
            .extracting(contract -> contract.name().value())
            .containsExactly(
                "api.analyze-source",
                "failure.analyze-execution",
                "http.execute-approved-case",
                "knowledge.retrieve-context",
                "memory.build-context",
                "report.generate-task",
                "testcase.generate-drafts",
                "testcase.review-draft"
            );
    }

    @Test
    void registryFindsContractsByStableToolNameAndRejectsUnknownNames() {
        var found = registry.find(ToolNames.TESTCASE_GENERATE_DRAFTS).orElseThrow();

        assertThat(found)
            .returns(ToolCapabilityGroup.TEST_CASE, ToolContract::capabilityGroup)
            .returns(ToolRiskLevel.MEDIUM, ToolContract::riskLevel);
        assertThat(registry.find("testcase.generate-drafts")).contains(found);
        assertThat(registry.find("unknown.missing-tool")).isEmpty();
        assertThat(registry.find("not a protocol name")).isEmpty();
    }

    @Test
    void registeredContractsHaveCompleteSchemasRiskModesPreconditionsAndTags() {
        assertThat(registry.listAll()).allSatisfy(contract -> {
            assertThat(contract.name()).isNotNull();
            assertThat(contract.description()).isNotBlank();
            assertThat(contract.inputSchema()).isNotNull();
            assertThat(contract.inputSchema().description()).isNotBlank();
            assertThat(contract.inputSchema().fields()).isNotEmpty();
            assertThat(contract.outputSchema()).isNotNull();
            assertThat(contract.outputSchema().summary()).isNotBlank();
            assertThat(contract.riskLevel()).isNotNull();
            assertThat(contract.executionMode()).isNotNull();
            assertThat(contract.preconditions()).isNotEmpty();
            assertThat(contract.tags()).contains("internal");
        });
    }

    @Test
    void catalogMarksKeyProbeFlowCapabilitiesWithExpectedContracts() {
        assertThat(registry.find(ToolNames.API_ANALYZE_SOURCE).orElseThrow().preconditions())
            .contains(ToolPrecondition.SOURCE_MATERIAL_AVAILABLE);
        assertThat(registry.find(ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT).orElseThrow())
            .satisfies(contract -> {
                assertThat(contract.readOnly()).isTrue();
                assertThat(contract.riskLevel()).isEqualTo(ToolRiskLevel.LOW);
            });
        assertThat(registry.find(ToolNames.MEMORY_BUILD_CONTEXT).orElseThrow().readOnly()).isTrue();
        assertThat(registry.find(ToolNames.TESTCASE_GENERATE_DRAFTS).orElseThrow())
            .satisfies(contract -> {
                assertThat(contract.tags()).contains("draft-output", "review-gate");
                assertThat(contract.outputSchema().summary()).contains("Review");
            });
        assertThat(registry.find(ToolNames.HTTP_EXECUTE_APPROVED_CASE).orElseThrow())
            .satisfies(contract -> {
                assertThat(contract.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
                assertThat(contract.humanConfirmationRequired()).isTrue();
                assertThat(contract.preconditions()).contains(ToolPrecondition.EXECUTION_READINESS_CONFIRMED);
            });
        assertThat(registry.find(ToolNames.FAILURE_ANALYZE_EXECUTION).orElseThrow().preconditions())
            .contains(ToolPrecondition.EXECUTION_RECORD_EXISTS, ToolPrecondition.FAILURE_SIGNAL_AVAILABLE);
        assertThat(registry.find(ToolNames.REPORT_GENERATE_TASK).orElseThrow().preconditions())
            .contains(ToolPrecondition.REPORT_DATA_AVAILABLE);
    }

    @Test
    void catalogDoesNotRegisterExternalCiTicketMcpPluginOrLlmExecutorTools() {
        var registeredNames = registry.listAll().stream()
            .map(contract -> contract.name().value())
            .collect(Collectors.toSet());

        assertThat(registeredNames).doesNotContain(
            "ui.browser-automation",
            "service.direct-call",
            "db.direct-assertion",
            "ticket.create-github",
            "ticket.create-jira",
            "notify.slack",
            "ci.run-pipeline",
            "mcp.invoke-tool",
            "plugin.install-marketplace",
            "llm.call-model"
        );
    }

    @Test
    void registryRejectsDuplicateToolNames() {
        var contract = registry.find(ToolNames.API_ANALYZE_SOURCE).orElseThrow();

        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ToolContractRegistry(java.util.List.of(contract, contract)))
            .withMessage("duplicate tool contract: api.analyze-source");
    }

    @Test
    void toolCatalogSourceDoesNotExposeServiceRepositoryDatabaseOrHttpClientReferences() throws Exception {
        var sourceText = Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/agentpolicy"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));

        assertThat(sourceText)
            .doesNotContain("ApplicationService")
            .doesNotContain("Repository")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain("HttpClient")
            .doesNotContain("WebClient")
            .doesNotContain("RestTemplate");
    }

    @Test
    void noExternalCapabilityGroupsAreRegistered() {
        assertThat(registry.listAll())
            .extracting(ToolContract::capabilityGroup)
            .containsOnly(
                ToolCapabilityGroup.API_ANALYSIS,
                ToolCapabilityGroup.KNOWLEDGE,
                ToolCapabilityGroup.MEMORY,
                ToolCapabilityGroup.TEST_CASE,
                ToolCapabilityGroup.HTTP_EXECUTION,
                ToolCapabilityGroup.FAILURE_ANALYSIS,
                ToolCapabilityGroup.REPORTING
            );
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
