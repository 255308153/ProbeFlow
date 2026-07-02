package com.probeflow.testagent.memory;

import org.springframework.data.repository.CrudRepository;

public interface SessionMemoryItemRepository extends CrudRepository<SessionMemoryItem, String> {
}
