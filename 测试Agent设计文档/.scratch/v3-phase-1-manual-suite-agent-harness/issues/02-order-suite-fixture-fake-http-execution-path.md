状态：ready-for-agent

# Issue 02：Order suite fixture and fake HTTP execution path

## Parent

ProbeFlow V3-1：Manual Suite Agent Harness 与演示 Fixture PRD

## What to build

把 V3-1 harness 从 smoke run 扩展为可演示的订单链路 fixture。系统需要加载订单、支付、查询、鉴权等 ApiSpec/TestCase 输入，在 fake HTTP gateway 下运行受控执行路径，并在 harness report 中展示 execution summary。

这个 issue 的目标是让 harness 具备一条真实可验证的后端执行路径：fixture 不只是元数据，而是能进入 Task、ApiSpec、TestCase 和 HTTP execution 的现有领域模型。

## Acceptance criteria

- [ ] 新增订单链路 fixture，至少覆盖创建订单、支付订单、查询订单或等价业务步骤。
- [ ] Fixture 包含 baseUrl、tenant、auth token 或等价 env/auth/test data。
- [ ] Fixture 中的接口资产能映射到现有 ApiSpec 模型，不引入另一套接口定义领域模型。
- [ ] Fixture run 能初始化 Task 或等价任务上下文，并记录 task id、task name、source type、promotion mode、target api spec ids 和 metadata。
- [ ] Fixture run 能初始化或引用 TestCase/TestCaseDraft 作为执行输入。
- [ ] Fixture run 默认使用 fake HTTP gateway 或等价受控网关。
- [ ] Fake HTTP response 能覆盖成功订单链路，报告中展示至少一个 passed execution result。
- [ ] Execution summary 能展示执行环境、case/step 数量、passed/failed/skipped/blocked 计数和关键响应摘要。
- [ ] Report 中明确记录 `usesExternalHttp=false`。
- [ ] Report 中不得泄露 auth token、authorization header、cookie、password、secret 或 apiKey。
- [ ] 订单链路 fixture 连续运行两次结果稳定，除 run id、时间戳、耗时和输出路径外，核心 execution summary 可比较。
- [ ] 测试覆盖订单 fixture 加载、Task/ApiSpec/TestCase 输入初始化、fake HTTP execution、execution summary 和 no external HTTP 边界。

## Blocked by

- Issue 01：Harness contract, fixture registry and smoke run foundation
