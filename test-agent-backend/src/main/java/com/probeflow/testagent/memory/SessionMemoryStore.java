package com.probeflow.testagent.memory;

import java.util.List;
import java.util.Optional;

public interface SessionMemoryStore {

    SessionMemoryItem save(SessionMemoryItem item);

    Optional<SessionMemoryItem> findById(String memoryId);

    List<SessionMemoryItem> findBySessionId(String sessionId);
}
