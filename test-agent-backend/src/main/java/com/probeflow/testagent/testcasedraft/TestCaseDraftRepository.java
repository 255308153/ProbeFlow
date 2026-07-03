package com.probeflow.testagent.testcasedraft;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseDraftRepository extends JpaRepository<TestCaseDraft, String> {

    List<TestCaseDraft> findByTaskIdAndDedupKeyOrderByCreatedAtAsc(String taskId, String dedupKey);
}
