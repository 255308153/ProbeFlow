package com.probeflow.testagent.rerank;

import java.util.List;

public record RerankOutput(
    List<RerankOutputItem> items
) {
    public RerankOutput {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
