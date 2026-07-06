package com.probeflow.testagent.memory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryUsefulnessFeedbackRepository extends JpaRepository<MemoryUsefulnessFeedback, String> {

    Optional<MemoryUsefulnessFeedback> findByUsageIdAndActorAndOutcome(
        String usageId,
        String actor,
        MemoryUsefulnessOutcome outcome
    );

    List<MemoryUsefulnessFeedback> findAllByMemoryIdOrderByCreatedAtAscFeedbackIdAsc(String memoryId);

    long countByMemoryIdAndOutcomeAndStatus(
        String memoryId,
        MemoryUsefulnessOutcome outcome,
        MemoryUsefulnessFeedbackStatus status
    );
}
