package com.probeflow.testagent.demorun;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoConsoleIssue03Tests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void demoConsoleLoadsRunnableFirstScreenAndCallsDemoRunApi() throws Exception {
        mockMvc.perform(get("/v4/demo-console"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("ProbeFlow V4 Demo Console")))
            .andExpect(content().string(containsString("id=\"fixture-id\"")))
            .andExpect(content().string(containsString("id=\"provider-mode\"")))
            .andExpect(content().string(containsString("id=\"run-button\"")))
            .andExpect(content().string(containsString("id=\"run-status\"")))
            .andExpect(content().string(containsString("id=\"section-grid\"")))
            .andExpect(content().string(containsString("const apiEndpoint = \"/api/v4/demo-runs\"")))
            .andExpect(content().string(containsString("[\"plan\", \"Plan\"]")))
            .andExpect(content().string(containsString("[\"context\", \"Context\"]")))
            .andExpect(content().string(containsString("[\"tools\", \"Tools\"]")))
            .andExpect(content().string(containsString("[\"suite\", \"Suite\"]")))
            .andExpect(content().string(containsString("[\"execution\", \"Execution\"]")))
            .andExpect(content().string(containsString("[\"failureAnalysis\", \"Failure Analysis\"]")))
            .andExpect(content().string(containsString("[\"memoryFeedback\", \"Memory Feedback\"]")))
            .andExpect(content().string(containsString("[\"evaluation\", \"Evaluation\"]")))
            .andExpect(content().string(containsString("document.createElement(\"details\")")))
            .andExpect(content().string(containsString("Fake baseline")))
            .andExpect(content().string(containsString("Real LLM manual")))
            .andExpect(content().string(containsString("Comparison")));
    }
}
