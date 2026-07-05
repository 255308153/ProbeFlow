Status: ready-for-agent

# Issue 07: Execution feedback into Task Memory

## Parent

Phase 7: Failure Analysis and Feedback Loop PRD

## What to build

Write compact, useful analysis findings into Task Memory so the current task can reuse failure explanations, warnings, retry suggestions, and next actions later in the workflow.

This slice should avoid turning Task Memory into a log dump. Memory writes should be selective, deduplicated, and tied to analysis output.

## Acceptance criteria

- [ ] Meaningful failed, blocked, errored, and passed-with-warnings analyses can write Task Memory entries.
- [ ] Passed executions do not create Task Memory by default unless they provide useful contrast or stability evidence.
- [ ] Task Memory entries include classification, risk level, retryability, next action, execution id, case id, environment, and compact evidence metadata.
- [ ] Re-running analysis for the same execution does not create duplicate Task Memory entries.
- [ ] Task Memory scope type is selected consistently with the analysis classification.
- [ ] Task memory refinement status is updated when existing task fields support it cleanly.
- [ ] Tests verify memory write behavior, deduplication, metadata content, and no noisy passed-execution writes.

## Blocked by

- 03-observation-persistence-idempotent-analysis.md
- 04-retry-suggestion-next-action-recommendation.md
