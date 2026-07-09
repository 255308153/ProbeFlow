package com.probeflow.testagent.memorygraph;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryGraphNodeRepository extends JpaRepository<MemoryGraphNode, String> {

    Optional<MemoryGraphNode> findByEntityTypeAndNormalizedValueAndScope(
        MemoryGraphEntityType entityType,
        String normalizedValue,
        String scope
    );

    List<MemoryGraphNode> findAllByEntityTypeAndNormalizedValue(
        MemoryGraphEntityType entityType,
        String normalizedValue
    );
}
