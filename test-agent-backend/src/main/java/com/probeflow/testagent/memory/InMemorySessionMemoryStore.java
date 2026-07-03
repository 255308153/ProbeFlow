package com.probeflow.testagent.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("test")
public class InMemorySessionMemoryStore implements SessionMemoryStore {

    private final Map<String, SessionMemoryItem> items = new ConcurrentHashMap<>();

    @Override
    public SessionMemoryItem save(SessionMemoryItem item) {
        var copy = copyOf(item);
        var memoryId = copy.getMemoryId();
        items.put(memoryId, copy);
        return copyOf(copy);
    }

    @Override
    public Optional<SessionMemoryItem> findById(String memoryId) {
        return Optional.ofNullable(items.get(memoryId)).map(this::copyOf);
    }

    @Override
    public List<SessionMemoryItem> findBySessionId(String sessionId) {
        return items.values().stream()
            .filter(item -> sessionId.equals(item.getSessionId()))
            .sorted(Comparator.comparing(SessionMemoryItem::getCreatedAt).thenComparing(SessionMemoryItem::getMemoryId))
            .map(this::copyOf)
            .toList();
    }

    private SessionMemoryItem copyOf(SessionMemoryItem item) {
        var copy = new SessionMemoryItem();
        copy.setMemoryId(item.getMemoryId());
        copy.setSessionId(item.getSessionId());
        copy.setScopeType(item.getScopeType());
        copy.setSummary(item.getSummary());
        copy.setContent(item.getContent());
        copy.setTags(new ArrayList<>(item.getTags()));
        copy.setSourceType(item.getSourceType());
        copy.setSourceRef(item.getSourceRef());
        copy.setConfidence(item.getConfidence());
        copy.setStatus(item.getStatus());
        copy.setMetadata(new LinkedHashMap<>(item.getMetadata()));
        copy.setCreatedAt(item.getCreatedAt());
        copy.setExpiresAt(item.getExpiresAt());
        copy.setTtlSeconds(item.getTtlSeconds());
        return copy;
    }
}
