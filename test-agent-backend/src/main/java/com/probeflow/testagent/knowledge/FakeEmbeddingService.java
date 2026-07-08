package com.probeflow.testagent.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FakeEmbeddingService implements EmbeddingService {

    private final int dimension;
    private final EmbeddingProfile profile;

    public FakeEmbeddingService(@Value("${probeflow.embedding.dimension:1024}") int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("embedding dimension must be positive");
        }
        this.dimension = dimension;
        this.profile = EmbeddingProfile.fake(dimension);
    }

    @Override
    public float[] embedDocument(String text) {
        var normalized = EmbeddingValidation.requireText("document", text, profile);
        return EmbeddingValidation.requireVector(
            "document",
            profile,
            embed("document", profile.documentPrefix() + normalized),
            dimension
        );
    }

    @Override
    public float[] embedQuery(String text) {
        var normalized = EmbeddingValidation.requireText("query", text, profile);
        return EmbeddingValidation.requireVector(
            "query",
            profile,
            embed("query", profile.queryPrefix() + normalized),
            dimension
        );
    }

    @Override
    public int dimensions() {
        return dimension;
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }

    private float[] embed(String mode, String text) {
        var vector = new float[dimension];
        var baseSeed = (mode + "\n" + text).getBytes(StandardCharsets.UTF_8);
        var vectorIndex = 0;
        var counter = 0;
        while (vectorIndex < dimension) {
            var digest = sha256(baseSeed, counter++);
            for (int offset = 0; offset <= digest.length - 4 && vectorIndex < dimension; offset += 4) {
                var raw = ((digest[offset] & 0xff) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
                vector[vectorIndex++] = raw / (float) Integer.MAX_VALUE;
            }
        }
        return vector;
    }

    private byte[] sha256(byte[] baseSeed, int counter) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(baseSeed);
            digest.update((byte) (counter >>> 24));
            digest.update((byte) (counter >>> 16));
            digest.update((byte) (counter >>> 8));
            digest.update((byte) counter);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

}
