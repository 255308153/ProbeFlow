package com.probeflow.testagent.humanintheloop;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanDecisionRecordRepository extends JpaRepository<HumanDecisionRecord, String> {

    List<HumanDecisionRecord> findByTaskIdOrderByCreatedAtAsc(String taskId);

    List<HumanDecisionRecord> findByRequestIdOrderByCreatedAtAsc(String requestId);
}
