package com.probeflow.testagent.controlledplanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PlanDecisionParser {

    private final ObjectMapper objectMapper;

    public PlanDecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PlanDecision parse(String output) {
        var json = extractJsonObject(output);
        if (json == null) {
            return failed("Planner output was not structured JSON.", "NON_STRUCTURED_OUTPUT");
        }
        try {
            var root = objectMapper.readTree(json);
            if (!root.isObject()) {
                return failed("Planner output must be a JSON object.", "INVALID_JSON_SHAPE");
            }
            return parseDecision(root);
        } catch (Exception exception) {
            return failed("Planner output could not be parsed safely.", exception.getClass().getSimpleName());
        }
    }

    private PlanDecision parseDecision(JsonNode root) {
        var action = parseAction(requiredText(root, "action"));
        if (action == null) {
            return failed("Planner output used an unknown action.", "UNKNOWN_ACTION");
        }
        var reasoning = requiredText(root, "reasoning");
        if (reasoning == null) {
            return failed("Planner output is missing reasoning.", "MISSING_REASONING");
        }
        var confidence = requiredDouble(root, "confidence");
        if (confidence == null) {
            return failed("Planner output is missing numeric confidence.", "MISSING_CONFIDENCE");
        }
        var riskLevel = parseRiskLevel(requiredText(root, "riskLevel", "risk_level", "risk"));
        if (riskLevel == null) {
            return failed("Planner output is missing a valid risk level.", "MISSING_RISK_LEVEL");
        }
        try {
            return switch (action) {
                case CONTINUE -> new PlanDecision(
                    null,
                    PlanDecisionStatus.PROPOSED,
                    PlannerAction.CONTINUE,
                    reasoning,
                    confidence,
                    riskLevel,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    false
                );
                case INSERT_STEP -> insertStep(root, reasoning, confidence, riskLevel);
                case REPLAN -> PlanDecision.replan(reasoning, confidence, riskLevel, stringList(field(root, "blockers")));
                case WAIT_FOR_HUMAN -> waitForHuman(root, reasoning, confidence, riskLevel);
                case STOP -> PlanDecision.stop(reasoning, confidence, riskLevel, stringList(field(root, "blockers")));
            };
        } catch (IllegalArgumentException exception) {
            return failed("Planner output failed structural validation.", exception.getMessage());
        }
    }

    private PlanDecision insertStep(JsonNode root, String reasoning, double confidence, ToolRiskLevel riskLevel) {
        var stepNode = field(root, "proposedPlanStep", "proposed_plan_step", "proposedStep", "proposed_step");
        if (stepNode == null || !stepNode.isObject()) {
            return failed("INSERT_STEP requires a proposed plan step.", "MISSING_PROPOSED_PLAN_STEP");
        }
        var proposedToolName = text(field(root, "proposedToolName", "proposed_tool_name", "toolName", "tool_name"));
        var stepToolName = text(field(stepNode, "proposedToolName", "proposed_tool_name", "toolName", "tool_name"));
        var proposedStep = ProposedPlanStep.of(
            requiredText(stepNode, "stepType", "step_type", "type"),
            requiredText(stepNode, "title"),
            text(field(stepNode, "description")),
            stepToolName == null ? proposedToolName : stepToolName
        );
        return PlanDecision.insertStep(
            reasoning,
            confidence,
            riskLevel,
            proposedToolName == null ? proposedStep.proposedToolName() : proposedToolName,
            proposedStep
        );
    }

    private PlanDecision waitForHuman(JsonNode root, String reasoning, double confidence, ToolRiskLevel riskLevel) {
        var inputNode = field(root, "requiredHumanInput", "required_human_input", "humanInput", "human_input");
        if (inputNode == null || !inputNode.isObject()) {
            return failed("WAIT_FOR_HUMAN requires actionable human input.", "MISSING_HUMAN_INPUT");
        }
        var schema = humanInputSchema(field(inputNode, "inputSchema", "input_schema", "fields"));
        if (schema.isEmpty()) {
            return failed("WAIT_FOR_HUMAN requires a non-empty input schema.", "MISSING_HUMAN_INPUT_SCHEMA");
        }
        var input = new RequiredHumanInput(
            requiredText(inputNode, "reason"),
            requiredText(inputNode, "question"),
            schema,
            booleanValue(field(inputNode, "blocking"), true)
        );
        return PlanDecision.waitForHuman(reasoning, confidence, riskLevel, input);
    }

    private List<HumanInputField> humanInputSchema(JsonNode schemaNode) {
        if (schemaNode == null || !schemaNode.isArray()) {
            return List.of();
        }
        var fields = new ArrayList<HumanInputField>();
        for (var node : schemaNode) {
            if (node.isObject()) {
                fields.add(new HumanInputField(
                    requiredText(node, "name"),
                    requiredText(node, "type"),
                    text(field(node, "description")),
                    booleanValue(field(node, "required"), true)
                ));
            }
        }
        return List.copyOf(fields);
    }

    private PlannerAction parseAction(String value) {
        if (value == null) {
            return null;
        }
        try {
            return PlannerAction.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private ToolRiskLevel parseRiskLevel(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ToolRiskLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Double requiredDouble(JsonNode root, String... names) {
        var node = field(root, names);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private String requiredText(JsonNode root, String... names) {
        return text(field(root, names));
    }

    private JsonNode field(JsonNode root, String... names) {
        if (root == null) {
            return null;
        }
        for (var name : names) {
            var node = root.get(name);
            if (node != null && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private String text(JsonNode node) {
        if (node == null || !node.isValueNode()) {
            return null;
        }
        var value = node.asText();
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean booleanValue(JsonNode node, boolean fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return Boolean.parseBoolean(node.asText());
        }
        return fallback;
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            var value = text(node);
            return value == null ? List.of() : List.of(value);
        }
        if (!node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        for (var item : node) {
            var value = text(item);
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private String extractJsonObject(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        var trimmed = output.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        var start = trimmed.indexOf('{');
        var end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private PlanDecision failed(String reasoning, String blocker) {
        return PlanDecision.failed(reasoning, List.of(blocker));
    }
}
