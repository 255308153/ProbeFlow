package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.testcase.CaseCategory;
import com.probeflow.testagent.testcase.CasePriority;
import com.probeflow.testagent.testcase.CaseRiskLevel;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcase.CaseStatus;
import com.probeflow.testagent.testcase.DetailType;
import com.probeflow.testagent.testcase.StaleStatus;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcase.TestCaseRepository;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TestCaseAndDraftRepositoryTests {

    @Autowired
    private TestCaseRepository testCases;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private EntityManager entityManager;

    @Test
    void testCaseCanStoreFormalApiAssetWithSuiteStepSnapshotsAndStaleTracking() {
        var testCase = new TestCase();
        testCase.setPrimaryApiSpecId("api-spec-create-order");
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(TestCaseMode.SUITE);
        testCase.setTitle("order-suite-happy-path");
        testCase.setDescription("Create and pay an order through HTTP APIs");
        testCase.setPreconditions(List.of("user is logged in", "sku is available"));
        testCase.setExpectedResult("order is paid");
        testCase.setPriority(CasePriority.HIGH);
        testCase.setRiskLevel(CaseRiskLevel.CRITICAL);
        testCase.setTags(List.of("order", "payment", "regression"));
        testCase.setScenarioName("create-order-flow");
        testCase.setModuleName("order");
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(CaseSource.BUSINESS);
        testCase.setManualEdited(true);
        testCase.setLocked(true);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(Map.of(
            "method", "POST",
            "path", "/api/orders",
            "assertionDefinitions", List.of(Map.of("assertionType", "STATUS_CODE", "expectedValue", "200"))
        ));
        testCase.setSteps(List.of(
            Map.of(
                "order", 1,
                "stepName", "createOrder",
                "apiSpecId", "api-spec-create-order",
                "requestTemplate", Map.of("body", Map.of("skuId", "sku-1")),
                "extractRules", List.of(Map.of("sourcePath", "$.data.orderId", "targetKey", "orderId")),
                "critical", true
            ),
            Map.of(
                "order", 2,
                "stepName", "payOrder",
                "apiSpecId", "api-spec-pay-order",
                "requestTemplate", Map.of("body", Map.of("orderId", "${suite.orderId}")),
                "extractRules", List.of(),
                "critical", true
            )
        ));
        testCase.setStaleStatus(StaleStatus.FRESH);
        testCase.setBasedOnApiSpecVersions(Map.of("api-spec-create-order", 3, "api-spec-pay-order", 2));
        testCase.setGeneratedFromSingleCaseIds(List.of("single-create-order", "single-pay-order"));
        testCase.setUpdatedBy("tester");

        var saved = testCases.save(testCase);
        entityManager.flush();
        entityManager.clear();

        var loaded = testCases.findById(saved.getCaseId()).orElseThrow();

        assertThat(loaded.getPrimaryApiSpecId()).isEqualTo("api-spec-create-order");
        assertThat(loaded.getCaseCategory()).isEqualTo(CaseCategory.API);
        assertThat(loaded.getMode()).isEqualTo(TestCaseMode.SUITE);
        assertThat(loaded.getTags()).containsExactly("order", "payment", "regression");
        assertThat(loaded.getDetail()).containsEntry("path", "/api/orders");
        assertThat(loaded.getSteps()).hasSize(2);
        assertThat(loaded.getSteps().getFirst()).containsEntry("stepName", "createOrder");
        assertThat(loaded.getStaleStatus()).isEqualTo(StaleStatus.FRESH);
        assertThat(loaded.getBasedOnApiSpecVersions()).containsEntry("api-spec-create-order", 3);
        assertThat(loaded.getGeneratedFromSingleCaseIds()).containsExactly("single-create-order", "single-pay-order");
        assertThat(loaded.isManualEdited()).isTrue();
        assertThat(loaded.isLocked()).isTrue();
        assertThat(loaded.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(loaded.getUpdatedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void testCaseDraftCanStoreReviewPromotionDeduplicationAndExpectedStatusCode() {
        var draft = new TestCaseDraft();
        draft.setTaskId("task-generate-order-cases");
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(DraftStatus.PENDING_REVIEW);
        draft.setPromotionMode(PromotionMode.MANUAL);
        draft.setTargetApiSpecId("api-spec-create-order");
        draft.setDedupKey("boundary:quantity:min:400");
        draft.setExpectedStatusCode(400);
        draft.setDraftContent(Map.of(
            "title", "create-order-invalid-quantity",
            "detail", Map.of(
                "method", "POST",
                "path", "/api/orders",
                "inputData", Map.of("quantity", 0)
            )
        ));
        draft.setPromotedCaseId("case-promoted-after-review");

        var saved = drafts.save(draft);
        entityManager.flush();
        entityManager.clear();

        var loaded = drafts.findById(saved.getDraftId()).orElseThrow();

        assertThat(loaded.getTaskId()).isEqualTo("task-generate-order-cases");
        assertThat(loaded.getStatus()).isEqualTo(DraftStatus.PENDING_REVIEW);
        assertThat(loaded.getPromotionMode()).isEqualTo(PromotionMode.MANUAL);
        assertThat(loaded.getDedupKey()).isEqualTo("boundary:quantity:min:400");
        assertThat(loaded.getExpectedStatusCode()).isEqualTo(400);
        assertThat(loaded.getDraftContent()).containsEntry("title", "create-order-invalid-quantity");
        assertThat(loaded.getPromotedCaseId()).isEqualTo("case-promoted-after-review");
        assertThat(loaded.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
