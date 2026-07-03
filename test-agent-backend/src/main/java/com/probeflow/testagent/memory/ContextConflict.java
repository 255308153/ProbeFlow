package com.probeflow.testagent.memory;

public record ContextConflict(
    String conflictType,
    String conflictKey,
    ContextConflictSide knowledgeSide,
    ContextConflictSide memorySide,
    String preferredSourceType
) {
}
