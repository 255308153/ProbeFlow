Status: ready-for-agent

# Issue 05: Suite failure analysis and skipped-step explanation

## Parent

Phase 7: Failure Analysis and Feedback Loop PRD

## What to build

Extend failure analysis to understand SUITE ExecutionRecord snapshots. The system should identify the first failing step, distinguish the real prerequisite failure from downstream skipped steps, and produce analysis that is useful for flow-test debugging.

This slice should reuse the same application service entrypoint and Observation persistence behavior established earlier.

## Acceptance criteria

- [ ] SUITE response snapshots are analyzed as ordered step results.
- [ ] The analysis identifies the first failed, errored, or blocked step.
- [ ] Downstream skipped steps caused by prerequisite failure are explained as dependent skips, not independent failures.
- [ ] The analysis result includes failed step id, step order, step API reference where available, and suite impact summary.
- [ ] Suite prerequisite failures receive an appropriate classification and risk level.
- [ ] Observation records for suite failures include the relevant step reference.
- [ ] Tests cover failed first step, failed middle step, downstream skipped steps, and all-skipped suite behavior.

## Blocked by

- 03-observation-persistence-idempotent-analysis.md
- 04-retry-suggestion-next-action-recommendation.md
