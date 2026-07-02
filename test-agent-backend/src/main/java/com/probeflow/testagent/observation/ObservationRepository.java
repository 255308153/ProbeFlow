package com.probeflow.testagent.observation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservationRepository extends JpaRepository<Observation, String> {
}
