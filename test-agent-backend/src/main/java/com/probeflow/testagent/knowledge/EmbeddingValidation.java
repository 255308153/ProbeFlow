package com.probeflow.testagent.knowledge;

public final class EmbeddingValidation {

    private EmbeddingValidation() {
    }

    public static String requireText(String usage, String text, EmbeddingProfile profile) {
        if (text == null || text.isBlank()) {
            throw new EmbeddingException(
                EmbeddingFailureCode.EMPTY_TEXT,
                profile.profileId(),
                usage + " embedding text must not be blank for profile " + profile.profileId()
            );
        }
        return text.trim();
    }

    public static float[] requireVector(
        String usage,
        EmbeddingProfile profile,
        float[] vector,
        int expectedDimension
    ) {
        if (vector == null || vector.length == 0) {
            throw new EmbeddingException(
                EmbeddingFailureCode.EMPTY_VECTOR,
                profile.profileId(),
                usage + " embedding vector must not be empty for profile " + profile.profileId()
            );
        }
        if (vector.length != expectedDimension) {
            throw new EmbeddingException(
                EmbeddingFailureCode.DIMENSION_MISMATCH,
                profile.profileId(),
                "embedding dimension mismatch for " + usage
                    + " using profile " + profile.profileId()
                    + ": expected " + expectedDimension
                    + " but was " + vector.length
            );
        }
        for (var value : vector) {
            if (!Float.isFinite(value)) {
                throw new EmbeddingException(
                    EmbeddingFailureCode.INVALID_RESPONSE,
                    profile.profileId(),
                    usage + " embedding vector contains non-finite values for profile " + profile.profileId()
                );
            }
        }
        return vector;
    }
}
