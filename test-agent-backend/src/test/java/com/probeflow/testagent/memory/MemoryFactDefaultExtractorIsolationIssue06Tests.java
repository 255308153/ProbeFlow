package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.probeflow.testagent.llm.FakeLlmProvider;
import com.probeflow.testagent.llm.LlmRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryFactDefaultExtractorIsolationIssue06Tests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private MemoryFactExtractor memoryFactExtractor;

    @MockBean
    private FakeLlmProvider fakeLlmProvider;

    @Test
    void defaultConfigurationUsesDeterministicExtractorWithoutCallingLlm() {
        var result = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment timeout requires reusable retry handling",
            "Payment gateway timeout failures require reusable retry fixture evidence across tasks.",
            MemorySourceType.EXECUTION_RESULT,
            "issue06-default",
            "issue06-default-task",
            List.of("payment", "timeout", "retry"),
            0.82f,
            "Execution evidence confirmed reusable payment timeout behavior.",
            Map.of("module", "payment", "apiPath", "/api/payments/charge", "errorCode", "GW_TIMEOUT")
        ));

        assertThat(memoryFactExtractor).isInstanceOf(DeterministicMemoryFactExtractor.class);
        assertThat(result.accepted()).isTrue();
        assertThat(result.memory().metadata()).containsEntry("factType", "failure_pattern");
        verify(fakeLlmProvider, never()).generate(any(LlmRequest.class));
    }
}
