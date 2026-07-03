Status: ready-for-agent

# BATCH mode resumable multi-ApiSpec generation

## Parent

`../PRD.md`

## What to build

Add BATCH mode for module/system-level generation across many ApiSpecs. The service should generate drafts per ApiSpec, preserve per-target status, tolerate partial failures, and support repeated runs without duplicating work.

This slice makes Phase 5 usable for bootstrapping a larger API test suite while staying deterministic and bounded.

## Acceptance criteria

- [ ] Generation accepts BATCH mode with explicit target ApiSpec ids or module/system filters where supported by existing repositories.
- [ ] Each target ApiSpec receives an independent result status such as generated, updated, skipped, failed, incomplete, or blocked.
- [ ] A failure for one ApiSpec does not roll back successful generation for unrelated targets unless transaction boundaries require it explicitly and the result reports the behavior.
- [ ] Re-running BATCH mode is idempotent and reuses draft dedup behavior.
- [ ] BATCH mode can resume after partial failure and avoid duplicating previously completed target drafts.
- [ ] Coverage summary aggregates per-target and overall generation coverage.
- [ ] Tests verify multi-ApiSpec generation, partial failure handling, resumability, idempotency, and aggregate coverage through the application service seam.
- [ ] The slice does not implement promotion, HTTP execution, reports, frontend, or real LLM calls.

## Blocked by

- `05-coverage-summary-incomplete-blocking-diagnostics.md`
