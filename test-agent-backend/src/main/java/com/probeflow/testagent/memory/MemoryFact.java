package com.probeflow.testagent.memory;

import java.util.List;
import java.util.Map;

public record MemoryFact(
    MemoryFactType factType,
    String summary,
    String content,
    String fullContent,
    String applicability,
    String trigger,
    List<String> tags,
    Map<String, Object> identityHints,
    List<MemoryFactEvidence> evidenceEntries,
    Float confidence,
    Float importance,
    Float reuseScore,
    MemoryFactQualityStatus qualityStatus,
    String rejectionReason,
    String fingerprint
) {}
