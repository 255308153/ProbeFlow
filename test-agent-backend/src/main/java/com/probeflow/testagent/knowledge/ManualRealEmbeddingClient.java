package com.probeflow.testagent.knowledge;

@FunctionalInterface
public interface ManualRealEmbeddingClient {

    ManualRealEmbeddingClientResponse embed(ManualRealEmbeddingClientRequest request);
}
