Status: ready-for-agent

# Issue 04: API_TEST automatic workflow from analysis to report

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Wire the automatic API_TEST workflow end to end. In automatic mode, an API_TEST task should proceed through analysis, context preparation, case generation, automatic draft promotion, HTTP execution, failure analysis when needed and final report generation. The workflow should use the existing Phase 1-8 services and persist progress through Task, PlanStep, TestCase, TaskCaseExecution, ExecutionRecord, Observation and Report records as those services already define them.

This slice should demonstrate ProbeFlow's first deterministic full backend task loop.

## Acceptance criteria

- [ ] API_TEST automatic tasks follow the expected V1 PlanStep order.
- [ ] Generated drafts are promoted automatically where the existing promotion service supports it.
- [ ] HTTP execution is invoked through the existing execution service.
- [ ] Failed, errored, blocked or warning executions trigger failure analysis where needed.
- [ ] All-passed executions do not perform unnecessary failure analysis.
- [ ] Report generation runs at the end of the workflow.
- [ ] The orchestration result includes a Report id when the workflow completes with a report.
- [ ] Tests cover an automatic API_TEST workflow through the orchestration service using seeded or fakeable dependencies.

## Blocked by

- Issue 01: Task initialization and deterministic template planning
- Issue 02: Orchestration entrypoint and PlanStep execution lifecycle
- Issue 03: Deterministic PlanStep routing to existing services
