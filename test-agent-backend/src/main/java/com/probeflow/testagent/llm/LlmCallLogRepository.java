package com.probeflow.testagent.llm;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, String> {

    List<LlmCallLog> findAllByStatusOrderByCreatedAtAscLlmCallIdAsc(LlmCallStatus status);

    List<LlmCallLog> findAllByTaskIdOrderByCreatedAtAscLlmCallIdAsc(String taskId);

    List<LlmCallLog> findAllByPurposeOrderByCreatedAtAscLlmCallIdAsc(String purpose);
}
