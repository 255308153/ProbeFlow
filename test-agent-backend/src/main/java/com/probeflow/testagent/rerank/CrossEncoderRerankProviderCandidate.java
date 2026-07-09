package com.probeflow.testagent.rerank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record CrossEncoderRerankProviderCandidate(
    String candidateId,
    String text,
    Map<String, Object> metadata
) {
    public CrossEncoderRerankProviderCandidate {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        text = text == null ? "" : text;
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
