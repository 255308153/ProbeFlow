Status: ready-for-agent

# Phase 5: Test Case Generation PRD

## Problem Statement

Phase 1 已经完成 ProbeFlow 后端骨架、核心领域模型和持久化基础。Phase 2 已经完成 SourceMaterial 导入与 ApiSpec 分析。Phase 3 已经完成 Knowledge RAG 基础能力。Phase 4 已经完成 Memory System 与 Unified Context Builder。

系统现在已经能回答：

- 目标系统有哪些 HTTP API？
- 每个 API 的结构、参数、认证和约束是什么？
- 与 API 相关的业务文档、测试知识和历史经验是什么？
- 下游模块应该使用怎样的统一上下文？

但系统还不能把这些资产转化为测试人员真正可消费的产物：结构化测试用例。

从用户角度看，ProbeFlow 目前像一个已经完成“资料理解”和“上下文准备”的测试智能体底座，但还缺少核心生产力闭环：根据接口、知识库、记忆和任务目标自动生成测试用例草稿，再经过去重、覆盖度检查、风险分级和人工确认后沉淀为正式 TestCase。

如果没有 Phase 5，后续 HTTP 执行、失败分析和报告都没有稳定输入；如果直接让执行器从 ApiSpec 临时拼请求，又会丢失业务规则、边界场景、负向场景、历史失败模式和用户偏好。

## Solution

实现 Phase 5 的测试用例生成系统，以 `TestCaseGenerationApplicationService` 作为最高层应用服务 seam。

Phase 5 的核心流程是：

```text
Task + ApiSpec targets + generation mode
-> UnifiedContextBuilder
-> Scenario planning
-> TestCaseDraft generation
-> Deduplication and coverage analysis
-> Draft review/update
-> Promotion to TestCase
```

Phase 5 不负责执行 HTTP 请求，也不负责生成最终报告。它只负责把已有上下文变成结构化、可追踪、可审核、可执行前准备的测试用例资产。

生成模式分为三类：

- `SINGLE`：围绕一个 ApiSpec 生成单接口测试用例，覆盖 happy path、参数边界、认证失败、业务规则、错误码和幂等/重复提交等场景。
- `SUITE`：围绕一组相关 ApiSpec 生成流程型测试用例，表达跨接口业务链路，例如创建、查询、修改、取消、删除。
- `BATCH`：面向一个模块或系统批量生成多个 ApiSpec 的用例草稿，要求可分批、可幂等、可恢复，避免重复生成。

Phase 5 的产物分两层：

- `TestCaseDraft`：生成阶段的草稿，允许被重新生成、去重、更新、审核和丢弃。
- `TestCase`：正式测试用例，来自草稿 promotion，具备稳定 id、版本来源、优先级、风险等级、步骤、预期结果、标签和生成依据。

Phase 5 应该优先使用确定性规则生成器落地 V1 能力。可以预留 LLM 生成器接口，但测试环境必须使用 deterministic/fake generator，保证单元和集成测试稳定。真实 LLM 生成、prompt 调优、流式生成和模型路由不属于本阶段。

推荐测试 seam：

- 最高层 seam：`TestCaseGenerationApplicationService`
- 支撑 seam：`TestCaseDraftReviewService` 或等价草稿审核/更新服务
- 支撑 seam：`TestCasePromotionService` 或等价草稿转正式用例服务

测试应优先从最高层应用服务验证外部行为：给定 Task、ApiSpec、KnowledgeContext、Memory Context 和生成模式，断言产生的草稿、覆盖度、去重、promotion 和边界行为。

## User Stories

1. As a tester, I want ProbeFlow to generate test case drafts from an ApiSpec, so that I do not need to manually start from an empty spreadsheet.
2. As a tester, I want generated cases to include happy path scenarios, so that normal API behavior is covered first.
3. As a tester, I want generated cases to include negative scenarios, so that invalid inputs and rejected requests are covered.
4. As a tester, I want generated cases to include boundary values, so that parameter constraints are tested systematically.
5. As a tester, I want generated cases to include authentication and authorization scenarios, so that protected APIs are tested correctly.
6. As a tester, I want generated cases to include business rule scenarios from Knowledge RAG, so that documentation is reflected in the test plan.
7. As a tester, I want generated cases to include historical failure patterns from Memory, so that known project risks are not forgotten.
8. As a tester, I want generated cases to cite the context that influenced them, so that I can understand why a case exists.
9. As a tester, I want each generated case to have title, description, preconditions, steps, expected result, priority, risk level, tags, and structured detail, so that it can later be executed.
10. As a tester, I want generated cases to keep the primary ApiSpec reference, so that the system can detect when a case becomes stale.
11. As a tester, I want generated drafts to remain editable before promotion, so that humans can review and adjust them.
12. As a tester, I want duplicate drafts to be suppressed, so that repeated generation does not create clutter.
13. As a tester, I want repeated generation to update compatible drafts where appropriate, so that improvements can accumulate.
14. As a tester, I want locked or manually edited cases to be protected from automatic overwrite, so that human decisions are respected.
15. As a tester, I want to promote selected drafts into formal TestCases, so that only approved cases enter the stable test asset library.
16. As a tester, I want promotion to preserve draft provenance, so that the formal case remains traceable to its generation source.
17. As a tester, I want promotion to support manual, auto, and guarded modes where the domain model supports them, so that different workflows can be adopted later.
18. As a tester, I want generated cases to be categorized by scenario type, so that I can see normal, boundary, negative, security, compatibility, and regression coverage.
19. As a tester, I want generated cases to carry module and scenario names, so that they are easy to browse.
20. As a tester, I want generated cases to include expected HTTP status codes in draft metadata, so that later execution can prepare assertions.
21. As a tester, I want generated cases to express request inputs structurally, so that an execution engine can later build HTTP requests.
22. As a tester, I want generated cases to express expected result and validation hints structurally, so that assertion generation can happen later.
23. As a tester, I want SINGLE mode for one API, so that I can quickly cover a single endpoint.
24. As a tester, I want SUITE mode for related APIs, so that I can cover a business flow across endpoints.
25. As a tester, I want BATCH mode for a module, so that I can generate initial coverage for many APIs.
26. As a tester, I want BATCH mode to be resumable and idempotent, so that partial failures do not force a restart from zero.
27. As a tester, I want coverage summaries after generation, so that I can see which categories are covered and which remain missing.
28. As a tester, I want generation to flag low-confidence context, so that I know when a case needs stronger human review.
29. As a tester, I want generation to flag context conflicts, so that contradictory docs or memories do not silently produce unreliable cases.
30. As a tester, I want generation to degrade gracefully when Knowledge RAG is empty, so that ApiSpec-only case generation still works.
31. As a tester, I want generation to degrade gracefully when Memory is empty, so that new projects can still begin testing.
32. As a tester, I want generation to reject APIs without enough structural information, so that unusable cases are not created.
33. As a tester, I want generation to mark drafts as blocked or incomplete when required context is missing, so that the next action is clear.
34. As a tester, I want generated cases to respect token budget and context pruning decisions, so that generation remains bounded.
35. As a tester, I want generation to be deterministic in the baseline path, so that repeated runs are understandable and testable.
36. As a tester, I want generated cases to include tags from ApiSpec, Knowledge, and Memory, so that later filtering is useful.
37. As a tester, I want generated cases to distinguish API contract constraints from business constraints, so that failures can be diagnosed later.
38. As a tester, I want generated cases to include risk level, so that high-risk APIs can be prioritized.
39. As a tester, I want generated cases to include priority, so that smoke and regression planning is easier.
40. As a tester, I want generated cases to store based-on ApiSpec versions, so that stale detection can be implemented reliably.
41. As a tester, I want generated cases to avoid UI automation assumptions, so that V1 remains focused on HTTP API testing.
42. As a tester, I want generated cases to avoid DB direct assertions, so that V1 stays inside black-box HTTP API testing boundaries.
43. As a maintainer, I want the generation service to reuse Unified Context Builder, so that every generated case has the same context semantics as later phases.
44. As a maintainer, I want the generation service to reuse existing repositories, so that persistence remains consistent with Phase 1.
45. As a maintainer, I want draft generation and promotion to be separate, so that review workflows are not mixed with generation.
46. As a maintainer, I want case generation to be testable without external LLMs, so that CI remains stable.
47. As a maintainer, I want generated drafts to have deterministic dedup keys, so that idempotency can be verified.
48. As a maintainer, I want formal TestCases to preserve manualEdited and locked semantics, so that future regeneration cannot destroy manual work.
49. As a maintainer, I want acceptance boundary tests for Phase 5, so that HTTP execution, report rendering, frontend UI, browser automation, service direct invocation, and DB direct assertions remain out of scope.
50. As a future agent, I want this PRD to be broken into vertical issues, so that implementation can proceed one small committed slice at a time.

## Implementation Decisions

- Build Phase 5 on top of the existing ApiSpec, Task, TestCaseDraft, TestCase, Knowledge RAG, Memory System, and Unified Context Builder foundations.
- Add one primary generation application service as the highest-level entrypoint for downstream callers.
- Generation requests should include task id, session id, target ApiSpec ids, generation mode, optional module/system filters, optional scenario categories, and a token/context budget.
- The generation service should resolve ApiSpec targets, build context through Unified Context Builder, plan scenarios, generate drafts, deduplicate drafts, and return a structured generation result.
- Scenario planning should be explicit and structured. It should produce scenario intents before creating draft records.
- Scenario intents should include at least happy path, required parameter missing, invalid type/value, boundary value, auth failure, permission failure, documented business rule, known failure pattern, and regression risk where applicable.
- Generation should produce TestCaseDraft first. Formal TestCase creation should happen only through promotion.
- TestCaseDraft should store structured draft content rather than a prompt blob.
- Draft content should include title, description, preconditions, steps, expected result, request shape, expected status, scenario category, risk reasoning, context citations, and generation metadata.
- Deduplication should use deterministic keys derived from task, target ApiSpec, generation mode, scenario category, normalized title or intent, expected status, and important tags.
- Regeneration should not create duplicate drafts for the same dedup key.
- Regeneration may update unpromoted, unlocked, non-manual drafts when the new draft is a better equivalent.
- Regeneration must not overwrite promoted, locked, or manually edited formal cases.
- Promotion should create or update TestCase records from selected drafts according to promotion mode and lock/manual-edit rules.
- Promotion should preserve provenance from the draft, including source, target ApiSpec, generated time, dedup key, context citations, and based-on ApiSpec version data.
- Generated TestCases should fill existing fields for category, mode, title, description, preconditions, expected result, priority, risk level, tags, scenario name, module name, status, source, detail type, detail, steps, stale status, based-on ApiSpec versions, generated source ids, and generated timestamp.
- Priority and risk level should be deterministic in Phase 5. Inputs may include HTTP method, path sensitivity, auth requirements, documented risk, memory failure patterns, and scenario category.
- Coverage analysis should be part of the generation result. It should show which scenario categories were generated, skipped, blocked, or missing.
- Context conflicts from Unified Context Builder should not be ignored. They should either lower confidence, create review warnings, or block risky draft promotion depending on severity.
- Low-confidence context should be reflected in draft metadata and generation result warnings.
- The baseline generator should be deterministic and local.
- A future LLM generator boundary may be introduced, but Phase 5 should not require real LLM calls.
- No new external infrastructure should be introduced for generation.
- The service should use existing transaction patterns and repository style.
- Generation should be idempotent at the application-service level.
- Batch generation should be able to return partial success with per-ApiSpec errors.
- Task and PlanStep status updates may be recorded if they fit existing task orchestration patterns, but generation behavior should not depend on a scheduler.
- Maintain the V1 boundary: HTTP/HTTPS REST API testing only.
- Do not implement HTTP execution, browser automation, UI automation, service direct invocation, DB direct assertions, report rendering, or frontend behavior in Phase 5.

## Testing Decisions

- Tests should verify external behavior through application service seams, not private helper methods.
- The highest-value test seam is the generation application service.
- Add tests where seeded ApiSpec plus seeded context produces deterministic TestCaseDraft records.
- Add tests for SINGLE mode with one ApiSpec and multiple scenario categories.
- Add tests for SUITE mode with related ApiSpecs and flow-oriented drafts.
- Add tests for BATCH mode with multiple ApiSpecs, including partial failure and resumability.
- Add tests for ApiSpec-only generation when Knowledge and Memory are empty.
- Add tests for context-rich generation when KnowledgeContext and MemoryContext are available.
- Add tests for context citations being preserved in draft content.
- Add tests for conflict warnings when Unified Context Builder reports conflicts.
- Add tests for low-confidence context warnings.
- Add tests for deterministic dedup keys and repeated generation idempotency.
- Add tests proving promoted, locked, or manually edited cases are not overwritten.
- Add tests for draft promotion into formal TestCase records.
- Add tests for based-on ApiSpec version data, generated source ids, tags, priority, risk level, steps, detail, and expected result.
- Add tests for coverage summary output.
- Add acceptance boundary tests proving Phase 5 does not perform HTTP execution, assertion evaluation, report rendering, frontend UI, browser automation, service direct invocation, or DB direct assertions.
- Reuse the existing Spring Boot application service tests and repository tests as prior art.
- Do not test real LLM behavior, real HTTP execution, production embedding providers, report rendering, or UI behavior in Phase 5.

## Out of Scope

- Real HTTP request execution.
- Request runner, retry policy, timeout policy, and environment management.
- Assertion execution or assertion result evaluation.
- Failure analysis from real execution results.
- Report rendering.
- Frontend UI.
- Browser automation.
- UI automation.
- Service direct invocation.
- DB direct assertions.
- Real LLM generation provider.
- Prompt streaming, model routing, model evaluation, or provider failover.
- Production vector database changes.
- New distributed queues or background workers.
- Full stale-case regeneration workflow beyond storing enough version provenance for later stale detection.
- Manual review UI.
- Export to Excel, Postman, JMeter, or external test management systems.

## Further Notes

- Phase 5 is the bridge between context intelligence and executable testing. It should stay disciplined: generate structured test assets, but do not execute them yet.
- The safest implementation path is to land deterministic generation first, then add richer scenario categories and promotion behavior in small vertical slices.
- The next phase after Phase 5 should likely be HTTP execution preparation and execution records, using generated TestCase detail and steps as input.
- Existing untracked PRD publication workflow scratch data is unrelated and should not be modified by this PRD.
