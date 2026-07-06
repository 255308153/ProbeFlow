Status: ready-for-agent

# Phase 8: Task Report Generation PRD

## Problem Statement

Phase 1 已经完成 ProbeFlow 后端骨架、核心领域模型和 Report 持久化地基。Phase 2 已经完成 SourceMaterial 导入和 ApiSpec 分析。Phase 3 已经完成 Knowledge RAG。Phase 4 已经完成 Memory System 与 Unified Context Builder。Phase 5 已经完成测试用例生成系统。Phase 6 已经完成 HTTP Execution Engine。Phase 7 已经完成 Failure Analysis and Feedback Loop，能够把 ExecutionRecord 转成失败分类、风险等级、retry 建议、Observation、Task Memory 和高置信长期记忆候选。

系统现在已经能回答：

- 目标系统有哪些 HTTP API？
- 每个 API 生成了哪些 TestCase？
- 哪些 TestCase 被执行了？
- 每次执行产生了哪些 ExecutionRecord？
- 哪些执行失败、阻塞、跳过或带 warning？
- 失败原因、风险等级、retry 建议和下一步动作是什么？
- 哪些分析结果已经写入 Observation 和 Memory？

但系统还不能把这些分散事实汇总成一份稳定、可追踪、可复用的任务报告。

从用户角度看，ProbeFlow 已经能分析失败，但用户仍然需要在 Task、TestCase、TaskCaseExecution、ExecutionRecord、Observation、Task Memory、Long-term Memory 和 Knowledge 引用之间来回翻找，才能回答：

- 这次任务整体通过率是多少？
- 哪些 API 风险最高？
- 哪些失败是环境问题，哪些是服务端回归，哪些是用例过期？
- 哪些失败可以重试，哪些需要人工处理？
- 本次任务有哪些关键发现？
- 本次任务对下一轮测试生成、执行和记忆沉淀有什么建议？
- 报告是否可以被后续前端、Agent Loop 或团队协作模块稳定消费？

如果没有任务报告生成，ProbeFlow 的执行和分析结果仍然只是多个底层事实表。Phase 8 需要把这些事实聚合成结构化 Report，让 V1 具备“执行闭环后的可交付总结”。

## Solution

实现 Phase 8 的 Task Report Generation，以 `ReportGenerationApplicationService` 作为最高层应用服务 seam。

Phase 8 的核心流程是：

```text
Task id + report generation options
-> load Task, TestCase, TaskCaseExecution, ExecutionRecord
-> ensure or reuse FailureAnalysis/Observation data
-> aggregate execution counts and coverage
-> group findings by API, case, classification, risk and retryability
-> assemble summary, risk summary, findings and suggestions
-> persist Report
-> return ReportGenerationResult
```

Phase 8 的目标是生成**结构化报告数据**，不是生成最终视觉展示。报告应当能被后续前端、CLI、Agent Loop、Markdown/HTML/PDF renderer 或团队协作模块复用。

推荐测试 seam：

- 最高层 seam：`ReportGenerationApplicationService`
- 支撑 seam：`ReportAssembler` 或等价报告组装组件
- 支撑 seam：`ReportFindingPrioritizer` 或等价发现排序组件
- 支撑 seam：`ReportSuggestionBuilder` 或等价建议生成组件

测试应优先通过最高层应用服务验证外部行为：给定 Task、TestCase、TaskCaseExecution、ExecutionRecord、Observation 和 FailureAnalysis 结果，断言 Report 的 summary、counts、risk summary、findings、suggestions、source references 和持久化行为。

Phase 8 应复用现有 Report、ReportRepository、ExecutionRecord、TaskCaseExecution、Observation、FailureAnalysisApplicationService、Task Memory、Long-term Memory 和 Knowledge citation。不要在 Phase 8 引入前端、PDF/HTML 渲染、真实 LLM、Agent Loop、自动发通知或外部 ticket。

## User Stories

1. As a tester, I want ProbeFlow to generate a report for a Task, so that I can understand the outcome of a test run.
2. As a tester, I want the report to include total case count, so that I know the size of the task.
3. As a tester, I want the report to include passed, failed, warning, skipped, blocked and error counts, so that I can quickly assess quality.
4. As a tester, I want the report to include pass rate, so that I can compare runs over time.
5. As a tester, I want the report to include execution duration totals and slowest cases, so that performance risk is visible.
6. As a tester, I want the report to include environment name, so that results are tied to the correct target.
7. As a tester, I want the report to include source task metadata, so that I can trace the report back to the original task.
8. As a tester, I want the report to include tested API coverage, so that I know which target APIs were exercised.
9. As a tester, I want the report to include unexecuted or skipped TestCases, so that gaps are visible.
10. As a tester, I want the report to include failed API groups, so that repeated failures are easier to inspect.
11. As a tester, I want the report to include risk summary, so that critical risks are obvious.
12. As a tester, I want findings ordered by severity, so that I can focus on the most important problems first.
13. As a tester, I want findings grouped by API, so that service owners can review their own endpoints.
14. As a tester, I want findings grouped by failure classification, so that environment issues and product regressions are not mixed together.
15. As a tester, I want retryable failures called out, so that temporary failures can be retried safely.
16. As a tester, I want non-retryable failures called out, so that deterministic defects get human attention.
17. As a tester, I want auth failures summarized, so that credential and permission problems are easy to triage.
18. As a tester, I want validation failures summarized, so that request data or contract issues are easy to triage.
19. As a tester, I want server errors summarized, so that backend regressions are easy to escalate.
20. As a tester, I want blocked requests summarized separately, so that safety policy and environment configuration issues are not misread as API bugs.
21. As a tester, I want suite failures to identify first failing step, so that flow failures can be debugged.
22. As a tester, I want downstream skipped suite steps explained, so that skipped steps are not counted as independent failures.
23. As a tester, I want report findings to reference ExecutionRecord ids, so that every conclusion is auditable.
24. As a tester, I want report findings to reference Observation ids, so that failure analysis can be inspected.
25. As a tester, I want report findings to reference TestCase ids, so that the underlying test asset can be reviewed.
26. As a tester, I want report findings to reference ApiSpec ids, so that API ownership and route context are clear.
27. As a tester, I want report suggestions to include next actions, so that I know what to do after the report.
28. As a tester, I want suggestions deduplicated, so that the report does not repeat the same action many times.
29. As a tester, I want suggestions prioritized, so that the most important actions appear first.
30. As a tester, I want report generation to reuse Phase 7 analysis if it already exists, so that analysis is not duplicated.
31. As a tester, I want report generation to trigger missing basic failure analysis when necessary, so that the report has analysis coverage.
32. As a tester, I want report generation to avoid mutating ExecutionRecord and TestCase, so that factual history and test assets remain stable.
33. As a tester, I want report generation to be idempotent when inputs have not changed, so that repeated generation does not create noisy duplicates.
34. As a tester, I want report regeneration after new executions, so that newer facts can produce a newer report.
35. As a tester, I want older reports preserved, so that historical report snapshots remain available.
36. As a tester, I want the report to identify stale analysis, so that I know when executions changed after a report was created.
37. As a tester, I want report findings to include concise evidence, so that I can understand conclusions without opening every raw record.
38. As a tester, I want report findings to avoid dumping huge snapshots, so that reports stay readable and storage-friendly.
39. As a tester, I want the report to include memory and knowledge references where useful, so that recommendations are traceable.
40. As a tester, I want the report to distinguish factual evidence from inferred analysis, so that conclusions are trustworthy.
41. As a tester, I want the report to include V1 scope information, so that users know this is HTTP API testing only.
42. As a tester, I want the report to include no-result states, so that an empty or not-yet-executed task is explained clearly.
43. As a tester, I want the report to handle missing linked TestCase or ApiSpec data, so that data-quality issues are visible.
44. As a tester, I want the report to handle mixed SINGLE, BATCH and SUITE executions, so that one task can be summarized consistently.
45. As a tester, I want the report to include memory feedback summary, so that I know what the system learned from the run.
46. As a tester, I want the report to include accepted long-term memory candidate counts, so that learning progress is visible.
47. As a tester, I want the report to include rejected/noisy candidate notes when useful, so that users understand why not everything was remembered.
48. As a tester, I want report generation to be deterministic, so that identical inputs produce stable output.
49. As a tester, I want reports to be persisted as structured JSON-backed fields, so that future UI and renderers can display them flexibly.
50. As a tester, I want a report generation result with report id, so that future workflows can reference it.
51. As a maintainer, I want report generation isolated from execution and failure analysis, so that each module keeps a clear responsibility.
52. As a maintainer, I want the report service to consume existing repositories and services, so that no duplicate analysis pipeline is invented.
53. As a maintainer, I want no real LLM dependency in Phase 8, so that reports remain deterministic in CI.
54. As a maintainer, I want no frontend UI in Phase 8, so that backend report data lands before visualization.
55. As a maintainer, I want no PDF, HTML or Markdown renderer in Phase 8, so that rendering remains a later concern.
56. As a maintainer, I want no automatic external notification in Phase 8, so that report generation has no side effects outside the system.
57. As a future frontend, I want findings and suggestions to be structured, so that UI can render cards, tables and filters later.
58. As a future Agent Loop, I want report suggestions to be machine-readable, so that planner decisions can consume them later.
59. As a future CI integration, I want deterministic report status, so that pipeline checks can eventually use it.
60. As a future agent, I want Phase 8 boundary tests, so that report generation stays inside the intended V1 backend scope.

## Implementation Decisions

- Build Phase 8 on top of existing Task, TestCase, TaskCaseExecution, ExecutionRecord, Observation, FailureAnalysis, Memory and Report foundations.
- Add one primary report generation application service as the highest-level entrypoint for callers.
- The primary entrypoint should support generating a report for one task.
- Report generation should read the task execution scope and aggregate relevant case/execution records.
- Report generation should reuse existing Phase 7 analysis results and Observations where available.
- Report generation may trigger BASIC deterministic failure analysis for executions that lack analysis, but it must not introduce DEEP AI analysis.
- Existing Report persistence should be reused where possible.
- If existing Report fields are insufficient, extend Report with structured metadata in a conservative way rather than creating a separate parallel report store.
- Reports should be immutable snapshots once persisted. Regeneration should create a new report unless an explicit replace mode is provided later.
- Report output should include report id, task id, generated at, counts, pass rate, risk summary, findings, suggestions and source references.
- Counts should include total, passed, failed, warning, error, blocked, skipped and unexecuted where data supports it.
- Findings should be structured entries, not prose-only blobs.
- Each finding should include severity, classification, affected api/case references, execution ids, observation ids, evidence summary, retryability and recommended action where available.
- Suggestions should be structured entries with category, priority, action, rationale and supporting source references.
- Findings and suggestions should be deduplicated by stable keys such as classification, API, case, status code, assertion type, error type and suggested action.
- Findings should be sorted by severity, then classification, then deterministic identifiers.
- Risk summary should be concise and deterministic, derived from counts, highest-risk observations and critical failures.
- Report generation should handle empty tasks, tasks with generated cases but no execution, tasks with executions but no analysis, and tasks with missing linked data.
- Report generation should distinguish factual execution evidence from inferred analysis.
- Report generation should preserve source references instead of copying large raw snapshots into Report fields.
- Report generation should not mutate ExecutionRecord, TestCase, TestCaseDraft, Observation or Memory records.
- Report generation should avoid noisy pass-only findings but still count passed executions.
- Report generation should include memory feedback summary when Phase 7 memory effects are observable through existing data.
- Report generation should remain deterministic and local.
- Do not introduce frontend UI, REST controllers, report rendering, PDF, HTML, Markdown export, real LLM calls, Agent Loop, automatic notification, external ticket creation, browser automation, service direct invocation, DB direct assertion engine, distributed workers or queue-backed report jobs in Phase 8.
- Maintain the V1 boundary: HTTP/HTTPS REST API testing only.

## Testing Decisions

- Tests should verify external behavior through application service seams, not private helper methods.
- The highest-value test seam is the report generation application service.
- Seed Task, TestCase, TaskCaseExecution, ExecutionRecord and Observation records, then call the report service and assert persisted Report contents.
- Add tests for task report generation with all-passed executions.
- Add tests for failed, errored, blocked, skipped and passed-with-warnings executions.
- Add tests for pass/fail/warning/error/blocked/skipped counts.
- Add tests for pass rate and no-execution states.
- Add tests for findings grouped by API, case, classification and risk.
- Add tests for finding severity ordering and deterministic output ordering.
- Add tests for retryable and non-retryable failure summaries.
- Add tests for suite first-failing-step findings.
- Add tests for downstream skipped suite step explanation in report findings.
- Add tests for suggestions deduplication and priority ordering.
- Add tests proving report generation reuses existing Observations.
- Add tests proving report generation triggers missing BASIC analysis only when necessary and without real LLM calls.
- Add tests proving repeated report generation creates stable equivalent content and either creates a new immutable report snapshot or follows the chosen idempotency rule.
- Add tests proving ExecutionRecord, TestCase and Observation are not mutated by report generation.
- Add tests for missing TestCase or ApiSpec references becoming data-quality findings.
- Add tests for memory feedback summary where existing memory/refinery artifacts make it observable.
- Add tests for empty task and task with generated but unexecuted cases.
- Add acceptance boundary tests proving Phase 8 does not introduce frontend UI, report rendering, PDF/HTML/Markdown export, real external LLM dependencies, Agent Loop, automatic notification, external ticket creation, browser/UI automation, service direct invocation, DB direct assertions, distributed workers, queues or mandatory live network dependencies in CI.
- Reuse existing Phase 6 execution tests, Phase 7 failure analysis tests, repository tests and boundary guard tests as prior art.

## Out of Scope

- Frontend UI.
- REST API controller layer.
- HTML report rendering.
- PDF report rendering.
- Markdown export.
- Email, Slack, Jira, GitHub issue or external ticket integration.
- Real LLM narrative generation.
- Prompt engineering for report writing.
- Agent Loop orchestration.
- Automatic re-execution.
- Automatic test case mutation.
- Browser automation.
- UI automation.
- Service direct invocation.
- DB direct assertions.
- Distributed report workers.
- Queue-backed asynchronous reports.
- Notification delivery.
- Historical trend analytics beyond one task report snapshot.
- CI/CD integration.
- Human approval workflow.
- Permission model or team collaboration UI.

## Further Notes

- Phase 8 is the first phase that produces a user-facing deliverable artifact, but it should stay at the structured backend data layer.
- The report should be boring, deterministic and easy for a future UI to render. The pretty version comes later.
- The cleanest implementation path is to land task-level counts first, then findings, then suggestions, then Observation/FailureAnalysis reuse, then memory feedback summary, then boundary guard tests.
- The existing untracked PRD publication workflow scratch directory is unrelated and should not be modified by this PRD.
