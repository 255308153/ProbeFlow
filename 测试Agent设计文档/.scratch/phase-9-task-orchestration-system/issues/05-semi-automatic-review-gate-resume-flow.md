Status: ready-for-agent

# Issue 05: Semi-automatic review gate and resume flow

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Support the semi-automatic API_TEST flow where orchestration pauses after case generation and waits for human review. A task in manual promotion mode should move to WAITING_FOR_REVIEW after drafts are generated, avoid execution while review is pending, and resume after approved drafts have been promoted. Manual review decisions such as discarded drafts should be respected.

This slice makes the V1 review gate explicit without building a UI.

## Acceptance criteria

- [ ] Manual promotion API_TEST tasks pause after case generation.
- [ ] Paused tasks enter WAITING_FOR_REVIEW and do not execute HTTP cases.
- [ ] The orchestration result clearly indicates that the task is waiting for review.
- [ ] A task can resume after drafts are promoted.
- [ ] Discarded drafts remain discarded and are not promoted or executed.
- [ ] Review gate behavior is represented through Task and PlanStep status rather than frontend-specific state.
- [ ] Tests cover pause, no-execution-while-pending, and resume-after-review behavior.

## Blocked by

- Issue 04: API_TEST automatic workflow from analysis to report
