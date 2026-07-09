package com.probeflow.testagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.memory.MemoryFactType;
import org.junit.jupiter.api.Test;

class StageRoutingProfileIssue06Tests {

    private final StageRoutingProfileCatalog catalog = new StageRoutingProfileCatalog();

    @Test
    void apiAnalysisProfileFavorsStructureAndKnowledgeDocumentTypes() {
        var profile = catalog.profileFor("api-analysis");

        assertThat(profile.stageProfile()).isEqualTo("api_analysis");
        assertThat(profile.enabledRoutes()).contains(
            RetrievalRoute.METADATA_EXACT,
            RetrievalRoute.ORIGINAL_SEMANTIC,
            RetrievalRoute.REWRITTEN_SEMANTIC,
            RetrievalRoute.DOCUMENT_TYPE
        );
        assertThat(profile.weightFor(RetrievalRoute.METADATA_EXACT))
            .isGreaterThan(profile.weightFor(RetrievalRoute.SEMANTIC_MEMORY));
        assertThat(profile.documentTypePreferences()).containsExactly(
            DocumentType.BUSINESS_FLOW,
            DocumentType.API_NOTE,
            DocumentType.DOMAIN_RULE
        );
        assertThat(profile.diagnostic()).contains("metadata exact");
    }

    @Test
    void stageProfilesExpressDistinctDocumentAndMemoryPreferences() {
        var caseGeneration = catalog.profileFor("case_generation");
        var failureAnalysis = catalog.profileFor("failure_analysis");
        var suiteRecovery = catalog.profileFor("suite_recovery");
        var planner = catalog.profileFor("planner_policy");
        var report = catalog.profileFor("report");

        assertThat(caseGeneration.documentTypePreferences())
            .contains(DocumentType.TEST_SPEC, DocumentType.DOMAIN_RULE, DocumentType.BUSINESS_FLOW);
        assertThat(caseGeneration.factTypePreferences())
            .contains(MemoryFactType.TESTING_PATTERN);

        assertThat(failureAnalysis.documentTypePreferences())
            .contains(DocumentType.ERROR_CODE_GUIDE, DocumentType.INCIDENT_POSTMORTEM);
        assertThat(failureAnalysis.factTypePreferences())
            .contains(MemoryFactType.FAILURE_PATTERN);
        assertThat(failureAnalysis.enabledRoutes()).contains(RetrievalRoute.GRAPH_MEMORY, RetrievalRoute.EXACT_ENTITY);

        assertThat(suiteRecovery.documentTypePreferences()).contains(DocumentType.BUSINESS_FLOW);
        assertThat(suiteRecovery.factTypePreferences())
            .contains(MemoryFactType.SUITE_DEPENDENCY_FACT, MemoryFactType.VARIABLE_EXTRACTION_FACT);
        assertThat(suiteRecovery.enabledRoutes()).contains(RetrievalRoute.GRAPH_MEMORY);

        assertThat(planner.factTypePreferences())
            .contains(MemoryFactType.POLICY_LEARNING, MemoryFactType.PREFERENCE);
        assertThat(planner.diagnostic()).contains("toolName", "policyReason");

        assertThat(report.factTypePreferences())
            .contains(MemoryFactType.FAILURE_PATTERN, MemoryFactType.PROJECT_KNOWLEDGE);
        assertThat(report.diagnostic()).contains("citations");
    }

    @Test
    void unknownStageFallsBackToDefaultWithoutDroppingSafetyGatesOrBudgets() {
        var profile = catalog.profileFor("custom_exploration");

        assertThat(profile.stageProfile()).isEqualTo("custom_exploration");
        assertThat(profile.enabledRoutes())
            .contains(RetrievalRoute.ORIGINAL_SEMANTIC, RetrievalRoute.REWRITTEN_SEMANTIC);
        assertThat(profile.fallbackStrategy()).contains("fallback-to-original-semantic");
        assertThat(profile.budgetPolicy().enforceStatusGate()).isTrue();
        assertThat(profile.budgetPolicy().enforceConfidenceGate()).isTrue();
        assertThat(profile.budgetPolicy().enforcePermissionGate()).isTrue();
        assertThat(profile.budgetPolicy().totalCandidateLimit()).isPositive();
        assertThat(profile.budgetPolicy().tokenBudget()).isPositive();
        assertThat(profile.limitFor(RetrievalRoute.ORIGINAL_SEMANTIC)).isPositive();
    }

    @Test
    void profileDiagnosticsExplainEnabledRoutesAndNeverRequireRealLlm() {
        var profile = catalog.profileFor("failure_analysis");

        assertThat(profile.diagnostic())
            .contains("failure analysis")
            .contains("error-code")
            .contains("graph evidence");
        assertThat(profile.diagnostic().toLowerCase()).doesNotContain("deepseek", "llm", "cross encoder");
    }
}
