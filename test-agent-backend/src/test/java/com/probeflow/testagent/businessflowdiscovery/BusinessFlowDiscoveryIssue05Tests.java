package com.probeflow.testagent.businessflowdiscovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.HttpMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessFlowDiscoveryIssue05Tests {

    @Test
    void missingCriticalApiSpecReturnsBlockerAndRequiresHumanReview() {
        var result = new BusinessFlowDiscoveryService().discover(
            BusinessFlowDiscoveryRequest.deterministic(
                "order-suite-demo",
                List.of(
                    BusinessFlowDiscoveryIssue01Tests.apiSpec(
                        "api-order-create",
                        HttpMethod.POST,
                        "/api/orders",
                        "Create order"
                    ),
                    BusinessFlowDiscoveryIssue01Tests.apiSpec(
                        "api-order-query",
                        HttpMethod.GET,
                        "/api/orders/{orderId}",
                        "Query order"
                    )
                )
            )
        );

        var candidate = result.candidates().get(0);
        assertThat(result.status()).isEqualTo(BusinessFlowDiscoveryStatus.BLOCKED);
        assertThat(candidate.requiresHumanReview()).isTrue();
        assertThat(candidate.confidence()).isLessThan(0.70);
        assertThat(candidate.blockers())
            .extracting(BusinessFlowDiscoveryBlocker::code)
            .contains("MISSING_PAY_STEP");
    }

    @Test
    void lowConfidenceUnclearFlowRequiresHumanReviewInsteadOfPretendingExecutableSuite() {
        var result = new BusinessFlowDiscoveryService().discover(
            BusinessFlowDiscoveryRequest.deterministic(
                "unclear-demo",
                List.of(
                    BusinessFlowDiscoveryIssue01Tests.apiSpec(
                        "api-inventory-reserve",
                        HttpMethod.PATCH,
                        "/api/inventory/{skuId}/reserve",
                        "Reserve inventory"
                    ),
                    BusinessFlowDiscoveryIssue01Tests.apiSpec(
                        "api-coupon-apply",
                        HttpMethod.PATCH,
                        "/api/coupons/{couponId}/apply",
                        "Apply coupon"
                    )
                )
            )
        );

        var candidate = result.candidates().get(0);
        assertThat(result.status()).isEqualTo(BusinessFlowDiscoveryStatus.BLOCKED);
        assertThat(candidate.requiresHumanReview()).isTrue();
        assertThat(candidate.confidence()).isLessThan(0.70);
        assertThat(candidate.blockers())
            .extracting(BusinessFlowDiscoveryBlocker::code)
            .contains("MISSING_CREATE_STEP", "MISSING_PAY_STEP", "MISSING_QUERY_STEP");
        assertThat(candidate.metadata())
            .containsEntry("staysDiscoveryOnly", true)
            .containsEntry("doesNotGenerateSuiteVariables", true);
    }
}
