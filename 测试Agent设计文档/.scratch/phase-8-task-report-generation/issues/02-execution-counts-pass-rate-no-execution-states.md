Status: ready-for-agent

# Issue 02: Execution counts, pass rate, and no-execution states

## Parent

Phase 8: Task Report Generation PRD

## What to build

Extend task report generation to aggregate execution outcomes into stable summary counts. A generated report should show how many cases passed, failed, errored, warned, blocked, skipped or remain unexecuted, and should calculate pass rate from deterministic inputs. It should also surface tested API coverage, total execution duration where available, and slowest cases without dumping large raw execution snapshots.

The report should handle SINGLE, BATCH and SUITE executions consistently at the summary-count level, while deeper suite diagnostics remain for a later slice.

## Acceptance criteria

- [ ] Reports include total, passed, failed, warning, error, blocked, skipped and unexecuted counts where data supports them.
- [ ] Reports include deterministic pass rate.
- [ ] Reports include tested API coverage or equivalent coverage summary.
- [ ] Reports include total execution duration and slowest case information where available.
- [ ] Reports clearly explain generated-but-unexecuted cases and tasks with no execution records.
- [ ] Mixed SINGLE, BATCH and SUITE execution records are included in summary counts consistently.
- [ ] Tests cover all-passed, mixed outcome and no-execution states.

## Blocked by

- Issue 01: Report generation entrypoint and basic task report snapshot
