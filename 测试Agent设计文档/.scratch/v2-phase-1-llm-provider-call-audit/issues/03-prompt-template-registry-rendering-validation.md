状态：ready-for-agent

# Issue 03：Prompt template registry and rendering validation

## Parent

V2 Phase 1：LLM Provider 与调用审计 PRD

## What to build

建立 prompt template registry，用于管理 prompt 模板的用途、版本、输入变量和输出约束。调用方应能根据模板 id 或用途取到模板，传入变量后得到渲染后的 prompt。缺少变量时应返回明确错误，且不能调用 provider。

本阶段模板可以先以内置 registry 或配置类实现，不需要数据库化模板管理。

## Acceptance criteria

- [ ] Prompt 模板包含模板 id、用途、版本和正文。
- [ ] Prompt 模板可以声明必需输入变量。
- [ ] Prompt 模板可以声明输出格式或输出约束说明。
- [ ] Registry 可以按模板 id 或用途解析模板。
- [ ] 模板变量可以被稳定渲染。
- [ ] 缺少必需变量时返回 TEMPLATE_RENDER_ERROR。
- [ ] 缺少变量时不会调用 provider。
- [ ] 模板版本信息可以被后续调用日志记录。
- [ ] 测试覆盖渲染成功、缺失变量、模板不存在等情况。

## Blocked by

- Issue 01：LLM domain model and provider abstraction
- Issue 02：Fake LLM Provider for deterministic CI
