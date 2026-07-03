Status: ready-for-agent

# Coverage summary and incomplete/blocking diagnostics

## Parent

`../PRD.md`

## What to build

Add generation diagnostics that tell the caller what coverage was produced, what was skipped, what remains missing, and why any target or scenario is incomplete or blocked. The result should help a human or agent decide the next action without inspecting raw drafts.

This slice makes the generation result useful as a planning artifact, not just a list of draft ids.

## Acceptance criteria

- [ ] Generation result includes coverage by scenario category for each target ApiSpec.
- [ ] Coverage can distinguish generated, updated, skipped, unsupported, incomplete, missing, and blocked categories.
- [ ] Structurally insufficient ApiSpecs are rejected or marked incomplete without creating unusable drafts.
- [ ] Low-confidence context and context conflicts are surfaced in coverage or diagnostic warnings.
- [ ] Missing context or unsupported scenario reasons are explicit and deterministic.
- [ ] The result includes enough summary data for later Task/PlanStep reporting without requiring HTTP execution.
- [ ] Tests verify full coverage, partial coverage, incomplete ApiSpec handling, blocked diagnostics, and warning output through the generation application service seam.
- [ ] The slice does not implement SUITE, BATCH, promotion, HTTP execution, reports, frontend, or real LLM calls.

## Blocked by

- `04-draft-deduplication-idempotent-regeneration.md`
