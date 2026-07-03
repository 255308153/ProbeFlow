Status: ready-for-agent

# HTTP execution entrypoint and fake-client SINGLE execution

## Parent

`../PRD.md`

## What to build

Build the first end-to-end Phase 6 execution path. A caller should be able to execute one selected TestCase under a Task through the HTTP execution application service, using a deterministic fake HTTP client, and receive a persisted ExecutionRecord linked from TaskCaseExecution.

This slice establishes the main execution seam, request/result vocabulary, fake transport boundary, and persistence lifecycle without yet implementing full request preparation, assertions, batch behavior, or real network transport.

## Acceptance criteria

- [ ] An HTTP execution application service accepts task id, selected case id, execution mode, environment name, dry-run flag, and basic execution options.
- [ ] The service resolves the Task and TestCase and rejects missing or invalid references clearly.
- [ ] The service executes through an HTTP client boundary that can be faked in tests.
- [ ] A successful fake-client execution persists an ExecutionRecord with task id, case id, executor type, environment, request snapshot, response snapshot, duration, status code, and overall status.
- [ ] TaskCaseExecution is created or updated for the task/case pair and linked to the created ExecutionRecord.
- [ ] The execution result includes per-case status, execution record id, duration, and aggregate counts.
- [ ] Tests verify the behavior through the application service seam without live network access.
- [ ] The slice does not implement full environment variable resolution, dry-run request validation, baseline assertions, batch execution, SUITE execution, Task Memory handoff, reports, frontend, browser automation, service direct invocation, DB direct assertions, or real LLM calls.

## Blocked by

None - can start immediately
