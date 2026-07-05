Status: ready-for-agent

# Phase 7: Failure Analysis and Feedback Loop PRD

## Problem Statement

Phase 1 已经完成 ProbeFlow 后端骨架、核心领域模型和执行记录持久化模型。Phase 2 已经完成 SourceMaterial 导入和 ApiSpec 分析。Phase 3 已经完成 Knowledge RAG。Phase 4 已经完成 Memory System 与 Unified Context Builder。Phase 5 已经完成测试用例生成系统。Phase 6 已经完成 HTTP Execution Engine，能够执行 SINGLE、BATCH 和 SUITE 测试用例，保存 ExecutionRecord，更新 TaskCaseExecution，并写入基础 Task Memory。

系统现在已经能回答：

- 目标系统有哪些 HTTP API？
- 每个 API 应该生成哪些测试用例？
- 每条测试用例是否可以被执行？
- 执行时发出了什么请求？
- 收到了什么响应？
- baseline assertions 是否通过？
- 执行结果是否已经被记录？

但系统还不能充分解释这些执行结果。

从用户角度看，ProbeFlow 已经能跑测试，但测试失败后仍然停留在“状态码失败、断言失败、网络错误、请求被阻止”这一层。用户仍然需要手工阅读 ExecutionRecord、requestSnapshot、responseSnapshot、assertionResults、Task Memory 和 RAG 文档，才能判断：

- 这是接口真实 bug，还是测试数据/环境/鉴权问题？
- 这是断言写错、用例过期，还是 API 行为变化？
- 这是偶发网络问题，还是稳定可复现失败？
- 是否应该重试？
- 是否应该生成新测试、修正测试、更新知识库，还是沉淀长期记忆？
- 对后续报告、Agent Loop 和团队协作来说，这次失败有什么可复用结论？

如果没有失败分析和反馈闭环，ProbeFlow 仍然只是“自动执行器”。Phase 7 需要让系统从执行结果中提炼 Observation、Failure Classification、Retry Suggestion、Risk Evaluation 和 Memory Candidate，使后续编排、报告和记忆提纯有高质量输入。

## Solution

实现 Phase 7 的 Failure Analysis and Feedback Loop，以 `FailureAnalysisApplicationService` 作为最高层应用服务 seam。

Phase 7 的核心流程是：

```text
ExecutionRecord id or Task execution scope
-> load ExecutionRecord, Task, TestCase, ApiSpec and existing Task Memory
-> classify execution outcome
-> analyze failed assertions, transport errors, blocked requests and suite step failures
-> compare request/response facts with ApiSpec, TestCase expectation and Knowledge/Memory context where available
-> create Observation records
-> produce retry suggestions and next action recommendations
-> write compact Task Memory
-> optionally send high-confidence candidates to MemoryRefineryService
-> return FailureAnalysisResult for later orchestration/reporting
```

Phase 7 的目标不是接入真实 LLM，也不是生成最终报告，而是先做一个确定性、可测试、可追踪的失败分析层。它应当把 Phase 6 的低层执行事实升级为结构化分析结果。

推荐测试 seam：

- 最高层 seam：`FailureAnalysisApplicationService`
- 支撑 seam：`ExecutionFailureClassifier` 或等价分类组件
- 支撑 seam：`FailureObservationWriter` 或等价 Observation 写入组件
- 支撑 seam：`ExecutionFeedbackMemoryWriter` 或等价记忆反馈组件

测试应优先通过最高层应用服务验证外部行为：给定已持久化的 ExecutionRecord、TestCase、ApiSpec、Task 和已有 Memory，断言分析结果、Observation、Task Memory、Memory Candidate、risk level、retry suggestion 和 next action 的行为。

Phase 7 应复用现有 ExecutionRecord、Observation、TaskMemoryService、MemoryRefineryService、UnifiedContextBuilder、KnowledgeRetrievalApplicationService 和 LongTermMemoryRetrievalService。不要把失败分析逻辑塞回 HTTP execution engine；HTTP execution engine 继续负责执行和事实记录，Failure Analysis 负责解释和反馈。

## User Stories

1. As a tester, I want ProbeFlow to analyze a failed ExecutionRecord, so that I can understand why a test failed.
2. As a tester, I want ProbeFlow to classify assertion failures, so that I can distinguish API bugs from expectation mismatches.
3. As a tester, I want ProbeFlow to classify transport errors, so that I can tell network failures from application failures.
4. As a tester, I want ProbeFlow to classify blocked requests, so that I can see whether safety policy, invalid URL, or missing variable caused the block.
5. As a tester, I want ProbeFlow to analyze status code mismatches, so that I can understand whether the API returned an unexpected class of response.
6. As a tester, I want ProbeFlow to analyze JSON field existence failures, so that missing response fields are explained clearly.
7. As a tester, I want ProbeFlow to analyze JSON field equality failures, so that changed business values can be identified.
8. As a tester, I want ProbeFlow to analyze response body presence failures, so that empty or unexpected responses are visible.
9. As a tester, I want ProbeFlow to analyze duration threshold failures, so that slow API behavior is recorded as a performance risk.
10. As a tester, I want ProbeFlow to analyze suite step failures, so that I know which step caused the flow to fail.
11. As a tester, I want ProbeFlow to identify skipped suite steps caused by prerequisite failures, so that follow-up failures are not misread as independent problems.
12. As a tester, I want ProbeFlow to summarize request facts, so that I can see method, path, environment, status, duration, and case id in one place.
13. As a tester, I want ProbeFlow to summarize response facts, so that I can quickly inspect status code, failure type, error type, and failed assertion summaries.
14. As a tester, I want ProbeFlow to create Observation records, so that analysis results become queryable project artifacts.
15. As a tester, I want Observation risk levels, so that critical failures can be prioritized over harmless warnings.
16. As a tester, I want Observation analysis levels, so that I can distinguish basic deterministic analysis from future deep AI analysis.
17. As a tester, I want retry suggestions, so that temporary environment and network failures can be handled differently from deterministic bugs.
18. As a tester, I want next action recommendations, so that I know whether to retry, inspect environment, update test case, update API expectation, or escalate a bug.
19. As a tester, I want ProbeFlow to mark likely flaky failures, so that repeated unstable behavior can be separated from stable regressions.
20. As a tester, I want ProbeFlow to mark likely test asset drift, so that old TestCases can be revised when API behavior changed intentionally.
21. As a tester, I want ProbeFlow to mark likely environment failures, so that invalid base URLs, blocked hosts, timeouts, and auth problems do not get mistaken for product bugs.
22. As a tester, I want ProbeFlow to mark likely authentication failures, so that 401/403 and auth-related assertion failures are easy to triage.
23. As a tester, I want ProbeFlow to mark likely validation failures, so that 400/422 responses can be connected to request data or contract mismatch.
24. As a tester, I want ProbeFlow to mark likely server regressions, so that 5xx and critical failed assertions can be escalated.
25. As a tester, I want ProbeFlow to compare failures with ApiSpec facts, so that method/path/auth/parameter mismatch can be detected.
26. As a tester, I want ProbeFlow to compare failures with TestCase expectations, so that failed expectation names and expected/actual values are visible.
27. As a tester, I want ProbeFlow to use Unified Context when available, so that knowledge and memory evidence can inform classification.
28. As a tester, I want ProbeFlow to include citations from knowledge and memory where available, so that analysis remains traceable.
29. As a tester, I want ProbeFlow to avoid overclaiming root cause when evidence is weak, so that analysis stays trustworthy.
30. As a tester, I want ProbeFlow to preserve uncertainty, so that a likely cause is not presented as a confirmed cause.
31. As a tester, I want ProbeFlow to write compact Task Memory for important execution findings, so that the current task can use the result later.
32. As a tester, I want ProbeFlow to avoid writing noisy Task Memory for every passed execution, so that the task context remains useful.
33. As a tester, I want ProbeFlow to create high-confidence Memory Candidates for repeated or important failures, so that useful experience can become Long-term Memory.
34. As a tester, I want ProbeFlow to reject low-value memory candidates, so that long-term memory does not become a dumping ground.
35. As a tester, I want ProbeFlow to update the Task memory refinement status when analysis produces candidates, so that later workflows know refinement happened.
36. As a tester, I want ProbeFlow to analyze all failed records in a batch, so that a regression run can produce an aggregate failure picture.
37. As a tester, I want ProbeFlow to deduplicate similar failures in the same batch, so that the report does not repeat the same root signal many times.
38. As a tester, I want ProbeFlow to rank failures by severity, so that critical API failures appear before minor warnings.
39. As a tester, I want ProbeFlow to group failures by API, case, status code, and failure type, so that repeated patterns are obvious.
40. As a tester, I want ProbeFlow to detect mixed outcomes in a suite, so that the first failed prerequisite is separated from downstream skipped steps.
41. As a tester, I want ProbeFlow to expose analysis result ids, so that future reports and orchestration steps can reference them.
42. As a tester, I want ProbeFlow to be idempotent for the same execution record, so that repeated analysis does not create duplicate Observations.
43. As a tester, I want ProbeFlow to support re-analysis after new knowledge or memory is added, so that better context can improve conclusions.
44. As a tester, I want ProbeFlow to leave ExecutionRecord immutable, so that factual execution history is not rewritten by analysis.
45. As a tester, I want ProbeFlow to distinguish factual evidence from inferred explanation, so that users can audit conclusions.
46. As a tester, I want ProbeFlow to handle passed-with-warnings executions, so that non-critical assertion failures still become risk observations.
47. As a tester, I want ProbeFlow to handle skipped executions, so that dry-run or blocked cases do not become false failures.
48. As a tester, I want ProbeFlow to handle missing linked TestCase or ApiSpec gracefully, so that analysis still produces a useful data-quality observation.
49. As a tester, I want ProbeFlow to expose a task-level failure summary, so that later report generation can consume it directly.
50. As a tester, I want ProbeFlow to expose retryable and non-retryable counts, so that future orchestration can decide whether to retry automatically.
51. As a maintainer, I want failure analysis isolated from HTTP execution, so that execution remains a factual recording layer.
52. As a maintainer, I want deterministic classification rules first, so that CI tests are stable and behavior is explainable.
53. As a maintainer, I want analysis output to reuse Observation, Task Memory, and Long-term Memory models, so that Phase 1 and Phase 4 models remain useful.
54. As a maintainer, I want a small set of explicit failure categories, so that later reports and orchestration can depend on them.
55. As a maintainer, I want no real LLM dependency in Phase 7, so that the backend remains deterministic and testable.
56. As a maintainer, I want no frontend UI in Phase 7, so that analysis behavior lands before visualization.
57. As a maintainer, I want no automatic ticket creation in Phase 7, so that external workflow integration remains a later phase.
58. As a future agent, I want Phase 7 to emit next action recommendations, so that Agent Loop can later decide what to do after execution.
59. As a future agent, I want Phase 7 to produce memory candidates, so that future generation can cover known failure patterns.
60. As a future agent, I want Phase 7 boundary tests, so that failure analysis stays inside the intended V1 backend scope.

## Implementation Decisions

- Build Phase 7 on top of existing ExecutionRecord, Task, TestCase, ApiSpec, Observation, Task Memory, Long-term Memory and Unified Context foundations.
- Add one primary failure analysis application service as the highest-level entrypoint for callers.
- The primary entrypoint should support analyzing one execution record by id.
- The primary entrypoint should support analyzing a task execution scope by task id and optional execution ids.
- Analysis requests should allow a mode such as BASIC now and reserve DEEP for future AI-backed analysis.
- BASIC analysis must be deterministic and local.
- ExecutionRecord remains immutable. Phase 7 writes derived artifacts instead of modifying factual execution records.
- Derived artifacts should include Observation records for important analysis outcomes.
- Analysis results should include execution id, task id, case id, overall status, classification, risk level, retryability, next suggestion, evidence summary, observation ids, task memory ids, and memory refinement result where applicable.
- Define a small classification vocabulary for V1: assertion failure, status mismatch, response shape mismatch, duration regression, transport error, timeout, blocked request, environment issue, auth issue, validation issue, server error, suite prerequisite failure, skipped, passed with warning, data quality issue, unknown.
- Classification should use evidence from overall status, status code, error message, response snapshot, assertion results, request snapshot and suite step snapshots.
- Risk level should be derived from severity, critical flag, status code family, blocked/error type, suite impact and number of affected cases.
- Retry suggestion should be conservative. Retry is suggested for likely transient transport errors, timeout, 429, 502, 503, 504 and explicitly retryable response metadata. Retry should not be suggested for deterministic assertion mismatch unless evidence suggests transient behavior.
- Next action should be explicit: retry execution, inspect environment, inspect auth variables, update TestCase expectation, investigate API regression, review API contract, inspect suite prerequisite, or no action.
- Use ApiSpec and TestCase data to enrich analysis but avoid failing if either record is missing.
- Use UnifiedContextBuilder where it can add Knowledge/Memory evidence without expanding scope too far. If context is unavailable, produce deterministic analysis from execution facts.
- Observation records should use existing Observation fields and source SYSTEM for BASIC deterministic analysis.
- Analysis should be idempotent. Re-running analysis for the same execution and same analysis mode should not create duplicate Observations or duplicate Task Memory entries.
- Task Memory writes should be compact and focused on task-relevant failures, warnings, retry suggestions and confirmed stable pass patterns only when useful.
- Long-term Memory candidates should be created only for high-confidence useful findings such as repeated failure patterns, auth quirks, environment gotchas, validation behavior or known regression risks.
- MemoryRefineryService should remain the only Long-term Memory write boundary.
- Phase 7 may update Task memory refinement status if existing task fields support it cleanly.
- Batch/task analysis should deduplicate similar failures by API, classification, status code, failed assertion type and error type.
- Batch/task analysis should return aggregate counts by classification, risk level, retryability and affected case count.
- Suite analysis should identify the first failing step and downstream skipped steps.
- Passed executions should usually produce no Observation unless they are passed with warnings or are needed as evidence for batch-level contrast.
- Skipped dry-run executions should not be treated as failures.
- Blocked executions should become safety/environment observations, not API bug observations.
- Keep all generated summaries concise and structured. Store raw evidence references rather than duplicating huge snapshots.
- Do not introduce real LLM calls, prompt orchestration, report rendering, frontend UI, browser automation, service direct invocation, DB direct assertion engine, external ticket creation, or distributed workers in Phase 7.
- Maintain the V1 boundary: HTTP/HTTPS REST API testing only.

## Testing Decisions

- Tests should verify external behavior through application service seams, not private helper methods.
- The highest-value test seam is the failure analysis application service.
- Seed ExecutionRecord, Task, TestCase and ApiSpec records, then call the analysis service and assert returned analysis plus persisted Observation and Memory effects.
- Add tests for status code assertion mismatch classification.
- Add tests for JSON field missing classification.
- Add tests for JSON field equality mismatch classification.
- Add tests for body presence failure classification.
- Add tests for duration threshold warning or failure classification.
- Add tests for timeout and transport error classification.
- Add tests for blocked host, unsupported protocol and unresolved variable classification when represented in response snapshots.
- Add tests for 401 and 403 authentication/authorization classification.
- Add tests for 400 and 422 validation classification.
- Add tests for 5xx server regression classification.
- Add tests for passed-with-warnings behavior.
- Add tests proving passed executions do not create noisy Observations by default.
- Add tests proving skipped dry-run executions do not become failures.
- Add tests for suite first-failing-step detection and downstream skipped step explanation.
- Add tests for batch/task analysis aggregation, deduplication and risk ordering.
- Add tests for retry suggestion rules, including retryable transport failures and non-retryable deterministic assertion mismatches.
- Add tests for next action recommendations.
- Add tests for Observation persistence with correct observation type, analysis level, source, risk level, summary, failure reason and next suggestion.
- Add tests for Task Memory write behavior and deduplication.
- Add tests for Long-term Memory candidate/refinery behavior only for high-confidence useful findings.
- Add tests proving repeated analysis of the same execution is idempotent.
- Add tests proving ExecutionRecord is not mutated by analysis.
- Add tests proving missing TestCase or ApiSpec does not crash analysis and creates a data-quality observation.
- Add acceptance boundary tests proving Phase 7 does not introduce frontend UI, report rendering, browser/UI automation, service direct invocation, DB direct assertions, real external LLM dependencies, automatic ticket creation, distributed workers, or mandatory live network dependencies in CI.
- Reuse existing Phase 4 memory tests, Phase 6 HTTP execution tests, repository tests and phase boundary guard tests as prior art.

## Out of Scope

- Final report rendering.
- Frontend UI.
- REST API controller layer.
- Real LLM failure analysis.
- Prompt engineering for failure analysis.
- Agent Loop orchestration.
- Automatic re-execution.
- Automatic test case mutation.
- Automatic PR or bug ticket creation.
- Browser automation.
- UI automation.
- Service direct invocation.
- DB direct assertions.
- Full root cause proof.
- Distributed execution workers.
- Queue-backed async analysis.
- Notification integrations.
- Slack, Jira, GitHub issue or email integration.
- Full observability dashboard.
- Production credential vault integration.
- Load testing or performance trend analytics beyond local duration failure interpretation.
- Contract diffing.
- Schema validation engine.
- Human approval workflow.

## Further Notes

- Phase 7 is the bridge between HTTP execution and future orchestration/reporting. It should turn raw execution facts into actionable observations without pretending to know more than the evidence supports.
- Keep the first implementation boring and deterministic. The future AI-backed analysis layer can consume the same ExecutionRecord, Observation, ContextBundle and Memory artifacts later.
- The cleanest implementation path is to land single-execution analysis first, then Observation persistence, then Task Memory/refinery feedback, then task-level aggregation and suite/batch grouping.
- The existing untracked PRD publication workflow scratch directory is unrelated and should not be modified by this PRD.
