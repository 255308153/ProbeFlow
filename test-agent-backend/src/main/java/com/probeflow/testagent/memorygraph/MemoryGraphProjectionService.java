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
import java.util.TreeMap;
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
        projectFactGovernance(activeMemories, context);
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
        putEntity(entityNodes, MemoryGraphEntityType.SUITE_ID, value(metadata, "suiteId", "suite"), null, metadata, evidence, context);
        var suiteScope = scope("suite", normalizeGeneral(value(metadata, "suiteId", "suite")));
        putEntity(entityNodes, MemoryGraphEntityType.CASE_ID, value(metadata, "caseId", "testCaseId"), suiteScope, metadata, evidence, context);
        putEntity(entityNodes, MemoryGraphEntityType.ROOT_STEP_ID, value(metadata, "rootStepId", "producerStepId", "upstreamStepId", "stepId"), suiteScope, metadata, evidence, context);
        putEntity(entityNodes, MemoryGraphEntityType.DOWNSTREAM_STEP_ID, value(metadata, "downstreamStepId", "consumerStepId"), suiteScope, metadata, evidence, context);
        putEntity(entityNodes, MemoryGraphEntityType.VARIABLE_KEY, value(metadata, "variableKey", "suiteVariableKey", "targetKey"), suiteScope, metadata, evidence, context);
        putEntity(entityNodes, MemoryGraphEntityType.SOURCE_PATH, value(metadata, "sourcePath", "jsonPath", "responsePath"), suiteScope, metadata, evidence, context);
        putEntity(entityNodes, MemoryGraphEntityType.POLICY_REASON, value(metadata, "policyReason", "policyReasonCode", "reasonCode", "approvalReason"), null, metadata, evidence, context);
        putEntity(entityNodes, MemoryGraphEntityType.TOOL_NAME, value(metadata, "toolName", "tool", "toolId", "plannerToolName"), null, metadata, evidence, context);
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
        relate(entityNodes, MemoryGraphEntityType.SUITE_ID, MemoryGraphEntityType.CASE_ID, MemoryGraphRelationType.SUITE_CONTAINS_CASE, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.ROOT_STEP_ID, MemoryGraphEntityType.VARIABLE_KEY, MemoryGraphRelationType.SUITE_STEP_PRODUCES_VARIABLE, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.DOWNSTREAM_STEP_ID, MemoryGraphEntityType.VARIABLE_KEY, MemoryGraphRelationType.SUITE_STEP_CONSUMES_VARIABLE, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.VARIABLE_KEY, MemoryGraphEntityType.SOURCE_PATH, MemoryGraphRelationType.VARIABLE_EXTRACTED_FROM_SOURCE_PATH, evidence, context);
        relate(entityNodes, MemoryGraphEntityType.POLICY_REASON, MemoryGraphEntityType.TOOL_NAME, MemoryGraphRelationType.POLICY_REASON_APPLIES_TO_TOOL, evidence, context);
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
        node.setConfidence(governedConfidence(Math.max(node.getConfidence(), provenance.confidence()), node.getOccurrenceCount()));
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
        edge.setConfidence(governedConfidence(Math.max(edge.getConfidence(), provenance.confidence()), edge.getOccurrenceCount()));
        edge.setUpdatedAt(provenance.lastSeenAt());
        return edges.save(edge);
    }

    private void projectFactGovernance(List<LongTermMemory> memories, ProjectionContext context) {
        for (var pair : reinforcingPairs(memories)) {
            var source = memoryNode(pair.left());
            var target = memoryNode(pair.right());
            if (source != null && target != null) {
                upsertEdge(
                    source,
                    target,
                    MemoryGraphRelationType.FACT_REINFORCES_FACT,
                    MemoryGraphProvenance.combine(pair.left(), pair.right(), "shared fact identity reinforces both long-term memories"),
                    context
                );
            }
        }
        for (var memory : memories) {
            for (var targetMemoryId : conflictTargetMemoryIds(memory)) {
                var source = memoryNode(memory);
                var target = nodes.findById(nodeId(MemoryGraphEntityType.MEMORY_FACT, targetMemoryId, null)).orElse(null);
                if (source != null && target != null && !source.getNodeId().equals(target.getNodeId())) {
                    upsertEdge(
                        source,
                        target,
                        MemoryGraphRelationType.FACT_CONFLICTS_WITH_FACT,
                        MemoryGraphProvenance.from(memory).withEvidence("identity conflict audit points to " + targetMemoryId),
                        context
                    );
                }
            }
        }
    }

    private List<MemoryPair> reinforcingPairs(List<LongTermMemory> memories) {
        var pairs = new LinkedHashMap<String, MemoryPair>();
        for (int leftIndex = 0; leftIndex < memories.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < memories.size(); rightIndex++) {
                var left = memories.get(leftIndex);
                var right = memories.get(rightIndex);
                if (reinforces(left, right)) {
                    var orderedLeft = left.getMemoryId().compareTo(right.getMemoryId()) <= 0 ? left : right;
                    var orderedRight = orderedLeft == left ? right : left;
                    pairs.putIfAbsent(orderedLeft.getMemoryId() + "|" + orderedRight.getMemoryId(), new MemoryPair(orderedLeft, orderedRight));
                }
            }
        }
        return List.copyOf(pairs.values());
    }

    private boolean reinforces(LongTermMemory left, LongTermMemory right) {
        var leftMetadata = metadata(left);
        var rightMetadata = metadata(right);
        return intersects(values(leftMetadata.get("factFingerprint"), leftMetadata.get("mergedFactFingerprints")), values(rightMetadata.get("factFingerprint"), rightMetadata.get("mergedFactFingerprints")))
            || intersects(values(leftMetadata.get("sourceRef"), leftMetadata.get("mergedSourceRefs"), left.getSourceRef()), values(rightMetadata.get("sourceRef"), rightMetadata.get("mergedSourceRefs"), right.getSourceRef()))
            || identitySignature(leftMetadata).equals(identitySignature(rightMetadata)) && !identitySignature(leftMetadata).isEmpty();
    }

    private List<String> conflictTargetMemoryIds(LongTermMemory memory) {
        var metadata = metadata(memory);
        var targets = new LinkedHashSet<String>();
        collectValue(targets, metadata.get("conflictsWithMemoryId"));
        collectValue(targets, metadata.get("conflictingMemoryId"));
        collectValue(targets, metadata.get("conflictMemoryIds"));
        collectConflictAuditTargets(targets, metadata.get("identityConflictAudit"));
        collectConflictAuditTargets(targets, metadata.get("conflictAudit"));
        targets.remove(memory.getMemoryId());
        return List.copyOf(targets);
    }

    private void collectConflictAuditTargets(LinkedHashSet<String> targets, Object raw) {
        if (raw instanceof Map<?, ?> audit) {
            collectValue(targets, audit.get("existingMemoryId"));
            collectValue(targets, audit.get("conflictingMemoryId"));
            collectValue(targets, audit.get("conflictsWithMemoryId"));
            collectValue(targets, audit.get("memoryId"));
        } else if (raw instanceof List<?> list) {
            for (var item : list) {
                collectConflictAuditTargets(targets, item);
            }
        }
    }

    private MemoryGraphNode memoryNode(LongTermMemory memory) {
        return nodes.findById(nodeId(MemoryGraphEntityType.MEMORY_FACT, memory.getMemoryId(), null)).orElse(null);
    }

    private List<String> values(Object... rawValues) {
        var values = new LinkedHashSet<String>();
        for (var raw : rawValues) {
            collectValue(values, raw);
        }
        return List.copyOf(values);
    }

    private void collectValue(LinkedHashSet<String> values, Object raw) {
        if (raw instanceof List<?> list) {
            for (var item : list) {
                collectValue(values, item);
            }
            return;
        }
        if (raw != null && StringUtils.hasText(raw.toString())) {
            values.add(raw.toString().trim());
        }
    }

    private boolean intersects(List<String> left, List<String> right) {
        var normalizedLeft = left.stream().map(MemoryGraphProjectionService::normalizeGeneral).collect(java.util.stream.Collectors.toSet());
        return right.stream().map(MemoryGraphProjectionService::normalizeGeneral).anyMatch(normalizedLeft::contains);
    }

    private String identitySignature(Map<String, Object> metadata) {
        var identityHints = metadata.get("identityHints");
        if (!(identityHints instanceof Map<?, ?> hints) || hints.isEmpty()) {
            return "";
        }
        var normalized = new TreeMap<String, String>();
        for (var entry : hints.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && StringUtils.hasText(entry.getValue().toString())) {
                normalized.put(entry.getKey().toString(), normalizeGeneral(entry.getValue().toString()));
            }
        }
        return normalized.toString();
    }

    private double governedConfidence(double baseConfidence, int occurrenceCount) {
        return Math.min(0.98d, baseConfidence + Math.min(0.08d, Math.max(0, occurrenceCount - 1) * 0.02d));
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
            case TOOL_NAME -> normalizeName(trimmed);
            case TAG, SYSTEM, MODULE, BUSINESS_ENTITY, VARIABLE_KEY, SOURCE_PATH, SUITE_ID, CASE_ID,
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

    private static String normalizeName(String value) {
        return value.trim()
            .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
            .replaceAll("[-_]+", " ")
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
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

    private record MemoryPair(LongTermMemory left, LongTermMemory right) {
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
            collectEvidenceLedger(evidenceSummaries, metadata.get("evidenceLedger"));
            if (evidenceSummaries.isEmpty()) {
                collect(evidenceSummaries, memory.getSummary());
            }

            return new MemoryGraphProvenance(
                List.of(memory.getMemoryId()),
                List.copyOf(sourceRefs),
                List.copyOf(factFingerprints),
                List.copyOf(evidenceSummaries),
                confidence(memory, evidenceSummaries.size()),
                memory.getUpdatedAt() == null ? Instant.now() : memory.getUpdatedAt()
            );
        }

        private static MemoryGraphProvenance combine(LongTermMemory left, LongTermMemory right, String evidenceSummary) {
            return from(left).merge(from(right)).withEvidence(evidenceSummary);
        }

        private MemoryGraphProvenance merge(MemoryGraphProvenance other) {
            return new MemoryGraphProvenance(
                mergeLists(sourceMemoryIds, other.sourceMemoryIds),
                mergeLists(sourceRefs, other.sourceRefs),
                mergeLists(factFingerprints, other.factFingerprints),
                mergeLists(evidenceSummaries, other.evidenceSummaries),
                Math.max(confidence, other.confidence),
                lastSeenAt.isAfter(other.lastSeenAt) ? lastSeenAt : other.lastSeenAt
            );
        }

        private MemoryGraphProvenance withEvidence(String evidenceSummary) {
            var summaries = new LinkedHashSet<>(evidenceSummaries);
            if (StringUtils.hasText(evidenceSummary)) {
                summaries.add(evidenceSummary);
            }
            return new MemoryGraphProvenance(
                sourceMemoryIds,
                sourceRefs,
                factFingerprints,
                List.copyOf(summaries),
                confidence,
                lastSeenAt
            );
        }

        private static double confidence(LongTermMemory memory, int evidenceCount) {
            var memoryConfidence = memory.getConfidence() == null ? 0.55d : memory.getConfidence();
            var importance = memory.getImportance() == null ? 0.50d : memory.getImportance();
            var metadata = memory.getMetadata() == null ? Map.<String, Object>of() : memory.getMetadata();
            var humanConfirmed = humanConfirmed(memory, metadata) ? 0.05d : 0.0d;
            var evidenceBonus = Math.min(0.08d, Math.max(0, evidenceCount - 1) * 0.02d);
            return Math.min(0.98d, (memoryConfidence * 0.65d) + (importance * 0.20d) + 0.07d + evidenceBonus + humanConfirmed);
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

        private static void collectEvidenceLedger(LinkedHashSet<String> values, Object raw) {
            if (raw instanceof List<?> list) {
                for (var item : list) {
                    collectEvidenceLedger(values, item);
                }
                return;
            }
            if (raw instanceof Map<?, ?> evidence) {
                collect(values, evidence.get("summary"));
                collect(values, evidence.get("evidenceSummary"));
                collect(values, evidence.get("sourceRef"));
            }
        }

        private static boolean humanConfirmed(LongTermMemory memory, Map<String, Object> metadata) {
            return memory.getTags().stream()
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .anyMatch(tag -> tag.equals("human-feedback") || tag.equals("human-approved") || tag.equals("human-confirmed"))
                || Boolean.TRUE.equals(metadata.get("humanConfirmed"))
                || "approved".equalsIgnoreCase(String.valueOf(metadata.get("humanDecision")));
        }

        private static List<String> mergeLists(List<String> left, List<String> right) {
            var merged = new LinkedHashSet<String>();
            merged.addAll(left);
            merged.addAll(right);
            return List.copyOf(merged);
        }
    }
}
