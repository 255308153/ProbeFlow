package com.probeflow.testagent.demorun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoRunApiIssue02Tests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    private Path outputDir;

    @Test
    void postDemoRunTriggersFakeProviderAndReturnsStableDemoResultSchema() throws Exception {
        var body = Map.of(
            "fixtureId", "order-suite-demo",
            "providerMode", "fake",
            "runProfile", "local-demo",
            "comparison", false,
            "allowMemoryWrite", false,
            "outputFormats", List.of("JSON_REPORT", "MARKDOWN_REPORT"),
            "outputDirectory", outputDir.toString()
        );

        var response = mockMvc.perform(post("/api/v4/demo-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schemaVersion").value("v4-demo-run-result.v1"))
            .andExpect(jsonPath("$.fixtureId").value("order-suite-demo"))
            .andExpect(jsonPath("$.providerMode").value("FAKE"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.usesRealLlm").value(false))
            .andExpect(jsonPath("$.usesExternalHttp").value(false))
            .andExpect(jsonPath("$.provider.fakeBaseline").value(true))
            .andExpect(jsonPath("$.provider.comparisonEnabled").value(false))
            .andExpect(jsonPath("$.provider.allowMemoryWrite").value(false))
            .andExpect(jsonPath("$.plan.sectionId").value("plan"))
            .andExpect(jsonPath("$.context.sectionId").value("context"))
            .andExpect(jsonPath("$.tools.sectionId").value("tools"))
            .andExpect(jsonPath("$.suite.sectionId").value("suite"))
            .andExpect(jsonPath("$.execution.sectionId").value("execution"))
            .andExpect(jsonPath("$.variableAudit.sectionId").value("variable-audit"))
            .andExpect(jsonPath("$.failureAnalysis.sectionId").value("failure-analysis"))
            .andExpect(jsonPath("$.memoryFeedback.sectionId").value("memory-feedback"))
            .andExpect(jsonPath("$.evaluation.sectionId").value("evaluation"))
            .andExpect(jsonPath("$.errors.status").value("NONE"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(response)
            .doesNotContain("api_key")
            .doesNotContain("order-auth-token")
            .doesNotContain("unredacted-fixture-secret")
            .doesNotContain("sk-live")
            .doesNotContain("sk-proj");
    }

    @Test
    void postDemoRunRejectsMissingFixtureIdWithClearValidationError() throws Exception {
        var body = Map.of(
            "providerMode", "fake",
            "runProfile", "local-demo"
        );

        mockMvc.perform(post("/api/v4/demo-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_FIXTURE_ID"))
            .andExpect(jsonPath("$.category").value("VALIDATION"))
            .andExpect(jsonPath("$.field").value("fixtureId"));
    }

    @Test
    void postDemoRunRejectsInvalidProviderModeAndRunProfileBeforeHarnessExecution() throws Exception {
        var invalidProvider = Map.of(
            "fixtureId", "order-suite-demo",
            "providerMode", "banana",
            "runProfile", "local-demo"
        );

        mockMvc.perform(post("/api/v4/demo-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidProvider)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_MODE"))
            .andExpect(jsonPath("$.field").value("providerMode"));

        var invalidProfile = Map.of(
            "fixtureId", "order-suite-demo",
            "providerMode", "fake",
            "runProfile", "production"
        );

        mockMvc.perform(post("/api/v4/demo-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidProfile)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_RUN_PROFILE"))
            .andExpect(jsonPath("$.field").value("runProfile"));
    }

    @Test
    void postDemoRunReturnsDisplayableErrorSectionWhenHarnessRunFails() throws Exception {
        var body = Map.of(
            "fixtureId", "missing-fixture",
            "providerMode", "fake",
            "runProfile", "local-demo",
            "outputDirectory", outputDir.toString()
        );

        mockMvc.perform(post("/api/v4/demo-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.errors.sectionId").value("errors"))
            .andExpect(jsonPath("$.errors.summary.hasErrors").value(true))
            .andExpect(jsonPath("$.errors.summary.diagnostics[0].code").value("FIXTURE_NOT_FOUND"))
            .andExpect(jsonPath("$.errors.summary.diagnostics[0].category").value("FIXTURE"))
            .andExpect(jsonPath("$.diagnostics[0].code").value("FIXTURE_NOT_FOUND"));
    }
}
