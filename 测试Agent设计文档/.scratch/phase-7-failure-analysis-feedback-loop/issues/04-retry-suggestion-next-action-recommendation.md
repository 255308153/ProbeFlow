Status: ready-for-agent

# Issue 04: Retry suggestion and next action recommendation

## Parent

Phase 7: Failure Analysis and Feedback Loop PRD

## What to build

Add retryability and next-action recommendations to Phase 7 analysis. The system should tell a future tester or orchestrator whether retrying is sensible and what the next human or agent action should be.

Recommendations should be deterministic, conservative, and grounded in classification evidence.

## Acceptance criteria

- [ ] The analysis marks likely transient transport failures, timeouts, 429, 502, 503, and 504 as retryable where evidence supports it.
- [ ] Deterministic assertion mismatches are non-retryable by default.
- [ ] Blocked requests recommend inspecting environment, URL, variable, or safety policy instead of retrying blindly.
- [ ] Auth-related failures recommend inspecting auth variables or token scope.
- [ ] Validation failures recommend reviewing request data, generated TestCase inputs, or API contract expectations.
- [ ] Server errors recommend investigating API regression or service health.
- [ ] Test asset drift evidence recommends updating TestCase expectations rather than reporting an API bug.
- [ ] The analysis result and Observation next suggestion expose the same recommendation consistently.
- [ ] Tests cover retryable and non-retryable paths plus next-action text for major classifications.

## Blocked by

- 02-deterministic-execution-failure-classification.md
- 03-observation-persistence-idempotent-analysis.md
