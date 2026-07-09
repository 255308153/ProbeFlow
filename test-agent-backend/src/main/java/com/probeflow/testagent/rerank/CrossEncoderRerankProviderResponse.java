package com.probeflow.testagent.rerank;

import java.util.List;

public record CrossEncoderRerankProviderResponse(
    List<CrossEncoderRerankProviderResult> results
) {
    public CrossEncoderRerankProviderResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
