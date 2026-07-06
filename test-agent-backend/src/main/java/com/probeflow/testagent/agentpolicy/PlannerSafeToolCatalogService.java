package com.probeflow.testagent.agentpolicy;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PlannerSafeToolCatalogService {

    private final ToolContractRegistry registry;
    private final AgentPolicyService policyService;

    public PlannerSafeToolCatalogService(ToolContractRegistry registry, AgentPolicyService policyService) {
        this.registry = registry;
        this.policyService = policyService;
    }

    public List<PlannerSafeToolView> listForPlanner(AgentPolicy policy) {
        var effectivePolicy = policy == null ? AgentPolicy.v2Phase2Default() : policy;
        return registry.listAll().stream()
            .map(contract -> toView(contract, policyService.evaluate(contract.name(), effectivePolicy)))
            .sorted(Comparator.comparing(PlannerSafeToolView::name))
            .toList();
    }

    public List<PlannerSafeToolView> listAvailableForPlanner(AgentPolicy policy) {
        return listForPlanner(policy).stream()
            .filter(view -> !view.blocked())
            .toList();
    }

    private PlannerSafeToolView toView(ToolContract contract, ToolPolicyDecision decision) {
        return new PlannerSafeToolView(
            contract.name().value(),
            contract.capabilityGroup().name(),
            contract.description(),
            contract.inputSchema().fields().stream()
                .map(PlannerToolSchemaFieldView::from)
                .toList(),
            contract.inputSchema().example(),
            contract.outputSchema().summary(),
            contract.outputSchema().fields().stream()
                .map(PlannerToolSchemaFieldView::from)
                .toList(),
            contract.riskLevel().name(),
            contract.executionMode().name(),
            contract.humanConfirmationRequired(),
            contract.preconditionNames(),
            contract.tags().stream().sorted().toList(),
            decision.status(),
            decision.reasonCode(),
            decision.message()
        );
    }
}
