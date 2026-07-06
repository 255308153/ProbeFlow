Status: ready-for-agent

# Phase 9: Task Orchestration System PRD

## Problem Statement

Phase 1 已经完成 ProbeFlow 后端骨架、核心领域模型和持久化地基。Phase 2 已经完成 SourceMaterial 导入和 ApiSpec 分析。Phase 3 已经完成 Knowledge RAG。Phase 4 已经完成 Memory System 与 Unified Context Builder。Phase 5 已经完成测试用例生成。Phase 6 已经完成 HTTP Execution Engine。Phase 7 已经完成 Failure Analysis and Feedback Loop。Phase 8 已经完成结构化 Task Report Generation。

系统现在已经具备一组可独立调用的底层能力：

- 从 SourceMaterial 分析接口资产，生成 ApiSpec。
- 从知识库和记忆系统构建上下文。
- 基于 ApiSpec、Knowledge 和 Memory 生成 TestCaseDraft/TestCase。
- 执行 SINGLE、BATCH、SUITE HTTP API 测试。
- 对失败执行进行确定性失败分析，产出 Observation 和 Memory 候选。
- 把 Task、TestCase、ExecutionRecord、Observation 和 Memory 汇总成结构化 Report。

但这些能力目前仍然需要调用方按正确顺序手动串联。用户想完成一次完整 API 测试任务时，仍然要知道：

- 应该先创建什么 Task？
- 哪些 SourceMaterial 需要被分析？
- 哪些 ApiSpec 可以进入用例生成？
- 什么时候生成 TestCaseDraft？
- 自动模式下是否应该直接 promotion？
- 半自动模式下什么时候暂停等待 review？
- 回归任务应该如何跳过接口分析和用例生成？
- 执行前 readiness 不满足时应该如何结束？
- 执行失败后什么时候触发失败分析？
- 最终报告应该在什么时机生成？
- 哪些 PlanStep 已完成，哪些失败，哪些被跳过？

没有任务编排系统，ProbeFlow 仍然是“多个可用模块”，不是“一个可运行的测试 Agent 后端闭环”。Phase 9 需要把 Phase 1-8 的能力串成 V1 的确定性任务编排流程，让一个 Task 可以从初始化、计划、执行、分析到报告生成被稳定推进。

Phase 9 不是完整 AI Agent Loop，也不是分布式 DAG 引擎。V1 边界已经确定：正常路径走模板驱动编排，Planner 只作为未来按需能力预留。Phase 9 的目标是落地可测试、可恢复、可审计的后端编排地基。

## Solution

实现 Phase 9 的 Task Orchestration System，以 `TaskOrchestrationApplicationService` 作为最高层应用服务 seam。

Phase 9 的核心流程是：

```text
Task creation request or existing task id
-> TaskInitializationService creates or loads Task
-> TaskTemplateRegistry selects deterministic template
-> PlanStep records are created or resumed
-> TaskOrchestrationApplicationService runs eligible steps in order
-> PlanStepRunner routes each step to existing application services
-> StepOutcome summarizes runtime result
-> Task/PlanStep status transitions are persisted
-> orchestration pauses, completes, fails or generates Report
```

Phase 9 应采用“模板驱动 + 按需 Planner 预留”的 V1 方案：

- API_TEST 探索任务走固定模板。
- REGRESSION 回归任务走固定模板。
- 半自动任务在用例生成后暂停进入 WAITING_FOR_REVIEW。
- 全自动任务在用例生成后自动 promotion 并继续执行。
- 执行前 readiness gate 不满足时，任务以明确 blocker 状态结束或跳过执行并生成部分报告。
- 执行失败后触发基础失败分析和报告生成。
- 不引入真实 LLM Planner，不让 AI 决定工具调用。

推荐最高层测试 seam：

- `TaskOrchestrationApplicationService`

推荐支撑 seam：

- `TaskInitializationService`：创建 Task、补齐 targetApiSpecIds、初始化 TaskCaseExecution 或模板需要的 metadata。
- `TaskTemplateRegistry`：根据 TaskType、TaskSourceType、PromotionMode 选择 PlanStep 模板。
- `PlanStepRunner`：根据 PlanStepType 调用已有应用服务。
- `StepOutcome` 或等价运行时结果对象：表达每个步骤完成后的摘要、状态、引用和 blocker。
- `OrchestrationReadinessGate` 或等价组件：统一判断分析 readiness、执行 readiness、review readiness。

Phase 9 应复用现有模块，不复制底层能力：

- ApiAnalysisApplicationService
- KnowledgeRetrievalApplicationService
- UnifiedContextBuilder
- TestCaseGenerationApplicationService
- TestCasePromotionService
- HttpExecutionApplicationService
- FailureAnalysisApplicationService
- ReportGenerationApplicationService
- TaskRepository
- PlanStepRepository
- TaskCaseExecutionRepository
- TestCaseDraftRepository
- TestCaseRepository

Phase 9 的结果应该让调用方可以：

- 创建并初始化 API_TEST Task。
- 创建并初始化 REGRESSION Task。
- 运行一个 Task 直到完成、失败、取消或等待人工 review。
- 断点续跑已有 Task，不重复执行已完成 PlanStep。
- 查询 Task 和 PlanStep 状态了解进度。
- 得到最终 Report id 或 blocker 信息。

## User Stories

1. As a tester, I want to start an API test task from source material, so that ProbeFlow can run the full backend workflow.
2. As a tester, I want to start a regression task from existing TestCases, so that I can rerun known API tests without re-analyzing code.
3. As a tester, I want ProbeFlow to initialize Task metadata consistently, so that later steps know what APIs and cases belong to the task.
4. As a tester, I want ProbeFlow to create PlanSteps for a task, so that task progress is visible and auditable.
5. As a tester, I want API_TEST tasks to follow the expected V1 workflow, so that analysis, generation, execution, failure analysis and report generation happen in the right order.
6. As a tester, I want REGRESSION tasks to skip source analysis and case generation, so that existing cases can be executed quickly.
7. As a tester, I want automatic mode to promote generated drafts into TestCases, so that CI and repeatable regression flows can run without manual review.
8. As a tester, I want semi-automatic mode to pause after case generation, so that I can review generated drafts before execution.
9. As a tester, I want a paused task to resume after review, so that approved cases continue into execution.
10. As a tester, I want discarded drafts to stay discarded when a task resumes, so that manual review decisions are respected.
11. As a tester, I want task status to reflect the current phase, so that I know whether the task is analyzing, generating, executing, analyzing results or complete.
12. As a tester, I want PlanStep status to reflect pending, running, succeeded, failed or skipped states, so that I can inspect progress.
13. As a tester, I want the orchestrator to resume from incomplete steps, so that a crashed or interrupted task does not restart from the beginning.
14. As a tester, I want completed PlanSteps not to rerun on resume, so that executions and reports are not duplicated accidentally.
15. As a tester, I want execution readiness to be checked before HTTP execution, so that missing base URL, environment or auth does not create noisy failures.
16. As a tester, I want blocked execution readiness to be reported clearly, so that I know what must be fixed before execution can run.
17. As a tester, I want partial reports for tasks that cannot execute, so that analysis and generation work is still summarized.
18. As a tester, I want failed executions to trigger failure analysis, so that report findings are backed by observations.
19. As a tester, I want successful executions to skip unnecessary failure analysis, so that reports stay efficient and deterministic.
20. As a tester, I want report generation to happen at the end of the workflow, so that I always get a final structured artifact.
21. As a tester, I want the orchestration result to include task id, status, completed step count and report id when available, so that callers can continue workflow automation.
22. As a tester, I want blocker details in the orchestration result, so that a human can fix missing inputs.
23. As a tester, I want cancelled tasks not to continue executing, so that explicit cancellation is respected.
24. As a tester, I want failed steps to stop dependent steps, so that downstream work does not run on invalid state.
25. As a tester, I want non-critical skipped steps to be recorded, so that reports and debugging can explain why something did not run.
26. As a maintainer, I want templates to declare PlanStepType order, so that workflow logic is explicit and testable.
27. As a maintainer, I want PlanStep routing to be deterministic, so that CI does not depend on AI decisions.
28. As a maintainer, I want PlanStepRunner to call existing application services, so that orchestration does not duplicate domain logic.
29. As a maintainer, I want StepOutcome to stay separate from Observation, so that runtime orchestration state does not pollute persistent failure analysis.
30. As a maintainer, I want TaskInitializationService separate from orchestration runtime, so that task creation and task execution stay independently testable.
31. As a maintainer, I want TaskTemplateRegistry to be extendable, so that later task types can add templates without rewriting the engine.
32. As a maintainer, I want no real LLM Planner in Phase 9, so that orchestration remains deterministic.
33. As a maintainer, I want no distributed queue or worker in Phase 9, so that local CI stays simple.
34. As a maintainer, I want no frontend or REST controller in Phase 9, so that backend orchestration lands before API exposure.
35. As a maintainer, I want no external GitHub/Jira/Slack notification integration in Phase 9, so that orchestration has no external side effects.
36. As a maintainer, I want no browser automation or UI automation in Phase 9, so that V1 remains HTTP API testing only.
37. As a maintainer, I want task orchestration tests to verify external behavior through the application service, so that implementation details can evolve.
38. As a maintainer, I want boundary tests for Phase 9, so that future work does not silently turn the deterministic orchestrator into a real Agent Loop.
39. As a future planner developer, I want explicit extension points for planner decisions, so that Phase 10+ can add AI planning without changing the existing step execution contract.
40. As a future API developer, I want orchestration results to be structured, so that REST or CLI layers can expose them later.
41. As a future UI developer, I want PlanStep records to show progress, so that a task timeline can be rendered later.
42. As a future CI integration, I want orchestration to return deterministic statuses, so that pipeline checks can eventually consume them.
43. As a future team workflow user, I want tasks to preserve historical PlanStep outcomes, so that teammates can audit what happened.
44. As a future operator, I want reruns to be deliberate, so that completed executions are not repeated by accident.
45. As a future report consumer, I want report generation to be tied to the orchestration completion point, so that reports represent the task lifecycle.
46. As a future memory-system developer, I want orchestration to trigger only the existing memory feedback paths, so that MemoryRefinery remains the memory write authority.
47. As a future source-ingestion developer, I want source ingestion to remain outside this phase unless already represented by SourceMaterial, so that orchestration does not become a file import subsystem.
48. As a future regression user, I want stale TestCases to be surfaced before execution, so that outdated assets do not silently run as if fresh.
49. As a future semi-automatic user, I want review gates to be explicit, so that humans understand when ProbeFlow is waiting.
50. As a future automation user, I want the same Task to move through stable statuses, so that scripts do not need module-specific state logic.

## Implementation Decisions

- Build Phase 9 around a highest-level `TaskOrchestrationApplicationService`.
- Add a task initialization seam for creating task records and preparing initial task execution scope.
- Add a deterministic task template registry that maps task type and mode to ordered PlanStep templates.
- Use existing Task and PlanStep persistence instead of creating a separate workflow store.
- Keep StepOutcome as a runtime orchestration result concept distinct from persistent Observation.
- Route PlanStepType values to existing application services through a deterministic runner or adapter layer.
- Keep the routing table local and explicit. Phase 9 should not let an LLM choose tools.
- Treat API_TEST and REGRESSION as first-class V1 templates.
- API_TEST should support source analysis, knowledge/context preparation, test case generation, execution, failure analysis and report generation.
- REGRESSION should skip API analysis and test case generation, then prepare execution input, execute cases, analyze failures and generate report.
- Keep SourceMaterial ingestion itself out of Phase 9 unless existing records are already present. The orchestrator consumes existing SourceMaterial references and task metadata.
- Preserve V1 status transitions: PENDING, ANALYZING, CASE_GENERATED, WAITING_FOR_REVIEW, EXECUTING, ANALYZING_RESULTS, COMPLETED, FAILED and CANCELLED.
- Automatic mode should promote generated drafts through the existing promotion service where possible.
- Semi-automatic mode should pause after case generation and avoid execution until review has completed.
- Resuming a task should skip completed PlanSteps and continue from pending or failed resumable work.
- Completed execution and report steps should not be duplicated on ordinary resume.
- Execution readiness should check environment/base URL/auth requirements using existing task metadata and execution input conventions.
- Readiness blockers should be captured in structured orchestration result data and PlanStep status where appropriate.
- When execution is blocked, the orchestrator may generate a partial report if existing analysis or generation artifacts are available.
- Failure analysis should be called after failed, errored, blocked or warning executions where Phase 7 analysis is missing or needed.
- Report generation should use Phase 8 ReportGenerationApplicationService rather than assembling report data in the orchestrator.
- Memory write/refinery behavior should remain inside existing Phase 7/Phase 4 services. Phase 9 should not add a new memory write path.
- Planner should be represented only as a future extension point or interface if needed. Do not implement real LLM planning in Phase 9.
- Do not add REST controllers, frontend UI, distributed queues, background workers, external notifications or ticket integrations in Phase 9.
- Maintain V1 boundary: HTTP/HTTPS REST API testing only.

## Testing Decisions

- Tests should verify behavior through `TaskOrchestrationApplicationService` and `TaskInitializationService`, not private helper methods.
- The best tests create realistic Task, SourceMaterial, ApiSpec, TestCaseDraft, TestCase and TaskCaseExecution records, then invoke orchestration and assert Task/PlanStep/Report outcomes.
- Add tests for API_TEST automatic mode from initialized task to completed report where dependencies can be faked or seeded.
- Add tests for API_TEST semi-automatic mode pausing at WAITING_FOR_REVIEW after case generation.
- Add tests for semi-automatic resume after drafts are promoted.
- Add tests for REGRESSION template skipping API analysis and case generation.
- Add tests for execution readiness blockers preventing noisy execution.
- Add tests for partial report generation when execution is blocked after useful upstream work.
- Add tests for execution failures triggering failure analysis before report generation.
- Add tests for all-passed execution skipping unnecessary failure analysis.
- Add tests for resume behavior skipping completed PlanSteps.
- Add tests for cancelled tasks not executing further steps.
- Add tests for failed critical steps marking dependent steps skipped or preventing downstream execution.
- Add tests proving StepOutcome is not persisted as Observation unless an analysis service intentionally creates Observation.
- Add tests proving orchestration calls existing services through seams rather than duplicating analysis, generation, execution, failure analysis or report logic.
- Reuse prior-art tests from ApiAnalysisApplicationServiceTests, TestCaseGenerationApplicationServiceTests, HttpExecutionApplicationServiceTests, FailureAnalysisApplicationServiceTests and ReportGenerationApplicationServiceTests.
- Add Phase 9 acceptance boundary tests proving no frontend, REST controller, real LLM Planner, Agent Loop free-form tool selection, distributed worker, queue, external notification, external ticketing, browser/UI automation, service direct invocation or DB direct assertion engine is introduced.
- Full backend test command remains `mvn test` in the backend module.

## Out of Scope

- Real LLM Planner.
- Free-form Agent Loop tool selection.
- Distributed DAG engine.
- Queue-backed asynchronous orchestration.
- Background workers.
- REST controller layer.
- Frontend UI.
- CLI.
- GitHub, Jira, Slack, email or external notification integration.
- CI/CD integration.
- Browser automation.
- UI automation.
- Service direct invocation.
- DB direct assertion engine.
- Source clone, unzip or file upload pipeline beyond existing SourceMaterial references.
- Human review UI.
- Permission model.
- Team collaboration workflow.
- Automatic TestCase mutation beyond existing draft promotion.
- Automatic SUITE refresh.
- Long-running job cancellation infrastructure beyond respecting CANCELLED Task status.
- Historical trend analytics across reports.
- Real external network dependency in CI.

## Further Notes

- Phase 9 is the V1 backend closure phase: after it lands, ProbeFlow has a deterministic end-to-end backend workflow from task initialization to final report.
- The orchestrator should be boring and explicit. Its job is to connect modules, not to become a second implementation of every module.
- The old tool orchestration document describes a richer Agent Loop. Phase 9 intentionally implements the V1 constrained version: template-driven orchestration with extension points for future planner work.
- The existing untracked PRD publication workflow scratch directory is unrelated and should not be modified by this PRD.
