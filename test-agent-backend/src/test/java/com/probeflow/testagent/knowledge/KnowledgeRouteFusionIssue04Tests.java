package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.retrieval.DeterministicQueryRewriteService;
import com.probeflow.testagent.retrieval.QueryRewriteRequest;
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
class KnowledgeRouteFusionIssue04Tests {

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    private final DeterministicQueryRewriteService rewriteService = new DeterministicQueryRewriteService();

    @Test
    void fusesDuplicateKnowledgeRoutesAndExplainsScoreContributions() {
        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Issue04 PAY_401 route fusion guide",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Charge authorization failure

                POST /api/payments/charge returns PAY_401 when merchantId signature is invalid.
                The route fusion case should retain paymentToken, merchantId, tags and the error guide citation.
                """,
            DocumentSourceType.WIKI,
            "wiki/issue04-pay-401-route-fusion.md",
            DocumentType.ERROR_CODE_GUIDE,
            DocumentAuthority.HIGH,
            "billing",
            "payment",
            "Payment",
            List.of("payment", "auth", "issue04"),
            List.of("failure_analysis"),
            Map.of(
                "frontmatterKeys", List.of("merchantId", "paymentToken"),
                "errorCodeHints", List.of("PAY_401"),
                "apiPathHints", List.of("/api/payments/charge"),
                "httpMethodHints", List.of("POST")
            )
        ));

        var rewrite = rewriteService.rewrite(new QueryRewriteRequest(
            "failure_analysis",
            "PAY_401 charge auth failure for merchant signature issue04",
            "explain payment charge auth failure",
            "billing",
            "payment",
            "/api/payments/charge",
            "POST",
            "Payment",
            "PAY_401",
            "AUTH_FAILURE",
            null,
            null,
            null,
            null,
            List.of("payment", "auth", "issue04")
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "PAY_401 charge auth failure for merchant signature issue04",
            "billing",
            "payment",
            "/api/payments/charge",
            "POST",
            "Payment",
            null,
            "failure_analysis",
            List.of("payment", "auth", "issue04"),
            4,
            500
        ), rewrite);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.diagnostics())
            .anySatisfy(diagnostic -> assertThat(diagnostic).startsWith("knowledge-fusion-deduplicated-routes:"));

        var hit = result.hits().getFirst();
        assertThat(hit.lowConfidence()).isFalse();
        assertThat(hit.routeEvidence())
            .extracting(KnowledgeRouteEvidence::routeName)
            .contains("original-semantic", "rewritten-semantic", "metadata-exact", "lexical-tag", "document-type");
        assertThat(hit.routeEvidence())
            .allSatisfy(evidence -> {
                assertThat(evidence.routeRank()).isPositive();
                assertThat(evidence.routeScore()).isGreaterThanOrEqualTo(0.0d);
                assertThat(evidence.queryVariantId()).startsWith("qv-");
                assertThat(evidence.matchReason()).isNotBlank();
            });

        assertThat(hit.metadata())
            .containsKeys(
                "routeEvidence",
                "routeNames",
                "queryVariantIds",
                "preFusionRank",
                "preFusionRanks",
                "candidateRank",
                "fusedScore",
                "fusionExplanation"
            );
        assertThat(((Number) hit.metadata().get("fusedScore")).doubleValue()).isEqualTo(hit.score());
        assertThat(hit.componentScores()).containsKeys("routeAgreement", "rankContribution");
        assertThat(hit.matchReasons()).contains("route-fusion", "route-agreement");

        var routeMetadata = routeMetadata(hit);
        assertThat(routeMetadata)
            .hasSize(hit.routeEvidence().size())
            .allSatisfy(route -> assertThat(route)
                .containsKeys(
                    "routeName",
                    "queryVariantId",
                    "routeRank",
                    "routeScore",
                    "matchReason",
                    "routeWeight",
                    "weightedRouteScore",
                    "rankContribution"
                ));

        var explanation = explanation(hit);
        assertThat(explanation.get("formula")).isEqualTo("bestWeightedRouteScore + rankContributionTotal + routeAgreementBonus");
        assertThat(((Number) explanation.get("routeCount")).intValue()).isEqualTo(hit.routeEvidence().size());
        assertThat(((Number) explanation.get("rankContributionTotal")).doubleValue()).isPositive();
        assertThat(((Number) explanation.get("routeAgreementBonus")).doubleValue()).isPositive();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> routeMetadata(KnowledgeRetrievalHit hit) {
        return (List<Map<String, Object>>) hit.metadata().get("routeEvidence");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> explanation(KnowledgeRetrievalHit hit) {
        return (Map<String, Object>) hit.metadata().get("fusionExplanation");
    }
}
