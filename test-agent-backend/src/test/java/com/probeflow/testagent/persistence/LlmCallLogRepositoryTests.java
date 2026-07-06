package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.llm.LlmCallLog;
import com.probeflow.testagent.llm.LlmCallLogRepository;
import com.probeflow.testagent.llm.LlmCallStatus;
import com.probeflow.testagent.llm.LlmErrorType;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LlmCallLogRepositoryTests {

    @Autowired
    private LlmCallLogRepository logs;

    @Autowired
    private EntityManager entityManager;

    @Test
    void logPersistsAuditFieldsTokenUsageSummariesAndMetadata() {
        var log = new LlmCallLog();
        log.setTaskId("task-llm-audit");
        log.setPlanStepId("step-llm-audit");
        log.setPurpose("FAILURE_INSIGHT");
        log.setProvider("fake");
        log.setModel("fake-model");
        log.setTemplateId("v2.failure-insight.v1");
        log.setTemplateVersion("v1");
        log.setRequestHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        log.setStatus(LlmCallStatus.SUCCESS);
        log.setErrorType(LlmErrorType.NONE);
        log.setLatencyMs(42L);
        log.setPromptTokens(11);
        log.setCompletionTokens(7);
        log.setTotalTokens(18);
        log.setPromptSummary("Explain HTTP 500");
        log.setResponseSummary("Likely backend regression");
        log.setProviderTraceId("fake-trace-1");
        log.setFakeProvider(true);
        log.setMetadata(Map.of("templatePurpose", "FAILURE_INSIGHT"));

        var saved = logs.save(log);
        entityManager.flush();
        entityManager.clear();

        var loaded = logs.findById(saved.getLlmCallId()).orElseThrow();
        assertThat(loaded.getTaskId()).isEqualTo("task-llm-audit");
        assertThat(loaded.getPlanStepId()).isEqualTo("step-llm-audit");
        assertThat(loaded.getPurpose()).isEqualTo("FAILURE_INSIGHT");
        assertThat(loaded.getProvider()).isEqualTo("fake");
        assertThat(loaded.getModel()).isEqualTo("fake-model");
        assertThat(loaded.getTemplateId()).isEqualTo("v2.failure-insight.v1");
        assertThat(loaded.getTemplateVersion()).isEqualTo("v1");
        assertThat(loaded.getRequestHash()).hasSize(64);
        assertThat(loaded.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(loaded.getErrorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(loaded.getLatencyMs()).isEqualTo(42L);
        assertThat(loaded.getPromptTokens()).isEqualTo(11);
        assertThat(loaded.getCompletionTokens()).isEqualTo(7);
        assertThat(loaded.getTotalTokens()).isEqualTo(18);
        assertThat(loaded.getPromptSummary()).isEqualTo("Explain HTTP 500");
        assertThat(loaded.getResponseSummary()).isEqualTo("Likely backend regression");
        assertThat(loaded.getProviderTraceId()).isEqualTo("fake-trace-1");
        assertThat(loaded.isFakeProvider()).isTrue();
        assertThat(loaded.getMetadata()).containsEntry("templatePurpose", "FAILURE_INSIGHT");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void repositoryQueriesByStatusTaskAndPurpose() {
        logs.save(newLog("task-a", "REPORT_NARRATIVE", LlmCallStatus.SUCCESS));
        logs.save(newLog("task-a", "FAILURE_INSIGHT", LlmCallStatus.FAILED));
        logs.save(newLog("task-b", "REPORT_NARRATIVE", LlmCallStatus.BLOCKED));
        entityManager.flush();
        entityManager.clear();

        assertThat(logs.findAllByStatusOrderByCreatedAtAscLlmCallIdAsc(LlmCallStatus.SUCCESS))
            .extracting(LlmCallLog::getTaskId)
            .containsExactly("task-a");
        assertThat(logs.findAllByTaskIdOrderByCreatedAtAscLlmCallIdAsc("task-a"))
            .extracting(LlmCallLog::getPurpose)
            .containsExactly("REPORT_NARRATIVE", "FAILURE_INSIGHT");
        assertThat(logs.findAllByPurposeOrderByCreatedAtAscLlmCallIdAsc("REPORT_NARRATIVE"))
            .extracting(LlmCallLog::getTaskId)
            .containsExactly("task-a", "task-b");
    }

    private LlmCallLog newLog(String taskId, String purpose, LlmCallStatus status) {
        var log = new LlmCallLog();
        log.setTaskId(taskId);
        log.setPurpose(purpose);
        log.setProvider("fake");
        log.setModel("fake-model");
        log.setRequestHash(taskId + "-" + purpose + "-" + status);
        log.setStatus(status);
        log.setErrorType(status == LlmCallStatus.SUCCESS ? LlmErrorType.NONE : LlmErrorType.PROVIDER_ERROR);
        return log;
    }
}
