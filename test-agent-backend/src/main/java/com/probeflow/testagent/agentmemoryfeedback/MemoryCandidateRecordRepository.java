package com.probeflow.testagent.agentmemoryfeedback;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryCandidateRecordRepository extends JpaRepository<MemoryCandidateRecord, String> {

    Optional<MemoryCandidateRecord> findBySourceTypeAndSourceRefAndTaskId(
        AgentMemoryCandidateSourceType sourceType,
        String sourceRef,
        String taskId
    );

    List<MemoryCandidateRecord> findAllByTaskIdOrderByCreatedAtAscCandidateIdAsc(String taskId);
}
