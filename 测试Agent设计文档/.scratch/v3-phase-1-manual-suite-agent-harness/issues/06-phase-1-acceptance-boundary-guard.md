状态：ready-for-agent

# Issue 06：V3-1 acceptance regression and boundary guard

## Parent

ProbeFlow V3-1：Manual Suite Agent Harness 与演示 Fixture PRD

## What to build

补齐 V3-1 的验收回归和边界 guard。系统需要证明本阶段交付的是后端 Manual Suite Agent Harness 与演示 fixture，而不是提前实现完整 V3 智能链路、完整 Web Console、产品化 REST Controller、真实外部 LLM 依赖、真实外部 API 依赖或真实 embedding 依赖。

这个 issue 的目标是保护 V3-1 的范围：harness 可以演示和承载后续能力，但不能绕过 V1/V2 的 Task、HttpExecution、Report、Memory、AgentEvaluation、Policy 和安全边界。

## Acceptance criteria

- [ ] 边界测试证明 V3-1 没有新增完整 Web Console。
- [ ] 边界测试证明 V3-1 没有新增产品化 REST Controller。
- [ ] 边界测试证明 V3-1 没有新增 UI 自动化、浏览器自动化、Service 直调、DB 直连断言、消息队列测试或非 HTTP 协议测试作为交付能力。
- [ ] 边界测试证明真实 LLM 不是 CI 或默认 harness run 的必需依赖。
- [ ] 边界测试证明真实 embedding 不是 CI 或默认 harness run 的必需依赖。
- [ ] 边界测试证明真实外部 HTTP 服务不是 CI 或默认 harness run 的必需依赖。
- [ ] 边界测试证明 V3-1 没有实现完整 Business Flow Discovery。
- [ ] 边界测试证明 V3-1 没有实现完整 DependencyLinker。
- [ ] 边界测试证明 V3-1 没有实现完整 ExecutionContext、VariableResolver、ResponseExtractor 或 VariableWriteBackService。
- [ ] 边界测试证明 V3-1 没有实现完整 Suite Failure Analysis、Replanning 或 Human-in-the-loop 链路恢复。
- [ ] 边界测试证明 V3-1 没有实现完整 V3 Memory Feedback、V3 Agent Evaluation 或 V3 Light Console。
- [ ] 边界测试证明 JSON/Markdown report、diagnostics 和 artifact metadata 不泄露 token、authorization、cookie、password、secret、apiKey 等敏感字段。
- [ ] V3-1 关键路径有测试覆盖：harness contract、fixture registry、smoke run、订单 fake HTTP execution、staged sections、安全诊断、脱敏、本地运行入口和 artifact 输出。
- [ ] 完整后端测试在 fake provider 模式下稳定通过。

## Blocked by

- Issue 01：Harness contract, fixture registry and smoke run foundation
- Issue 02：Order suite fixture and fake HTTP execution path
- Issue 03：Staged V3 sections and phase integration slots
- Issue 04：Provider, external HTTP safety, diagnostics and redaction
- Issue 05：Local run command, output directory and usage notes
