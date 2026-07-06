package com.probeflow.testagent.testcase;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, String> {

    List<TestCase> findAllByPrimaryApiSpecIdInOrderByCreatedAtAscCaseIdAsc(Collection<String> primaryApiSpecIds);
}
