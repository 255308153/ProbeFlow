Status: ready-for-agent

# Issue 02: Deterministic execution failure classification

## Parent

Phase 7: Failure Analysis and Feedback Loop PRD

## What to build

Extend the single-record failure analysis path with deterministic classification rules. Given an ExecutionRecord, the analysis should classify the most likely failure category using execution status, status code, error message, response snapshot, request snapshot, and assertion results.

The classification should be evidence-based and conservative. When evidence is weak, the result should preserve uncertainty instead of pretending to prove a root cause.

## Acceptance criteria

- [ ] The analysis classifies status code mismatches separately from generic assertion failures.
- [ ] The analysis classifies JSON field missing and JSON field equality failures as response shape or response value mismatches.
- [ ] The analysis classifies body presence failures.
- [ ] The analysis classifies duration threshold failures or warnings.
- [ ] The analysis classifies transport errors and timeouts.
- [ ] The analysis classifies blocked requests caused by safety policy, invalid URL, unsupported protocol, or unresolved variable evidence.
- [ ] The analysis classifies 401/403 evidence as authentication or authorization related.
- [ ] The analysis classifies 400/422 evidence as likely validation or request contract related.
- [ ] The analysis classifies 5xx evidence as likely server error or regression risk.
- [ ] The analysis includes factual evidence used for classification.
- [ ] Tests cover each classification path through the application service seam.

## Blocked by

- 01-failure-analysis-entrypoint-single-execution-record.md
