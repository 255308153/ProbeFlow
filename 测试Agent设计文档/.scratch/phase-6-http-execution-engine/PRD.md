Status: ready-for-agent

# Phase 6: HTTP Execution Engine PRD

## Problem Statement

Phase 1 已经完成 ProbeFlow 后端骨架、核心领域模型和执行记录持久化模型。Phase 2 已经完成 SourceMaterial 导入和 ApiSpec 分析。Phase 3 已经完成 Knowledge RAG。Phase 4 已经完成 Memory System 与 Unified Context Builder。Phase 5 已经完成测试用例生成系统，能够把 ApiSpec、Knowledge 和 Memory 转成结构化 TestCaseDraft，并 promotion 成正式 TestCase。

系统现在已经能回答：

- 目标系统有哪些 HTTP API？
- 每个 API 应该生成哪些测试用例？
- 每条测试用例的请求形状、预期状态码、验证提示和上下文来源是什么？

但系统还不能真正执行这些测试用例。

从用户角度看，ProbeFlow 目前已经可以生成一批像样的 API 测试资产，但还停留在“可读、可审核、可准备执行”的阶段。用户仍然需要手工把 TestCase 转成 HTTP 请求，手工填充环境变量，手工发请求，再手工记录响应和执行结果。这会导致几个问题：

- TestCase 与真实执行结果之间没有闭环。
- 执行请求和响应没有标准化快照，后续失败分析无法稳定复用。
- TaskCaseExecution 与 ExecutionRecord 不能自动串联。
- 批量执行缺少可恢复、可跳过、可统计的应用服务入口。
- Assertion 仍然只是生成阶段的提示，没有一个最小可用的状态码/响应形状检查结果。
- Memory Refinery 和后续报告阶段没有可靠的执行事实输入。

Phase 6 需要实现 HTTP 执行引擎，让 ProbeFlow 从“生成测试用例”升级到“可以安全、可追踪地执行 HTTP API 测试并记录结果”。

## Solution

实现 Phase 6 的 HTTP Execution Engine，以 `HttpExecutionApplicationService` 作为最高层应用服务 seam。

Phase 6 的核心流程是：

```text
Task + selected TestCase ids + environment profile
-> resolve TestCase and ApiSpec
-> build executable HTTP request
-> resolve environment variables and auth placeholders
-> execute through HTTP client boundary
-> capture request/response snapshot
-> evaluate baseline assertions
-> persist ExecutionRecord
-> update TaskCaseExecution
-> return execution summary
```

Phase 6 的重点是可控执行，而不是完整测试平台。它应该提供：

- 单个 TestCase 执行。
- 多个 TestCase 执行。
- Task 范围内的 selected cases 执行。
- 环境配置和变量替换。
- 请求构建和安全校验。
- HTTP client 抽象，测试中使用 fake client，不依赖真实外部服务。
- 请求/响应快照。
- 基础 assertion evaluation：状态码、响应体存在性、JSON 字段存在性、简单等值匹配、响应时间阈值。
- ExecutionRecord 持久化。
- TaskCaseExecution 状态更新。
- 执行摘要和失败分类。

推荐测试 seams：

- 最高层 seam：`HttpExecutionApplicationService`
- 支撑 seam：`HttpClientGateway` 或等价 HTTP client 边界
- 支撑 seam：`ExecutableRequestBuilder` 或等价请求构建边界
- 支撑 seam：`BaselineAssertionEvaluator` 或等价最小断言评估边界

测试应优先通过最高层应用服务验证外部行为：给定 Task、TestCase、环境配置和 fake HTTP response，断言请求构建、HTTP 调用、ExecutionRecord、TaskCaseExecution、assertion results 和 summary 的行为。

真实网络执行可以通过可替换 HTTP client boundary 支持，但 CI 和单元/集成测试必须使用 deterministic fake client。Phase 6 不引入前端、不生成最终报告、不做浏览器/UI 自动化、不做服务 direct invocation、不做 DB direct assertions。

## User Stories

1. As a tester, I want ProbeFlow to execute a generated TestCase, so that I can verify whether the target HTTP API behaves as expected.
2. As a tester, I want ProbeFlow to execute multiple selected TestCases, so that I can run a small regression batch.
3. As a tester, I want ProbeFlow to execute TestCases under a Task, so that execution results are tied to the current testing workflow.
4. As a tester, I want each execution to create an ExecutionRecord, so that request, response, assertions, duration, and status are preserved.
5. As a tester, I want TaskCaseExecution to point to the created ExecutionRecord, so that task-level progress can be tracked.
6. As a tester, I want HTTP requests to be built from TestCase detail and steps, so that generated cases become executable without manual translation.
7. As a tester, I want ApiSpec data to fill missing HTTP method, path, auth, and parameter structure, so that execution can use contract information.
8. As a tester, I want environment profile values to fill base URL and variables, so that the same TestCase can run against dev, test, or staging.
9. As a tester, I want auth placeholders to be resolved from environment inputs, so that protected APIs can be executed.
10. As a tester, I want unresolved variables to block execution clearly, so that unsafe or malformed requests are not sent.
11. As a tester, I want request snapshots to hide secrets, so that tokens and passwords are not stored in plain text.
12. As a tester, I want response snapshots to capture status code, headers, body excerpt, and body metadata, so that failures can be diagnosed.
13. As a tester, I want large responses to be truncated safely, so that the database does not store excessive payloads.
14. As a tester, I want binary responses to be represented by metadata instead of raw bytes, so that snapshots remain safe.
15. As a tester, I want execution duration recorded, so that slow APIs are visible.
16. As a tester, I want expected status code assertions evaluated, so that obvious pass/fail results are automatic.
17. As a tester, I want simple response JSON field assertions evaluated, so that generated validation hints become useful checks.
18. As a tester, I want response time threshold assertions evaluated, so that performance regressions can be detected at a basic level.
19. As a tester, I want assertion results stored as structured data, so that later failure analysis can inspect them.
20. As a tester, I want overall status to distinguish passed, failed, error, skipped, and blocked outcomes, so that execution results are actionable.
21. As a tester, I want network errors captured as execution errors, so that failures are not lost.
22. As a tester, I want timeouts captured as execution errors with duration, so that slow or unavailable APIs are visible.
23. As a tester, I want unsafe URLs blocked by policy, so that the executor cannot accidentally hit forbidden hosts.
24. As a tester, I want non-HTTP protocols rejected, so that V1 remains HTTP/HTTPS only.
25. As a tester, I want redirect behavior to be explicit, so that tests do not silently follow unexpected flows.
26. As a tester, I want retry behavior to be explicit and conservative, so that execution remains predictable.
27. As a tester, I want dry-run request preparation, so that I can validate the built request without sending it.
28. As a tester, I want skipped cases to be reported with reasons, so that batch runs remain understandable.
29. As a tester, I want batch execution to continue after one case fails when configured, so that one bad API does not hide the rest.
30. As a tester, I want batch execution to stop on critical failure when configured, so that dangerous runs can halt early.
31. As a tester, I want execution summary counts, so that I can see passed, failed, error, skipped, blocked, and total cases.
32. As a tester, I want execution order to be deterministic, so that repeated runs are comparable.
33. As a tester, I want SUITE cases to execute ordered steps, so that flow tests can run in the intended sequence.
34. As a tester, I want step-level request/response records or snapshots, so that multi-step flow failures are diagnosable.
35. As a tester, I want dependent suite steps to stop after a failed prerequisite when configured, so that invalid follow-up requests are avoided.
36. As a tester, I want generated TestCases to remain unchanged by execution, so that execution records are separate from test assets.
37. As a tester, I want execution to update TaskCaseExecution status without mutating manual-edited TestCase definitions, so that assets remain stable.
38. As a tester, I want execution facts to be eligible for Task Memory or later Memory Refinery, so that useful failures can be learned later.
39. As a tester, I want execution records to include environment name, so that results are traceable to the target environment.
40. As a tester, I want execution records to include executor type, so that manual, fake, local, and future remote execution can be distinguished.
41. As a maintainer, I want the HTTP client hidden behind an interface, so that tests can run without network access.
42. As a maintainer, I want fake HTTP client behavior to be deterministic, so that CI remains stable.
43. As a maintainer, I want execution code to reuse existing ExecutionRecord and TaskCaseExecution models, so that Phase 1 persistence remains valuable.
44. As a maintainer, I want request building separate from HTTP transport, so that request validation can be tested without sending traffic.
45. As a maintainer, I want assertion evaluation separate from transport, so that baseline assertions can evolve independently.
46. As a maintainer, I want no frontend UI in Phase 6, so that backend execution behavior lands first.
47. As a maintainer, I want no report rendering in Phase 6, so that reporting remains a later phase.
48. As a maintainer, I want no browser automation or UI automation in Phase 6, so that V1 stays focused on HTTP APIs.
49. As a maintainer, I want no service direct invocation or DB direct assertions in Phase 6, so that tests remain black-box HTTP tests.
50. As a future agent, I want Phase 6 acceptance boundary tests, so that execution behavior stays inside the intended scope.

## Implementation Decisions

- Build Phase 6 on top of the existing TestCase, ApiSpec, Task, ExecutionRecord, TaskCaseExecution, repository, and Phase 5 generation foundations.
- Add one primary HTTP execution application service as the highest-level entrypoint for callers.
- Execution requests should include task id, selected case ids, execution mode, environment name, environment variables, auth variables, timeout settings, dry-run flag, and batch behavior options.
- Execution results should include per-case status, execution record ids, assertion summaries, duration, skipped/blocked reasons, and aggregate counts.
- Add an HTTP client boundary so tests can use a fake implementation and production can later use a real implementation.
- Add a request builder boundary that converts TestCase detail, TestCase steps, ApiSpec data, environment values, and auth placeholders into executable HTTP request objects.
- Request building should validate method, URL, path, headers, query params, body, content type, and unresolved placeholders before transport.
- Request building should support dry-run mode that returns prepared request snapshots without sending network calls.
- Environment resolution should be explicit and deterministic. Missing required variables should block execution before transport.
- Secret redaction should happen before request snapshots are persisted.
- Response capture should store status code, selected headers, body excerpt or structured JSON preview, response size metadata, duration, and error information.
- Large response bodies should be truncated using a deterministic limit.
- Binary or unsupported response bodies should be represented by metadata rather than raw bytes.
- Baseline assertion evaluation should start with deterministic local rules: expected status code, response body presence, JSON field existence, simple JSON field equality, and duration threshold.
- Assertion results should be stored as structured entries in ExecutionRecord.
- Overall status should be derived from transport result and assertion result, not manually set by callers.
- Network exceptions, timeout, blocked request, invalid request, and assertion failure should be distinguishable in result status and error messages.
- TaskCaseExecution should be created or updated for each task/case execution and linked to the latest ExecutionRecord.
- Generated TestCase definitions should not be mutated by execution.
- SINGLE TestCases should execute one request.
- SUITE TestCases should execute ordered steps using the structured step data created by Phase 5.
- BATCH execution should process multiple selected cases in deterministic order.
- Batch execution should support continue-on-failure and stop-on-critical-failure behavior.
- Execution facts may be written to Task Memory if the existing memory service can be used without expanding scope too far; otherwise preserve enough data for a later memory phase.
- Do not introduce a frontend, REST controllers, report renderer, browser automation, UI automation, service direct invocation, DB direct assertion engine, real distributed worker, or new external infrastructure in Phase 6.
- Maintain the V1 boundary: HTTP/HTTPS REST API testing only.

## Testing Decisions

- Tests should verify external behavior through application service seams, not private helper methods.
- The highest-value test seam is the HTTP execution application service.
- Use fake HTTP client implementations in tests. Tests must not require live network access.
- Add tests where a seeded Task and TestCase execute through fake transport and persist ExecutionRecord and TaskCaseExecution.
- Add tests for request building from TestCase detail and ApiSpec fallback data.
- Add tests for environment variable resolution, auth placeholder resolution, unresolved variable blocking, and secret redaction.
- Add tests for dry-run behavior proving no HTTP transport call occurs.
- Add tests for status code assertion pass/fail behavior.
- Add tests for JSON field existence and equality assertion behavior.
- Add tests for duration threshold assertion behavior.
- Add tests for network error, timeout, invalid URL, unsafe protocol, and blocked host behavior.
- Add tests for response snapshot truncation and binary response metadata.
- Add tests for SINGLE case execution.
- Add tests for SUITE ordered step execution and prerequisite failure stop behavior.
- Add tests for BATCH deterministic ordering, continue-on-failure, stop-on-critical-failure, skipped cases, and aggregate counts.
- Add tests proving TestCase definitions are not mutated by execution.
- Add tests proving TaskCaseExecution links to the latest ExecutionRecord.
- Add acceptance boundary tests proving Phase 6 does not introduce frontend UI, report rendering, browser/UI automation, service direct invocation, DB direct assertions, real external LLM dependencies, or mandatory live network dependencies in CI.
- Reuse existing Spring Boot application service tests, repository tests, and phase boundary guard tests as prior art.
- Do not test real external APIs, production credentials, real report rendering, frontend behavior, browser automation, direct service calls, DB assertions, or distributed worker behavior in Phase 6.

## Out of Scope

- Final report rendering.
- Frontend UI.
- REST API controller layer.
- Browser automation.
- UI automation.
- Service direct invocation.
- DB direct assertions.
- Full assertion DSL.
- Schema validation engine.
- Contract diffing.
- Load testing or performance benchmarking beyond basic duration threshold.
- Distributed execution workers.
- Queue-backed async execution.
- Environment/secret management UI.
- Production credential vault integration.
- Postman/JMeter export.
- Real external LLM calls.
- Failure analysis narrative generation.
- Automatic bug ticket creation.
- Long-term Memory refinement from execution results, except for optional minimal Task Memory write if already easy through existing services.

## Further Notes

- Phase 6 is the first phase that may send network traffic. The implementation should be conservative: fake client in tests, explicit real-client boundary, dry-run support, environment validation, and secret redaction.
- The cleanest implementation path is to land request preparation first, then fake-client execution, then assertion results, then batch/suite behavior.
- Phase 7 should likely focus on failure analysis and execution-to-memory feedback, using Phase 6 ExecutionRecord data as the factual input.
- The existing untracked PRD publication workflow scratch directory is unrelated and should not be modified by this PRD.
