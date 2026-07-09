package com.probeflow.testagent.retrieval;

import java.util.List;

public record QueryRewriteResult(
    List<QueryVariant> variants,
    List<String> diagnostics
) {

    public QueryRewriteResult {
        variants = variants == null ? List.of() : List.copyOf(variants);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
