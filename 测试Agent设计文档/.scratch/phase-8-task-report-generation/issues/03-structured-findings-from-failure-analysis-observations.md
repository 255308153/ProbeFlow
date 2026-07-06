Status: ready-for-agent

# Issue 03: Structured findings from failure analysis and observations

## Parent

Phase 8: Task Report Generation PRD

## What to build

Generate structured report findings from existing execution facts, failure analysis results and observations. Findings should make the report useful without forcing users to open every raw record: each finding should include severity, classification, affected API or case references, concise evidence, retryability where available, and source references back to ExecutionRecord, Observation, TestCase and ApiSpec ids when available.

Findings should be grouped and ordered deterministically so critical risks appear first and repeated failures are easier to inspect.

## Acceptance criteria

- [ ] Reports include structured findings, not only narrative text.
- [ ] Findings include severity, failure classification, affected case/API references and concise evidence.
- [ ] Findings reference ExecutionRecord ids where available.
- [ ] Findings reference Observation ids where available.
- [ ] Findings reference TestCase and ApiSpec ids where available.
- [ ] Findings distinguish factual execution evidence from inferred failure analysis.
- [ ] Findings are grouped or keyed by API, case and classification where useful.
- [ ] Findings are sorted deterministically by severity, classification and stable identifiers.
- [ ] Missing linked TestCase or ApiSpec data is surfaced as data-quality information instead of crashing report generation.
- [ ] Tests cover auth, validation, server error, blocked and missing-linked-data findings.

## Blocked by

- Issue 01: Report generation entrypoint and basic task report snapshot
- Issue 02: Execution counts, pass rate, and no-execution states
