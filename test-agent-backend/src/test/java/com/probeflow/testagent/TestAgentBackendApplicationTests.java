package com.probeflow.testagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = TestAgentBackendApplication.class)
@ActiveProfiles("test")
class TestAgentBackendApplicationTests {

    @Test
    void applicationContextLoadsWithFlywayEnabled() {
    }
}
