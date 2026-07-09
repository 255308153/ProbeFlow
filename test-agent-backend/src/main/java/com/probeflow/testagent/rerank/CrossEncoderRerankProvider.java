package com.probeflow.testagent.rerank;

public interface CrossEncoderRerankProvider {

    CrossEncoderRerankProviderResponse rerank(CrossEncoderRerankProviderRequest request);
}
