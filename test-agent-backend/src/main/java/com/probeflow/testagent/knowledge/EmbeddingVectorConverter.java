package com.probeflow.testagent.knowledge;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class EmbeddingVectorConverter implements AttributeConverter<float[], String> {

    @Override
    public String convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) {
            return null;
        }

        var builder = new StringBuilder(attribute.length * 8);
        builder.append('[');
        for (int index = 0; index < attribute.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(attribute[index]));
        }
        builder.append(']');
        return builder.toString();
    }

    @Override
    public float[] convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }

        var trimmed = dbData.trim();
        if (trimmed.length() < 2) {
            return new float[0];
        }

        var body = trimmed.substring(1, trimmed.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }

        var parts = body.split(",");
        var vector = new float[parts.length];
        for (int index = 0; index < parts.length; index++) {
            vector[index] = Float.parseFloat(parts[index]);
        }
        return vector;
    }
}
