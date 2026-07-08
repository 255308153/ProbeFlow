package com.probeflow.testagent.knowledge;

public interface EmbeddingService {

    float[] embedDocument(String text);

    float[] embedQuery(String text);

    int dimensions();

    default EmbeddingProfile profile() {
        return EmbeddingProfile.fake(dimensions());
    }
}
