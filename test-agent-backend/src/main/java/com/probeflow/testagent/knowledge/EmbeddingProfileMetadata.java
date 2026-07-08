package com.probeflow.testagent.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EmbeddingProfileMetadata {

    public static final String EMBEDDING_PROFILE = "embeddingProfile";
    public static final String CURRENT_EMBEDDING_PROFILE = "currentEmbeddingProfile";
    public static final String EMBEDDING_PROFILE_MISMATCH = "embeddingProfileMismatch";
    public static final String REINDEX_REQUIRED = "reindexRequired";
    public static final String REINDEX_REASON = "reindexReason";

    private EmbeddingProfileMetadata() {
    }

    public static Map<String, Object> withProfile(Map<String, Object> metadata, EmbeddingProfile profile) {
        var enriched = copy(metadata);
        enriched.put(EMBEDDING_PROFILE, toMetadata(profile));
        enriched.put(EMBEDDING_PROFILE_MISMATCH, false);
        enriched.put(REINDEX_REQUIRED, false);
        enriched.remove(CURRENT_EMBEDDING_PROFILE);
        enriched.remove(REINDEX_REASON);
        return enriched;
    }

    public static Map<String, Object> withReindexStatus(Map<String, Object> metadata, EmbeddingProfile currentProfile) {
        var enriched = copy(metadata);
        var compatible = isCompatible(enriched, currentProfile);
        enriched.put(EMBEDDING_PROFILE_MISMATCH, !compatible);
        enriched.put(REINDEX_REQUIRED, !compatible);
        if (!compatible) {
            enriched.put(CURRENT_EMBEDDING_PROFILE, toMetadata(currentProfile));
            enriched.put(REINDEX_REASON, "embedding-profile-mismatch");
        } else {
            enriched.remove(CURRENT_EMBEDDING_PROFILE);
            enriched.remove(REINDEX_REASON);
        }
        return enriched;
    }

    public static boolean isCompatible(Map<String, Object> metadata, EmbeddingProfile currentProfile) {
        if (metadata == null || currentProfile == null) {
            return false;
        }
        var stored = metadata.get(EMBEDDING_PROFILE);
        if (!(stored instanceof Map<?, ?> storedProfile)) {
            return false;
        }
        return Objects.equals(value(storedProfile, "profileId"), currentProfile.profileId())
            && Objects.equals(value(storedProfile, "providerMode"), currentProfile.providerMode().name())
            && Objects.equals(value(storedProfile, "model"), currentProfile.model())
            && Objects.equals(intValue(storedProfile, "dimension"), currentProfile.dimension());
    }

    public static Map<String, Object> toMetadata(EmbeddingProfile profile) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("profileId", profile.profileId());
        metadata.put("providerMode", profile.providerMode().name());
        metadata.put("model", profile.model());
        metadata.put("dimension", profile.dimension());
        metadata.put("configSource", profile.configSource());
        metadata.put("queryPrefix", profile.queryPrefix());
        metadata.put("documentPrefix", profile.documentPrefix());
        return metadata;
    }

    private static LinkedHashMap<String, Object> copy(Map<String, Object> metadata) {
        return metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    private static String value(Map<?, ?> metadata, String key) {
        var value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer intValue(Map<?, ?> metadata, String key) {
        var value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
