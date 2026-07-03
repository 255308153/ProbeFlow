package com.probeflow.testagent.memory;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class RedisSessionMemoryStore implements SessionMemoryStore {

    private final SessionMemoryItemRepository sessionMemories;

    public RedisSessionMemoryStore(SessionMemoryItemRepository sessionMemories) {
        this.sessionMemories = sessionMemories;
    }

    @Override
    public SessionMemoryItem save(SessionMemoryItem item) {
        return sessionMemories.save(item);
    }

    @Override
    public Optional<SessionMemoryItem> findById(String memoryId) {
        return sessionMemories.findById(memoryId);
    }

    @Override
    public List<SessionMemoryItem> findBySessionId(String sessionId) {
        return sessionMemories.findAllBySessionId(sessionId);
    }
}
