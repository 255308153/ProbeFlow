package com.probeflow.testagent.memorygraph;

import com.probeflow.testagent.memory.LongTermMemory;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemoryStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MemoryGraphProjectionService {

    private final LongTermMemoryRepository longTermMemories;
    private final MemoryGraphNodeRepository nodes;
    private final MemoryGraphEdgeRepository edges;

    public MemoryGraphProjectionService(
        LongTermMemoryRepository longTermMemories,
        MemoryGraphNodeRepository nodes,
        MemoryGraphEdgeRepository edges
    ) {
        this.longTermMemories = longTermMemories;
        this.nodes = nodes;
        this.edges = edges;
    }

    @Transactional
    public MemoryGraphProjectionSummary rebuild() {
        edges.deleteAllInBatch();
        nodes.deleteAllInBatch();

        var activeMemories = longTermMemories.findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE);
        var context = new ProjectionContext();
        for (var memory : activeMemories) {
            project(memory, context);
        }
        return new MemoryGraphProjectionSummary(
            context.processedMemoryCount,
            context.createdNodeIds.size(),
            0,
            context.createdEdgeIds.size(),
            0,
            context.skippedMemoryCount,
            List.copyOf(context.warnings)
        );
    }

    private void project(LongTermMemory memory, ProjectionContext context) {
        if (memory.getStatus() != MemoryStatus.ACTIVE) {
            context.skippedMemoryCount++;
            return;
        }
        context.processedMemoryCount++;

        var evidence = MemoryGraphProvenance.from(memory);
        var metadata = metadata(memory);
        var memoryNode = upsertNode(
            MemoryGraphEntityType.MEMORY_FACT,
            memory.getMemoryId(),
            memory.getMemoryId(),
            null,
            Map.of("summary", memory.getSummary(), "scopeType", memory.getScopeType().name()),
            evidence,
            context
        );
        var entityNodes = new LinkedHashMap<MemoryGraphEntityType, MemoryGraphNode>();
        var tagNodes = new ArrayList<MemoryGraphNode>();

        putEntity(entityNodes, MemoryGraphEntityType.SYSTEM, value(metadata, "systemName", "system", "serviceName"), null, metadata, evidence, context);
        var systemScope = scope("system", normalizeGeneral(value(metadata, "systemName", "system", "serviceName")));
        putEntity(entityNodes, MemoryGraphEntityType.MODULE, value(metadata, "module", "component"), systemScope, metadata, evidence, context);
        var moduleScope = scope(
            "system",
            normalizeGeneral(value(metadata, "systemName", "system", "serviceName")),
            "module",
            normalizeGeneral(value(metadata, "module", "component"))
        );
        putEntity(entityNodes, MemoryGraphEntityType.API_PATH, value(metadata, "apiPath", "path", "endpoint"), moduleScope, metadata, evidence, context);
        putEntity(entityNodes, MemoryGraphEntityType.HTTP_METHOD, value(metadata, "httpMethod", "method"), moduleScope, metadata, evidence, context);
        putEntity(entityNodes, MemoryGraphEntityType.ERROR_CODE, value(metadata, "errorCode", "error", "code"), moduleScope, metadata, evidence, context);
        putEntity(
            entityNodes,
            MemoryGraphEntityType.FAILURE_CLASSIFICATION,
            value(metadata, "failureClassification", "classification", "failureType", "rootCauseClassification"),
            null,
            metadata,
            evidence,
            context
        );
        putEntity(entityNodes, MemoryGraphEntityType.BUSINESS_ENTITY, value(metadata, "businessEntity", "businessObject", "entity", "entityName", "resource"), moduleScope, metadata, evidence, context);
        if (isFactType(metadata, "business_precondition_fact") && entityNodes.containsKey(MemoryGraphEntityType.BUSINESS_ENTITY)) {
            putEntity(
                entityNodes,
                MemoryGraphEntityType.PRECONDITION,
                firstNonBlank(value(metadata, "precondition", "businessPrecondition", "preconditionKey"), memory.getSummary()),
                moduleScope,
                metadata,
                evidence,
                context
            );
        }
        putEntity(entityNodes, MemoryGraphEntityType.FACT_TYPE, value(metadata, "factType"), null, metadata, evidence, context);

        for (var tag : memory.getTags()) {
            var tagNode = upsertEntity(MemoryGraphEntityType.TAG, tag, null, evidence, context);
            if (tagNode != null) {
                tagNodes.add(tagNode);
            }
        }

        for (var entityNode : entityNodes.values()) {
            if (entityNode != null && entityNode.getEntityType() != MemoryGraphEntityType.MEMORY_FACT) {
                upsertEdge(memoryNode, entityNode, MemoryGraphRelationType.MEMORY_MENTIONS_ENTITY, evidence, context);
                upsertEdge(entityNode, memoryNode, MemoryGraphRelationType.ENTITY_RELATED_TO_MEMORY, evidence, context);
            }
        }
        for (var tagNode : tagNodes) {
            upsertEdge(memoryNode, tagNode, MemoryGraphRelationType.MEMORY_MENTIONS_ENTITY, evidence, context);
            upsertEdge(tagNode, memoryNode, MemoryGraphRelationType.ENTITY_RELATED_TO_MEMORY, evidence, context);
        }

        relate(entityNodes, MemoryGraphEntityType.API_PATH, MemoryGraphEntityType.MODULE, MemoryGraphRelationType.API_BELONGS_TO_MODULE, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.MODULE, MemoryGraphEntityType.SYSTEM, MemoryGraphRelationType.MODULE_BELONGS_TO_SYSTEM, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.API_PATH, MemoryGraphEntityType.HTTP_METHOD, MemoryGraphRelationType.API_USES_HTTP_METHOD, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.ERROR_CODE, MemoryGraphEntityType.API_PATH, MemoryGraphRelationType.ERROR_OBSERVED_ON_API, evidence, context);
        relate(memoryNode, entityNodes.get(MemoryGraphEntityType.FAILURE_CLASSIFICATION), MemoryGraphRelationType.FAILURE_CLASSIFIED_AS, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.BUSINESS_ENTITY, MemoryGraphEntityType.API_PATH, MemoryGraphRelationType.BUSINESS_ENTITY_RELATED_TO_API, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.BUSINESS_ENTITY, MemoryGraphEntityType.PRECONDITION, MemoryGraphRelationType.BUSINESS_ENTITY_REQUIRES_PRECONDITION, evidence, context);
        relate(memoryNode, entityNodes.get(MemoryGraphEntityType.FACT_TYPE), MemoryGraphRelationType.FACT_HAS_TYPE, evidence, context);
        for (var tagNode : tagNodes) {
            relate(memoryNode, tagNode, MemoryGraphRelationType.FACT_HAS_TAG, evidence, context);
        }
    }

    private void putEntity(
        Map<MemoryGraphEntityType, MemoryGraphNode> entityNodes,
        MemoryGraphEntityType entityType,
        String value,
        String scope,
        Map<String, Object> metadata,
        MemoryGraphProvenance evidence,
        ProjectionContext context
    ) {
        var node = upsertEntity(entityType, value, scope, evidence, context);
        if (node != null) {
            entityNodes.put(entityType, node);
        }
    }

    private MemoryGraphNode upsertEntity(
        MemoryGraphEntityType entityType,
        String value,
        String scope,
        MemoryGraphProvenance evidence,
        ProjectionContext context
    ) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        var normalized = normalize(entityType, value);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return upsertNode(entityType, value.trim(), normalized, scope, Map.of("projectedFrom", "long-term-memory"), evidence, context);
    }

    private MemoryGraphNode upsertNode(
        MemoryGraphEntityType entityType,
        String displayValue,
        String normalizedValue,
        String scope,
        Map<String, Object> metadata,
        MemoryGraphProvenance provenance,
        ProjectionContext context
    ) {
        var nodeId = nodeId(entityType, normalizedValue, scope);
        var node = nodes.findById(nodeId).orElseGet(() -> {
            var created = new MemoryGraphNode();
            created.setNodeId(nodeId);
            created.setEntityType(entityType);
            created.setNormalizedValue(normalizedValue);
            created.setScope(scope);
            created.setFirstSeenAt(provenance.lastSeenAt());
            context.createdNodeIds.add(nodeId);
            return created;
        });
        node.setDisplayValue(displayValue);
        node.setMetadata(new LinkedHashMap<>(metadata));
        node.setSourceMemoryIds(merge(node.getSourceMemoryIds(), provenance.sourceMemoryIds()));
        node.setSourceRefs(merge(node.getSourceRefs(), provenance.sourceRefs()));
        node.setFactFingerprints(merge(node.getFactFingerprints(), provenance.factFingerprints()));
        node.setEvidenceSummaries(merge(node.getEvidenceSummaries(), provenance.evidenceSummaries()));
        node.setOccurrenceCount(node.getSourceMemoryIds().size());
        node.setConfidence(Math.max(node.getConfidence(), provenance.confidence()));
        node.setLastSeenAt(provenance.lastSeenAt());
        return nodes.save(node);
    }

    private MemoryGraphEdge upsertEdge(
        MemoryGraphNode source,
        MemoryGraphNode target,
        MemoryGraphRelationType relationType,
        MemoryGraphProvenance provenance,
        ProjectionContext context
    ) {
        if (source == null || target == null) {
            return null;
        }
        var edgeId = edgeId(source.getNodeId(), relationType, target.getNodeId());
        var edge = edges.findById(edgeId).orElseGet(() -> {
            var created = new MemoryGraphEdge();
            created.setEdgeId(edgeId);
            created.setSourceNodeId(source.getNodeId());
            created.setTargetNodeId(target.getNodeId());
            created.setRelationType(relationType);
            created.setCreatedAt(provenance.lastSeenAt());
            context.createdEdgeIds.add(edgeId);
            return created;
        });
        edge.setSourceMemoryIds(merge(edge.getSourceMemoryIds(), provenance.sourceMemoryIds()));
        edge.setSourceRefs(merge(edge.getSourceRefs(), provenance.sourceRefs()));
        edge.setFactFingerprints(merge(edge.getFactFingerprints(), provenance.factFingerprints()));
        edge.setEvidenceSummaries(merge(edge.getEvidenceSummaries(), provenance.evidenceSummaries()));
        edge.setOccurrenceCount(edge.getSourceMemoryIds().size());
        edge.setConfidence(Math.max(edge.getConfidence(), provenance.confidence()));
        edge.setUpdatedAt(provenance.lastSeenAt());
        return edges.save(edge);
    }

    private void relate(
        Map<MemoryGraphEntityType, MemoryGraphNode> entityNodes,
        MemoryGraphEntityType sourceType,
        MemoryGraphEntityType targetType,
        MemoryGraphRelationType relationType,
        MemoryGraphProvenance provenance,
        ProjectionContext context
    ) {
        relate(entityNodes.get(sourceType), entityNodes.get(targetType), relationType, provenance, context);
    }

    private void relate(
        MemoryGraphNode source,
        MemoryGraphNode target,
        MemoryGraphRelationType relationType,
        MemoryGraphProvenance provenance,
        ProjectionContext context
    ) {
        if (source != null && target != null) {
            upsertEdge(source, target, relationType, provenance, context);
        }
    }

    private Map<String, Object> metadata(LongTermMemory memory) {
        return memory.getMetadata() == null ? Map.of() : new LinkedHashMap<>(memory.getMetadata());
    }

    private String value(Map<String, Object> metadata, String... keys) {
        for (var key : keys) {
            var direct = metadata.get(key);
            if (direct != null && StringUtils.hasText(direct.toString())) {
                return direct.toString();
            }
        }
        var identityHints = metadata.get("identityHints");
        if (identityHints instanceof Map<?, ?> hints) {
            for (var key : keys) {
                var nested = hints.get(key);
                if (nested != null && StringUtils.hasText(nested.toString())) {
                    return nested.toString();
                }
            }
        }
        return null;
    }

    private boolean isFactType(Map<String, Object> metadata, String expected) {
        var factType = value(metadata, "factType");
        return StringUtils.hasText(factType) && normalize(MemoryGraphEntityType.FACT_TYPE, factType).equals(normalize(MemoryGraphEntityType.FACT_TYPE, expected));
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    static String normalize(MemoryGraphEntityType entityType, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        var trimmed = value.trim();
        return switch (entityType) {
            case API_PATH -> normalizeApiPath(trimmed);
            case HTTP_METHOD -> trimmed.toUpperCase(Locale.ROOT);
            case ERROR_CODE, FAILURE_CLASSIFICATION, POLICY_REASON, FACT_TYPE -> normalizeCode(trimmed);
            case TAG, TOOL_NAME, SYSTEM, MODULE, BUSINESS_ENTITY, VARIABLE_KEY, SOURCE_PATH, SUITE_ID, CASE_ID,
                ROOT_STEP_ID, DOWNSTREAM_STEP_ID, PRECONDITION -> normalizeGeneral(trimmed);
            case MEMORY_FACT -> trimmed;
        };
    }

    static String normalizeGeneral(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizeCode(String value) {
        return value.trim().replace('-', '_').replaceAll("\\s+", "_").toUpperCase(Locale.ROOT);
    }

    private static String normalizeApiPath(String value) {
        var normalized = value.trim().replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    static String nodeId(MemoryGraphEntityType entityType, String normalizedValue, String scope) {
        return "gn:" + sha256(entityType.name() + "|" + normalizedValue + "|" + (scope == null ? "" : scope)).substring(0, 32);
    }

    static String edgeId(String sourceNodeId, MemoryGraphRelationType relationType, String targetNodeId) {
        return "ge:" + sha256(sourceNodeId + "|" + relationType.name() + "|" + targetNodeId).substring(0, 32);
    }

    static String scope(String... values) {
        var parts = new ArrayList<String>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (StringUtils.hasText(values[index + 1])) {
                parts.add(values[index] + ":" + values[index + 1]);
            }
        }
        return parts.isEmpty() ? null : String.join("|", parts);
    }

    private List<String> merge(List<String> existing, List<String> incoming) {
        var merged = new LinkedHashSet<String>();
        if (existing != null) {
            merged.addAll(existing);
        }
        if (incoming != null) {
            merged.addAll(incoming);
        }
        return List.copyOf(merged);
    }

    private static String sha256(String material) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static final class ProjectionContext {
        private int processedMemoryCount;
        private int skippedMemoryCount;
        private final LinkedHashSet<String> createdNodeIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> createdEdgeIds = new LinkedHashSet<>();
        private final List<String> warnings = new ArrayList<>();
    }

    private record MemoryGraphProvenance(
        List<String> sourceMemoryIds,
        List<String> sourceRefs,
        List<String> factFingerprints,
        List<String> evidenceSummaries,
        double confidence,
        Instant lastSeenAt
    ) {
        private static MemoryGraphProvenance from(LongTermMemory memory) {
            var metadata = memory.getMetadata() == null ? Map.<String, Object>of() : memory.getMetadata();
            var sourceRefs = new LinkedHashSet<String>();
            collect(sourceRefs, memory.getSourceRef());
            collect(sourceRefs, metadata.get("sourceRef"));
            collect(sourceRefs, metadata.get("mergedSourceRefs"));

            var factFingerprints = new LinkedHashSet<String>();
            collect(factFingerprints, metadata.get("factFingerprint"));
            collect(factFingerprints, metadata.get("mergedFactFingerprints"));

            var evidenceSummaries = new LinkedHashSet<String>();
            collect(evidenceSummaries, metadata.get("evidenceSummary"));
            collect(evidenceSummaries, metadata.get("evidenceSummaries"));
            if (evidenceSummaries.isEmpty()) {
                collect(evidenceSummaries, memory.getSummary());
            }

            return new MemoryGraphProvenance(
                List.of(memory.getMemoryId()),
                List.copyOf(sourceRefs),
                List.copyOf(factFingerprints),
                List.copyOf(evidenceSummaries),
                confidence(memory),
                memory.getUpdatedAt() == null ? Instant.now() : memory.getUpdatedAt()
            );
        }

        private static double confidence(LongTermMemory memory) {
            var memoryConfidence = memory.getConfidence() == null ? 0.55d : memory.getConfidence();
            var importance = memory.getImportance() == null ? 0.50d : memory.getImportance();
            return Math.min(0.98d, (memoryConfidence * 0.70d) + (importance * 0.20d) + 0.10d);
        }

        private static void collect(LinkedHashSet<String> values, Object raw) {
            if (raw instanceof List<?> list) {
                for (var item : list) {
                    collect(values, item);
                }
                return;
            }
            if (raw != null && StringUtils.hasText(raw.toString())) {
                values.add(raw.toString().trim());
            }
        }
    }
}
