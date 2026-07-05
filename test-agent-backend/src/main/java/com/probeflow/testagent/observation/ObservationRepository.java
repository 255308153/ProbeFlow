package com.probeflow.testagent.observation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservationRepository extends JpaRepository<Observation, String> {

    List<Observation> findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
        String executionId,
        AnalysisLevel analysisLevel
    );
}
