package com.probeflow.testagent.testcase;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, String> {
}
