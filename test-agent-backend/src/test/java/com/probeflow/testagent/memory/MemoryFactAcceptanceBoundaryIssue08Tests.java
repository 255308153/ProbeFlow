package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
import com.probeflow.testagent.llm.FakeLlmProvider;
import com.probeflow.testagent.llm.LlmRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryFactAcceptanceBoundaryIssue08Tests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private MemoryFactExtractor memoryFactExtractor;

    @Autowired
    private Environment environment;

    @MockBean
    private FakeLlmProvider fakeLlmProvider;

    @Test
    void dependencyBoundaryDoesNotIncludeMem0OrVikingDb() throws IOException {
        var pom = Files.readString(Path.of("pom.xml")).toLowerCase(Locale.ROOT);

        assertThat(pom)
            .doesNotContain("mem0")
            .doesNotContain("vikingdb")
            .doesNotContain("viking-db")
            .doesNotContain("volcengine.viking");
    }

    @Test
    void defaultConfigurationUsesDeterministicExtractorAndDoesNotCallRealLlm() {
        var result = memoryRefineryService.refine(reusablePaymentCandidate("issue08-default-a", "issue08-task-a", 0.84f));

        assertThat(environment.getProperty("probeflow.llm.allow-real-providers")).isEqualTo("false");
        assertThat(environment.getProperty("probeflow.llm.allowed-providers")).isEqualTo("fake");
        assertThat(environment.getProperty("probeflow.memory.fact-extractor.mode")).isEqualTo("deterministic");
        assertThat(memoryFactExtractor).isInstanceOf(DeterministicMemoryFactExtractor.class);
        assertThat(result.accepted()).isTrue();
        verify(fakeLlmProvider, never()).generate(any(LlmRequest.class));
    }

    @Test
    void feedbackSourcesUseRefineryAndDoNotDirectlyWriteLongTermMemory() throws IOException {
        var feedbackSource = Files.readString(Path.of(
            "src/main/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackApplicationService.java"
        ));

        assertThat(feedbackSource).contains("MemoryRefineryService");
        assertThat(feedbackSource)
            .doesNotContain("LongTermMemoryRepository")
            .doesNotContain("new LongTermMemory(");
    }

    @Test
    void candidateToLongTermMemoryPathIncludesFactExtractionEvidenceLedgerAndEmbeddingProfile() {
        var accepted = memoryRefineryService.refine(reusablePaymentCandidate("issue08-fact-a", "issue08-task-b1", 0.84f));
        var merged = memoryRefineryService.refine(reusablePaymentCandidate("issue08-fact-b", "issue08-task-b2", 0.88f));

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.memory().metadata())
            .containsEntry("factType", "failure_pattern")
            .containsEntry("qualityStatus", "ACCEPTED")
            .containsEntry("evidenceCount", 1)
            .containsEntry("mergeCount", 1)
            .containsKey("factFingerprint")
            .containsKey("factApplicability")
            .containsKey("factTrigger")
            .containsKey("evidenceLedger")
            .containsKey(EmbeddingProfileMetadata.EMBEDDING_PROFILE);

        assertThat(merged.accepted()).isTrue();
        assertThat(merged.created()).isFalse();
        assertThat(merged.duplicateSuppressed()).isTrue();
        assertThat(merged.memory().memoryId()).isEqualTo(accepted.memory().memoryId());
        assertThat(merged.memory().metadata())
            .containsEntry("evidenceCount", 2)
            .containsEntry("mergeCount", 2)
            .containsKey(EmbeddingProfileMetadata.EMBEDDING_PROFILE);
        assertThat(longTermMemories.findAll()).hasSize(1);
    }

    @Test
    void lowQualityCandidatesDoNotWriteLongTermMemory() {
        var rejected = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Remember this",
            "Be careful.",
            MemorySourceType.EXECUTION_RESULT,
            "issue08-low-quality",
            "issue08-task-c",
            List.of("payment"),
            0.84f,
            "generic evidence",
            Map.of()
        ));

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.rejectionReason()).isEqualTo("too-generic");
        assertThat(longTermMemories.findAll()).isEmpty();
    }

    @Test
    void identityConflictDoesNotMergeOrCreateDuplicateMemory() {
        var first = memoryRefineryService.refine(conflictCandidate("issue08-conflict-a", "issue08-task-d1", "payment", "GW_TIMEOUT"));
        var conflict = memoryRefineryService.refine(conflictCandidate("issue08-conflict-b", "issue08-task-d2", "refund", "GW_TIMEOUT"));

        assertThat(first.accepted()).isTrue();
        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.rejectionReason()).isEqualTo("identity-conflict");
        assertThat(conflict.auditSummary()).containsEntry("reason", "identity-conflict");
        assertThat(longTermMemories.findAll()).hasSize(1);
    }

    @Test
    void futurePhaseBoundariesAreAbsentFromV5_2Implementation() throws IOException {
        var mainSources = readAllMainSources().toLowerCase(Locale.ROOT);

        assertThat(mainSources)
            .doesNotContain("mem0")
            .doesNotContain("vikingdb")
            .doesNotContain("viking-db")
            .doesNotContain("memoryentitygraph")
            .doesNotContain("memory entity graph")
            .doesNotContain("multi-route")
            .doesNotContain("multiroute")
            .doesNotContain("reciprocal rank fusion")
            .doesNotContain("rrf")
            .doesNotContain("crossencoder")
            .doesNotContain("cross-encoder")
            .doesNotContain("llmrerank")
            .doesNotContain("llm rerank")
            .doesNotContain("smalltobig")
            .doesNotContain("small-to-big");
    }

    private MemoryCandidateRequest reusablePaymentCandidate(String sourceRef, String taskId, float confidence) {
        return new MemoryCandidateRequest(
            "Payment GW_TIMEOUT requires retry fixture",
            "Payment gateway timeout failures require the approved retry fixture before assertions.",
            MemorySourceType.EXECUTION_RESULT,
            sourceRef,
            taskId,
            List.of("payment", "timeout", "retry"),
            confidence,
            "Execution evidence: POST /api/payments/charge returned GW_TIMEOUT until retry fixture was enabled.",
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "errorCode", "GW_TIMEOUT"
            )
        );
    }

    private MemoryCandidateRequest conflictCandidate(String sourceRef, String taskId, String module, String errorCode) {
        return new MemoryCandidateRequest(
            "Gateway timeout requires retry fixture",
            "Gateway timeout failures require the approved retry fixture before assertions.",
            MemorySourceType.EXECUTION_RESULT,
            sourceRef,
            taskId,
            List.of("timeout", "retry"),
            0.86f,
            "Comparable gateway timeout evidence from " + sourceRef,
            Map.of(
                "systemName", "billing",
                "module", module,
                "apiPath", "/api/payments/charge",
                "errorCode", errorCode
            )
        );
    }

    private String readAllMainSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            var builder = new StringBuilder();
            for (var path : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                builder.append(Files.readString(path)).append('\n');
            }
            return builder.toString();
        }
    }
}
