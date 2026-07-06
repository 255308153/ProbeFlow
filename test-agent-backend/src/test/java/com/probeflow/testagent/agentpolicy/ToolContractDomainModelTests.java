package com.probeflow.testagent.agentpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolContractDomainModelTests {

    @Test
    void toolNameUsesStableLowercaseDotSeparatedProtocol() {
        var name = ToolName.of("api.analyze-source");

        assertThat(name.value()).isEqualTo("api.analyze-source");
        assertThat(name.toString()).isEqualTo("api.analyze-source");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ToolName.of("Analyze Api"))
            .withMessage("tool name must use lowercase dot-separated protocol");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ToolName.of("api"))
            .withMessage("tool name must use lowercase dot-separated protocol");
    }

    @Test
    void toolContractCapturesCompletePlannerVisibleContractWithoutImplementationDetails() {
        var input = ToolInputSchema.of(
            "Source material to analyze.",
            List.of(
                ToolSchemaField.required("sourceMaterialId", ToolSchemaType.STRING, "Existing source material id"),
                ToolSchemaField.optional("includeExamples", ToolSchemaType.BOOLEAN, "Whether to include examples")
            ),
            "{\"sourceMaterialId\":\"source-1\"}"
        );
        var output = ToolOutputSchema.of(
            "ApiSpec id and analysis summary.",
            List.of(ToolSchemaField.required("apiSpecId", ToolSchemaType.STRING, "Generated ApiSpec id"))
        );

        var contract = ToolContract.of(
            ToolName.of("api.analyze-source"),
            ToolCapabilityGroup.API_ANALYSIS,
            "Analyze uploaded API source material into an ApiSpec.",
            input,
            output,
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.SOURCE_MATERIAL_AVAILABLE),
            ToolRiskLevel.MEDIUM,
            ToolExecutionMode.AUTO_ALLOWED,
            false,
            Set.of("internal", "analysis")
        );

        assertThat(contract.name()).isEqualTo(ToolName.of("api.analyze-source"));
        assertThat(contract.capabilityGroup()).isEqualTo(ToolCapabilityGroup.API_ANALYSIS);
        assertThat(contract.description()).contains("ApiSpec");
        assertThat(contract.inputSchema().requiredFields())
            .extracting(ToolSchemaField::name)
            .containsExactly("sourceMaterialId");
        assertThat(contract.outputSchema().summary()).contains("ApiSpec");
        assertThat(contract.preconditions()).containsExactlyInAnyOrder(
            ToolPrecondition.TASK_EXISTS,
            ToolPrecondition.SOURCE_MATERIAL_AVAILABLE
        );
        assertThat(contract.riskLevel()).isEqualTo(ToolRiskLevel.MEDIUM);
        assertThat(contract.executionMode()).isEqualTo(ToolExecutionMode.AUTO_ALLOWED);
        assertThat(contract.humanConfirmationRequired()).isFalse();
        assertThat(contract.tags()).containsExactlyInAnyOrder("internal", "analysis");
    }

    @Test
    void domainModelExposesRiskExecutionModeAndPreconditionVocabulary() {
        assertThat(ToolRiskLevel.values()).containsExactly(
            ToolRiskLevel.LOW,
            ToolRiskLevel.MEDIUM,
            ToolRiskLevel.HIGH,
            ToolRiskLevel.CRITICAL
        );
        assertThat(ToolExecutionMode.values()).containsExactly(
            ToolExecutionMode.AUTO_ALLOWED,
            ToolExecutionMode.HUMAN_CONFIRMATION_REQUIRED,
            ToolExecutionMode.BLOCKED
        );
        assertThat(ToolPrecondition.values()).contains(
            ToolPrecondition.TASK_EXISTS,
            ToolPrecondition.API_SPEC_AVAILABLE,
            ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE,
            ToolPrecondition.TEST_CASE_DRAFT_EXISTS,
            ToolPrecondition.EXECUTION_RECORD_EXISTS,
            ToolPrecondition.OBSERVATION_EXISTS,
            ToolPrecondition.REPORT_DATA_AVAILABLE
        );
    }

    @Test
    void humanConfirmationFlagIsTrueWhenExecutionModeRequiresIt() {
        var contract = ToolContract.of(
            ToolName.of("http.execute-approved-case"),
            ToolCapabilityGroup.HTTP_EXECUTION,
            "Execute an already approved HTTP API test case.",
            ToolInputSchema.of("Approved test case input.", List.of(
                ToolSchemaField.required("testCaseId", ToolSchemaType.STRING, "Approved test case id")
            ), "{\"testCaseId\":\"case-1\"}"),
            ToolOutputSchema.of("Execution record summary.", List.of(
                ToolSchemaField.required("executionRecordId", ToolSchemaType.STRING, "Execution record id")
            )),
            Set.of(ToolPrecondition.TEST_CASE_DRAFT_REVIEWED),
            ToolRiskLevel.HIGH,
            ToolExecutionMode.HUMAN_CONFIRMATION_REQUIRED,
            false,
            Set.of("mutating", "high-risk")
        );

        assertThat(contract.humanConfirmationRequired()).isTrue();
        assertThat(contract.preconditionNames()).contains("TEST_CASE_DRAFT_REVIEWED");
    }

    @Test
    void schemasDefensivelyCopyFieldsAndValidateRequiredDescriptions() {
        var fields = new java.util.ArrayList<ToolSchemaField>();
        fields.add(ToolSchemaField.requiredEnum("mode", "Workflow mode", List.of("AUTOMATIC", "REVIEW_REQUIRED")));
        var schema = ToolInputSchema.of("Mode input.", fields, null);
        fields.clear();

        assertThat(schema.fields()).hasSize(1);
        assertThat(schema.fields().getFirst().allowedValues()).containsExactly("AUTOMATIC", "REVIEW_REQUIRED");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ToolInputSchema.of(" ", List.of(), null))
            .withMessage("input schema description is required");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ToolOutputSchema.of(null, List.of()))
            .withMessage("output schema summary is required");
    }
}
