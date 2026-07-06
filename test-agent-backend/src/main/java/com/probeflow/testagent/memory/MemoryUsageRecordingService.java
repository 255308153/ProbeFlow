package com.probeflow.testagent.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MemoryUsageRecordingService {

    private final MemoryUsageRecordRepository usageRecords;

    public MemoryUsageRecordingService(MemoryUsageRecordRepository usageRecords) {
        this.usageRecords = usageRecords;
    }

    @Transactional
    public List<MemoryUsageRecord> recordLongTermMemoryUsage(
        UnifiedContextQuery query,
        LongTermMemoryRetrievalResult longTermMemory,
        List<ContextCitation> citations
    ) {
        if (query == null || longTermMemory == null || longTermMemory.isEmpty() || !StringUtils.hasText(query.taskId())) {
            return List.of();
        }

        var citationByMemoryId = citations == null ? Map.<String, ContextCitation>of() : citations.stream()
            .filter(citation -> "long_term_memory".equals(citation.citationType()))
            .collect(Collectors.toMap(
                ContextCitation::sourceId,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
            ));
        var records = longTermMemory.hits().stream()
            .map(hit -> toRecord(query, hit, citationByMemoryId.get(hit.memoryId())))
            .toList();
        return usageRecords.saveAll(records);
    }

    private MemoryUsageRecord toRecord(UnifiedContextQuery query, LongTermMemoryRetrievalHit hit, ContextCitation citation) {
        var record = new MemoryUsageRecord();
        record.setMemoryId(hit.memoryId());
        record.setTaskId(query.taskId());
        record.setStageProfile(query.stageProfile());
        record.setConsumer(query.consumer());
        record.setSourceRef(sourceRef(query));
        record.setCitationType(citation == null ? "long_term_memory" : citation.citationType());
        record.setCitationSourceId(citation == null ? hit.memoryId() : citation.sourceId());
        record.setCitationSourceRef(citation == null ? hit.sourceRef() : citation.sourceRef());
        record.setScore(hit.score());
        record.setConfidence(hit.confidence());
        record.setLowConfidence(hit.lowConfidence());
        record.setMatchReasons(List.copyOf(hit.matchReasons()));
        record.setMetadata(metadata(query, hit));
        return record;
    }

    private Map<String, Object> metadata(UnifiedContextQuery query, LongTermMemoryRetrievalHit hit) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("apiSpecId", query.apiSpecId());
        metadata.put("systemName", query.systemName());
        metadata.put("moduleName", query.moduleName());
        metadata.put("apiPath", query.apiPath());
        metadata.put("errorCode", query.errorCode());
        metadata.put("queryTags", query.tags());
        metadata.put("memoryScopeType", hit.scopeType().name());
        metadata.put("memorySourceType", hit.sourceType().name());
        metadata.put("memorySourceRef", hit.sourceRef());
        metadata.put("tokenCount", hit.tokenCount());
        metadata.put("componentScores", hit.componentScores());
        return metadata.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private String sourceRef(UnifiedContextQuery query) {
        if (StringUtils.hasText(query.usageSourceRef())) {
            return query.usageSourceRef();
        }
        return "context-build:" + query.taskId() + ":" + query.stageProfile() + ":" + query.consumer().name().toLowerCase();
    }
}
