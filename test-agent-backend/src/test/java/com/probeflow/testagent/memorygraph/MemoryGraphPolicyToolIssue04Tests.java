package com.probeflow.testagent.memorygraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.memory.LongTermMemory;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.MemoryStatus;
import com.probeflow.testagent.memory.MemoryType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MemoryGraphPolicyToolIssue04Tests {

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private MemoryGraphProjectionService projectionService;

    @Autowired
    private MemoryGraphQueryService queryService;

    @Autowired
    private MemoryGraphNodeRepository nodes;

    @Autowired
    private MemoryGraphEdgeRepository edges;

    @BeforeEach
    void clean() {
        edges.deleteAll();
        nodes.deleteAll();
        longTermMemories.deleteAll();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    @Test
    void projectsPolicyReasonToolRelationAndFiltersLowConfidencePolicyEdges() {
        longTermMemories.save(memory(
            "mem-v5-3-04-human-approval",
            "Human approval is required before shell command can delete workspace data",
            0.86f,
            0.78f,
            Map.of(
                "policyReason", " human-approval-required ",
                "toolName", "Shell_Command",
                "factType", "policy_learning",
                "factFingerprint", "policy:human-approval:shell-command",
                "evidenceSummary", "Policy validator rejected shell command until a human approved the risky action."
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-04-low-confidence",
            "Unconfirmed browser tool policy hint should not enter default graph expansion",
            0.10f,
            0.10f,
            Map.of(
                "policyReason", "human approval required",
                "toolName", "Browser Tool",
                "factType", "policy_learning",
                "factFingerprint", "policy:weak-browser",
                "evidenceSummary", "Single unconfirmed policy hint."
            )
        ));

        projectionService.rebuild();

        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.POLICY_REASON, "HUMAN_APPROVAL_REQUIRED"))
            .hasSize(1);
        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.TOOL_NAME, "shell command"))
            .hasSize(1);
        assertThat(edges.findAll())
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.POLICY_REASON_APPLIES_TO_TOOL))
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.FACT_HAS_TYPE))
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.FACT_HAS_TAG));

        var byReason = queryService.queryRelated(
            new MemoryGraphSeed(MemoryGraphEntityType.POLICY_REASON, "HUMAN approval required", null),
            3,
            20
        );
        assertThat(byReason.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::displayValue)
            .contains("Shell_Command")
            .doesNotContain("Browser Tool");
        assertThat(byReason.relatedMemories()).anySatisfy(memory -> {
            assertThat(memory.memoryId()).isEqualTo("mem-v5-3-04-human-approval");
            assertThat(memory.matchReason()).isEqualTo("graph-related-policy");
            assertThat(memory.relationPath()).contains(MemoryGraphRelationType.POLICY_REASON_APPLIES_TO_TOOL.name());
            assertThat(memory.confidence()).isGreaterThanOrEqualTo(MemoryGraphQueryService.DEFAULT_CONFIDENCE_GATE);
            assertThat(memory.sourceMemoryIds()).contains("mem-v5-3-04-human-approval");
            assertThat(memory.evidenceSummaries()).anyMatch(summary -> summary.contains("Policy validator rejected"));
        });
        assertThat(byReason.relatedMemories())
            .extracting(MemoryGraphRelatedMemory::memoryId)
            .doesNotContain("mem-v5-3-04-low-confidence");

        var byTool = queryService.queryRelated(
            new MemoryGraphSeed(MemoryGraphEntityType.TOOL_NAME, "shellCommand", null),
            3,
            20
        );
        assertThat(byTool.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::normalizedValue)
            .contains("HUMAN_APPROVAL_REQUIRED");
        assertThat(byTool.relatedMemories())
            .extracting(MemoryGraphRelatedMemory::memoryId)
            .contains("mem-v5-3-04-human-approval");
    }

    private LongTermMemory memory(String memoryId, String summary, float confidence, float importance, Map<String, Object> metadata) {
        var memory = new LongTermMemory();
        memory.setMemoryId(memoryId);
        memory.setMemoryType(MemoryType.LONG_TERM);
        memory.setScopeType(MemoryScopeType.PREFERENCE);
        memory.setSummary(summary);
        memory.setContent(summary + " content");
        memory.setFullContent(summary + " full evidence");
        memory.setTags(List.of("policy-learning", "human-approval"));
        memory.setSourceType(MemorySourceType.USER_FEEDBACK);
        memory.setSourceRef("policy-src:" + memoryId);
        memory.setConfidence(confidence);
        memory.setImportance(importance);
        memory.setSuccessContribution(0.35f);
        memory.setHitCount(0);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        memory.setEmbedding(new float[1024]);
        return memory;
    }
}
