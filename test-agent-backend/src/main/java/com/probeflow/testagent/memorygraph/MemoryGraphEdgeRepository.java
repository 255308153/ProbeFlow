package com.probeflow.testagent.memorygraph;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryGraphEdgeRepository extends JpaRepository<MemoryGraphEdge, String> {

    List<MemoryGraphEdge> findAllBySourceNodeIdOrTargetNodeId(String sourceNodeId, String targetNodeId);

    List<MemoryGraphEdge> findAllBySourceNodeIdInOrTargetNodeIdIn(Collection<String> sourceNodeIds, Collection<String> targetNodeIds);
}
