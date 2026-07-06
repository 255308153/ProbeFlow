package com.probeflow.testagent.llm;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplateRegistry {

    private final Map<String, PromptTemplate> templatesById;
    private final Map<String, List<PromptTemplate>> templatesByPurpose;

    public PromptTemplateRegistry() {
        this(defaultTemplates());
    }

    public PromptTemplateRegistry(List<PromptTemplate> templates) {
        var safeTemplates = templates == null ? List.<PromptTemplate>of() : List.copyOf(templates);
        this.templatesById = safeTemplates.stream()
            .collect(Collectors.toUnmodifiableMap(
                PromptTemplate::templateId,
                Function.identity(),
                (left, right) -> {
                    throw new IllegalArgumentException("Duplicate prompt template id: " + left.templateId());
                }
            ));
        this.templatesByPurpose = safeTemplates.stream()
            .collect(Collectors.groupingBy(
                PromptTemplate::purpose,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    grouped -> grouped.stream()
                        .sorted(Comparator.comparing(PromptTemplate::version).reversed()
                            .thenComparing(PromptTemplate::templateId))
                        .toList()
                )
            ));
    }

    public Optional<PromptTemplate> findById(String templateId) {
        return Optional.ofNullable(templatesById.get(clean(templateId)));
    }

    public Optional<PromptTemplate> findLatestByPurpose(String purpose) {
        var templates = templatesByPurpose.get(clean(purpose));
        if (templates == null || templates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(templates.getFirst());
    }

    public PromptRenderResult renderById(String templateId, Map<String, ?> variables) {
        return findById(templateId)
            .map(template -> template.render(variables))
            .orElseGet(() -> PromptRenderResult.templateNotFound(templateId));
    }

    public PromptRenderResult renderLatestByPurpose(String purpose, Map<String, ?> variables) {
        return findLatestByPurpose(purpose)
            .map(template -> template.render(variables))
            .orElseGet(() -> PromptRenderResult.templateNotFound(purpose));
    }

    private static List<PromptTemplate> defaultTemplates() {
        return List.of(
            new PromptTemplate(
                "v2.failure-insight.v1",
                "FAILURE_INSIGHT",
                "v1",
                "Explain the API test failure for task {{taskId}} using evidence: {{evidence}}",
                List.of("taskId", "evidence"),
                "Return concise structured JSON with fields summary, likelyCause, nextAction."
            ),
            new PromptTemplate(
                "v2.report-narrative.v1",
                "REPORT_NARRATIVE",
                "v1",
                "Draft a concise report narrative for task {{taskId}} from summary: {{summary}}",
                List.of("taskId", "summary"),
                "Return plain text only; do not include markdown headings."
            ),
            new PromptTemplate(
                "v2.context-gap-question.v1",
                "CONTEXT_GAP_QUESTION",
                "v1",
                "Ask one clarifying question for task {{taskId}} when context gap is: {{gap}}",
                List.of("taskId", "gap"),
                "Return one question and no tool calls."
            ),
            new PromptTemplate(
                "v2.controlled-planner.v1",
                "CONTROLLED_PLANNER",
                "v1",
                """
                    You are ProbeFlow's Controlled Planner. Suggest the next plan decision without executing tools.
                    Task state: {{taskState}}
                    Current phase: {{currentPhase}}
                    Workflow mode: {{workflowMode}}
                    Last step outcome: {{lastStepOutcome}}
                    Context summary: {{contextSummary}}
                    Planner-safe tools: {{availableTools}}
                    Constraints: {{constraints}}
                    """,
                List.of(
                    "taskState",
                    "currentPhase",
                    "workflowMode",
                    "lastStepOutcome",
                    "contextSummary",
                    "availableTools",
                    "constraints"
                ),
                """
                    Return compact structured JSON for PlanDecision only, with action, reasoning, confidence, riskLevel,
                    optional proposedToolName/proposedPlanStep/blockers, and requiredHumanInput for WAIT_FOR_HUMAN.
                    Do not claim that any tool was executed.
                    """
            )
        );
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
