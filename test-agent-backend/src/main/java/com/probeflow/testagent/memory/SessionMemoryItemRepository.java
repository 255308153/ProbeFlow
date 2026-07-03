package com.probeflow.testagent.memory;

import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface SessionMemoryItemRepository extends CrudRepository<SessionMemoryItem, String> {

    List<SessionMemoryItem> findAllBySessionId(String sessionId);
}
