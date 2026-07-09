package com.probeflow.testagent.memorygraph;

import com.probeflow.testagent.memory.LongTermMemoryRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MemoryGraphQueryService {

    public static final double DEFAULT_CONFIDENCE_GATE = 0.55d;

    private final LongTermMemoryRepository longTermMemories;
    private final MemoryGraphNodeRepository nodes;
    private final MemoryGraphEdgeRepository edges;

    public MemoryGraphQueryService(
        LongTermMemoryRepository longTermMemories,
        MemoryGraphNodeRepository nodes,
        MemoryGraphEdgeRepository edges
    ) {
        this.longTermMemories = longTermMemories;
        this.nodes = nodes;
        this.edges = edges;
    }

    @Transactional(readOnly = true)
    public MemoryGraphQueryResult queryRelated(MemoryGraphSeed seed, int maxDepth, int limit) {
        var normalized = normalizeSeed(seed);
        if (normalized == null) {
            return new MemoryGraphQueryResult(seed, List.of(), List.of());
        }
        var seedNodes = StringUtils.hasText(normalized.scope())
            ? nodes.findByEntityTypeAndNormalizedValueAndScope(normalized.entityType(), normalized.value(), normalized.scope()).stream().toList()
            : nodes.findAllByEntityTypeAndNormalizedValue(normalized.entityType(), normalized.value());
        if (seedNodes.isEmpty()) {
            return new MemoryGraphQueryResult(normalized, List.of(), List.of());
        }

        var relatedEntities = new LinkedHashMap<String, MemoryGraphRelatedEntity>();
        var relatedMemories = new LinkedHashMap<String, MemoryGraphRelatedMemory>();
        for (var seedNode : seedNodes) {
            traverse(seedNode, Math.max(1, maxDepth), Math.max(1, limit), relatedEntities, relatedMemories);
        }

        return new MemoryGraphQueryResult(
            normalized,
            relatedEntities.values().stream()
                .sorted(Comparator
                    .comparingDouble(MemoryGraphRelatedEntity::confidence).reversed()
                    .thenComparing(MemoryGraphRelatedEntity::entityType)
                    .thenComparing(MemoryGraphRelatedEntity::normalizedValue))
                .limit(limit)
                .toList(),
            relatedMemories.values().stream()
                .sorted(Comparator
                    .comparingDouble(MemoryGraphRelatedMemory::confidence).reversed()
                    .thenComparing(MemoryGraphRelatedMemory::memoryId))
                .limit(limit)
                .toList()
        );
    }

    private void traverse(
        MemoryGraphNode seedNode,
        int maxDepth,
        int limit,
        Map<String, MemoryGraphRelatedEntity> relatedEntities,
        Map<String, MemoryGraphRelatedMemory> relatedMemories
    ) {
        var queue = new ArrayDeque<TraversalState>();
        var visited = new LinkedHashSet<String>();
        queue.add(new TraversalState(seedNode, List.of(), 1.0d, 0));
        visited.add(seedNode.getNodeId());

        while (!queue.isEmpty() && (relatedEntities.size() < limit || relatedMemories.size() < limit)) {
            var current = queue.removeFirst();
            if (current.depth() >= maxDepth) {
                continue;
            }
            for (var edge : edges.findAllBySourceNodeIdOrTargetNodeId(current.node().getNodeId(), current.node().getNodeId())) {
                if (!forwardQueryable(edge)) {
                    continue;
                }
                var neighborId = edge.getSourceNodeId().equals(current.node().getNodeId()) ? edge.getTargetNodeId() : edge.getSourceNodeId();
                var neighbor = nodes.findById(neighborId).orElse(null);
                if (neighbor == null || neighbor.getConfidence() < DEFAULT_CONFIDENCE_GATE || edge.getConfidence() < DEFAULT_CONFIDENCE_GATE) {
                    continue;
                }
                var path = new ArrayList<>(current.path());
                path.add(edge.getRelationType().name());
                var confidence = Math.min(current.confidence(), Math.min(edge.getConfidence(), neighbor.getConfidence()));
                if (neighbor.getEntityType() == MemoryGraphEntityType.MEMORY_FACT) {
                    addRelatedMemory(neighbor, edge, path, confidence, relatedMemories);
                } else if (!neighbor.getNodeId().equals(seedNode.getNodeId())) {
                    relatedEntities.putIfAbsent(neighbor.getNodeId(), new MemoryGraphRelatedEntity(
                        neighbor.getEntityType(),
                        neighbor.getDisplayValue(),
                        neighbor.getNormalizedValue(),
                        neighbor.getScope(),
                        matchReason(path),
                        List.copyOf(path),
                        confidence,
                        List.copyOf(edge.getSourceMemoryIds()),
                        List.copyOf(edge.getEvidenceSummaries())
                    ));
                }
                if (visited.add(neighborId)) {
                    queue.addLast(new TraversalState(neighbor, List.copyOf(path), confidence, current.depth() + 1));
                }
            }
        }
    }

    private void addRelatedMemory(
        MemoryGraphNode memoryNode,
        MemoryGraphEdge edge,
        List<String> path,
        double confidence,
        Map<String, MemoryGraphRelatedMemory> relatedMemories
    ) {
        var memoryId = memoryNode.getNormalizedValue();
        longTermMemories.findById(memoryId).ifPresent(memory -> {
            var candidate = new MemoryGraphRelatedMemory(
                memoryId,
                memory.getSummary(),
                memory.getSourceRef(),
                matchReason(path),
                List.copyOf(path),
                confidence,
                List.copyOf(edge.getSourceMemoryIds()),
                List.copyOf(edge.getEvidenceSummaries())
            );
            var existing = relatedMemories.get(memoryId);
            if (existing == null || betterPath(candidate, existing)) {
                relatedMemories.put(memoryId, candidate);
            }
        });
    }

    private boolean betterPath(MemoryGraphRelatedMemory candidate, MemoryGraphRelatedMemory existing) {
        if ("graph-neighbor-memory".equals(existing.matchReason()) && !"graph-neighbor-memory".equals(candidate.matchReason())) {
            return true;
        }
        if (candidate.confidence() > existing.confidence() && candidate.relationPath().size() <= existing.relationPath().size()) {
            return true;
        }
        return candidate.relationPath().stream().anyMatch(this::isSpecificRelation)
            && existing.relationPath().stream().noneMatch(this::isSpecificRelation);
    }

    private boolean isSpecificRelation(String relationName) {
        return !"MEMORY_MENTIONS_ENTITY".equals(relationName) && !"ENTITY_RELATED_TO_MEMORY".equals(relationName);
    }

    private boolean forwardQueryable(MemoryGraphEdge edge) {
        return edge.getRelationType() != MemoryGraphRelationType.FACT_CONFLICTS_WITH_FACT
            && edge.getSourceMemoryIds() != null
            && !edge.getSourceMemoryIds().isEmpty();
    }

    private String matchReason(MemoryGraphRelationType relationType) {
        return switch (relationType) {
            case API_BELONGS_TO_MODULE, MODULE_BELONGS_TO_SYSTEM, API_USES_HTTP_METHOD -> "graph-related-api";
            case ERROR_OBSERVED_ON_API -> "graph-related-error-code";
            case SUITE_STEP_PRODUCES_VARIABLE, SUITE_STEP_CONSUMES_VARIABLE, VARIABLE_EXTRACTED_FROM_SOURCE_PATH -> "graph-related-variable";
            case POLICY_REASON_APPLIES_TO_TOOL -> "graph-related-policy";
            case BUSINESS_ENTITY_RELATED_TO_API, BUSINESS_ENTITY_REQUIRES_PRECONDITION -> "graph-related-business-entity";
            case ENTITY_RELATED_TO_MEMORY, MEMORY_MENTIONS_ENTITY -> "graph-neighbor-memory";
            default -> "graph-neighbor-memory";
        };
    }

    private String matchReason(List<String> relationPath) {
        for (var relationName : relationPath) {
            var relationType = MemoryGraphRelationType.valueOf(relationName);
            var reason = matchReason(relationType);
            if (!"graph-neighbor-memory".equals(reason)) {
                return reason;
            }
        }
        return "graph-neighbor-memory";
    }

    private MemoryGraphSeed normalizeSeed(MemoryGraphSeed seed) {
        if (seed == null || seed.entityType() == null || !StringUtils.hasText(seed.value())) {
            return null;
        }
        var normalizedValue = MemoryGraphProjectionService.normalize(seed.entityType(), seed.value());
        if (!StringUtils.hasText(normalizedValue)) {
            return null;
        }
        return new MemoryGraphSeed(seed.entityType(), normalizedValue, seed.scope());
    }

    private record TraversalState(
        MemoryGraphNode node,
        List<String> path,
        double confidence,
        int depth
    ) {
    }
}
