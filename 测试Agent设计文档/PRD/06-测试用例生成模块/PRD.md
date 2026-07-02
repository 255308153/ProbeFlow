# 06-测试用例生成模块 PRD

## 1. 模块定位

测试用例生成模块是测试 Agent 的核心生产模块，将接口结构、业务上下文和历史经验转化为可执行的测试用例。它位于接口分析模块下游、编排系统上游，输出结构化 `TestCase[]` 供执行引擎直接消费。

**核心目标不是"让 AI 随便写几条 case"，而是建立一套"基础覆盖稳定、业务场景可增强、历史经验可复用、输出结构可执行"的用例生成体系。**

## 2. 核心设计原则

### 2.1 规则保证基础覆盖

基础测试场景（正常请求、必填缺失、类型错误、边界值、非法枚举、鉴权缺失）由规则系统稳定生成，不依赖 AI 推理。

### 2.2 AI 补充业务深度

复杂业务场景（业务规则异常、状态流转、幂等性、权限差异）由 AI 结合知识库和 UnifiedApiContext 补充生成。

### 2.3 记忆增强项目经验

基于长期记忆中的历史失败模式、项目高风险场景，补充经验型测试点。让系统越用越强。

### 2.4 输出必须可执行

生成的不只是"标题列表"，而是可直接交给执行引擎的 `TestCase`，包含完整 inputData、断言定义和提取规则。

### 2.5 串行增强链，不并行

三层生成有明确的依赖关系：后一层需要知道前一层"已经覆盖了什么"。因此采用串行增强链而非并行。

### 2.6 Draft 绑定 Task，TestCase 归属 TestPlan

- `TestCaseDraft` 是任务态对象，绑定 `taskId`，是本次生成任务的产物
- 用户确认后草稿提升为正式 `TestCase`，归属 `ApiSpec`（primaryApiSpecId），脱离 `Task`
- 同一 ApiSpec 下通过 `dedupKey` 去重，`manualEdited`/`locked` 保护人工修改

## 3. 总架构

```
UnifiedApiContext + ApiSpec + Memory
  ↓
CaseGenerationEngine（主入口）
  │
  ├─ ① StructureCaseGenerator（规则系统，本地代码）
  │     └─ 输出：TestCaseDraft[] (source=STRUCTURE)
  │
  ├─ ② BusinessCaseGenerator（AI 增强，LLM 调用）
  │     └─ 输入：UnifiedApiContext + 知识库 + ① 已覆盖场景
  │     └─ 输出：TestCaseDraft[] (source=BUSINESS)
  │
  ├─ ③ MemoryCaseEnhancer（AI 增强，LLM 调用）
  │     └─ 输入：长期记忆 + 历史失败模式 + ①② 已覆盖场景
  │     └─ 输出：TestCaseDraft[] (source=MEMORY)
  │
  ├─ ④ AssertionSuggestionGenerator
  │     └─ 为所有 draft 统一附加断言建议
  │
  └─ ⑤ CaseNormalizer
        └─ 硬标准化 + 轻风格统一 → TestCase[]
```

### 为什么是串行不是并行？

- ① 先跑，把基础覆盖打底
- ② 再补，知道哪些结构场景已经覆盖，避免 AI 重复发明基础 case
- ③ 最后补，知道"结构+业务"已经覆盖了什么，记忆层只补高价值缺口
- ④ 统一挂断言，避免各层各自生成风格不同的断言
- ⑤ 最后统一格式

### 为什么 Business 和 Memory 是两次独立 LLM 调用？

| 维度 | BusinessCaseGenerator | MemoryCaseEnhancer |
|------|----------------------|--------------------|
| 输入源 | UnifiedApiContext + 知识库 | 长期记忆 + 历史失败模式 |
| 目标 | 补业务深度 | 补项目经验 |
| 召回 Profile | CASE_GENERATION_PROFILE（知识库） | CASE_GENERATION_PROFILE（记忆系统） |
| 单次输出量 | 3~8 条 | 2~5 条 |

两次调用职责分明，各自可独立调试、控量、评测。

## 4. 模块拆分

| 组件 | 职责 | 执行方式 |
|------|------|---------|
| `CaseGenerationEngine` | 主入口，协调整个生成流程 | 本地代码 |
| `StructureCaseGenerator` | 调用规则系统，生成基础结构型 case | 本地代码 |
| `BusinessCaseGenerator` | 结合知识库和 UnifiedApiContext，生成业务场景 case | AI (LLM) |
| `MemoryCaseEnhancer` | 结合长期记忆和历史失败，补充经验 case | AI (LLM) |
| `AssertionSuggestionGenerator` | 为所有 case 统一附加断言建议 | 本地代码 + AI 辅助 |
| `CaseNormalizer` | 结构标准化 + 轻风格统一 | 本地代码 |

## 5. 主生成流程

```
1. CaseGenerationEngine 接收生成请求
   ├─ ApiSpec（接口结构）
   ├─ UnifiedApiContext（业务上下文 + 代码上下文）
   ├─ MemoryContext（记忆召回结果）
   └─ GenerationConfig（配置：各类预算、开关）

2. StructureCaseGenerator.generate(apiSpec, config)
   ├─ 调用 ConstraintExtractor → RuleEngine
   ├─ 输出：TestCaseDraft[] (source=STRUCTURE)
   └─ 标记已覆盖场景：Set<CoverageKey>

3. BusinessCaseGenerator.generate(apiSpec, unifiedContext, coveredKeys, config)
   ├─ 构建 prompt（含 UnifiedApiContext + 知识库上下文 + 已覆盖场景清单）
   ├─ 单次 LLM 调用
   ├─ 解析输出为 TestCaseDraft[] (source=BUSINESS)
   └─ 更新已覆盖场景

4. MemoryCaseEnhancer.enhance(apiSpec, memoryContext, coveredKeys, config)
   ├─ 构建 prompt（含历史失败模式 + 项目高频风险 + 已覆盖场景清单）
   ├─ 单次 LLM 调用
   ├─ 解析输出为 TestCaseDraft[] (source=MEMORY)
   └─ 最终已覆盖场景

5. AssertionSuggestionGenerator.generate(allDrafts, apiSpec, unifiedContext)
   ├─ 为每条 draft 附加 assertionDefinitions
   └─ 按 caseType 自动补基础断言

6. CaseNormalizer.normalize(allDrafts, taskId, apiSpec)
   ├─ 硬标准化：字段补齐、枚举校验、格式统一
   ├─ 轻风格统一：title 模板化、description 压缩
   ├─ 去重合并
   └─ 输出：TestCase[]
```

## 6. StructureCaseGenerator

### 6.1 职责

调用规则系统（05-规则系统），将 ApiSpec 的结构约束稳定翻译为基础测试点。

### 6.2 输入

- `ApiSpec`：接口结构定义（路径、方法、参数、字段约束）
- `GenerationConfig`：规则开关、预算配置

### 6.3 输出

- `TestCaseDraft[]`：每条标记 `source=STRUCTURE`
- `Set<CoverageKey>`：标记已覆盖的场景维度，供下游增强器使用

### 6.4 CoverageKey 定义

```java
public class CoverageKey {
    String dimension;    // NORMAL, MISSING_REQUIRED, INVALID_TYPE, BOUNDARY, INVALID_ENUM, AUTH_MISSING
    String targetField;  // 字段路径（仅字段级规则有值），接口级规则为 null
}
```

示例：BusinessCaseGenerator 收到 `{dimension=MISSING_REQUIRED, targetField=body.userId}` 后，知道不需要再生成"userId 缺失"的 case。

### 6.5 数量控制

按规则系统三层预算执行：字段风险排序 → 规则预算筛选 → 接口总预算裁剪。大多数接口产出 8~15 条结构型 draft。

## 7. BusinessCaseGenerator

### 7.1 职责

结合 UnifiedApiContext 和知识库，生成规则系统无法覆盖的业务深度场景。

### 7.2 输入

- `ApiSpec`
- `UnifiedApiContext`（含 knowledgeContext：businessFlow、ruleSummary、riskSummary）
- `Set<CoverageKey>`：① 已覆盖场景
- `GenerationConfig`

### 7.3 生成重点

| 类型 | 说明 | 依赖信息 |
|------|------|---------|
| 业务规则异常 | 违反业务规则的场景 | knowledgeContext.ruleSummary |
| 角色权限差异 | 不同角色访问同一接口 | knowledgeContext.businessFlow |
| 状态流转异常 | 非法的状态跳转 | knowledgeContext.businessFlow |
| 幂等性问题 | 重复提交、重复操作 | knowledgeContext.riskSummary |
| 前置依赖不满足 | 缺少前置步骤的调用 | knowledgeContext.businessFlow |
| 跨步骤依赖异常 | 多步骤链路中某步失败的影响 | knowledgeContext.businessFlow |

### 7.4 Prompt 结构模板

```
System: 你是一个测试用例生成专家，负责为接口测试补充业务场景。

输入：
1. 接口定义：{apiSpec}
2. 业务上下文：{knowledgeContext}
3. 已覆盖的测试维度（请勿重复生成）：{coveredKeys}

要求：
- 生成 3~8 条业务场景测试用例
- 每条包含：caseType=业务相关枚举值, title, description, inputData（相对正常请求的改动）, expectedStatusCode, expectedErrorCode, riskLevel
- 不要生成与已覆盖维度重复的 case
- 所有断言字段留空，由 AssertionSuggestionGenerator 统一补充

输出格式：JSON
[
  { "caseType": "BUSINESS_RULE_EXCEPTION", "title": "...", ... },
  ...
]
```

### 7.5 输出

- `TestCaseDraft[]`：每条标记 `source=BUSINESS`
- 生成数量：3~8 条（由 `perSourceBudget.business: 8` 控制上限）

## 8. MemoryCaseEnhancer

### 8.1 职责

从长期记忆和历史失败中提取项目特有的高风险场景，补充经验型测试点。

### 8.2 输入

- `ApiSpec`
- `MemoryContext`（记忆系统 CASE_GENERATION_PROFILE 召回结果：testing_pattern、failure_pattern）
- `Set<CoverageKey>`：①+② 已覆盖场景
- `GenerationConfig`

### 8.3 生成重点

| 类型 | 说明 | 依赖信息 |
|------|------|---------|
| 历史失败模式 | 该项目/接口历史上高频失败的模式 | failure_pattern（相似接口、相似错误码） |
| 项目特殊规则 | 该项目特有的鉴权方式、签名字段顺序等 | project_knowledge |
| 团队特别关注风险 | 团队历史事故复盘中的关键场景 | preference, testing_pattern |

### 8.4 Prompt 结构模板

```
System: 你是一个基于历史经验补充测试用例的专家。

输入：
1. 接口定义：{apiSpec}
2. 历史经验：{memoryContext.highRiskExperiences, similarFailures, recommendedAssertions}
3. 已覆盖的测试维度（请勿重复生成）：{coveredKeys}

要求：
- 生成 2~5 条基于历史经验的测试用例
- 每条包含：caseType, title, description, inputData, expectedStatusCode, expectedErrorCode, riskLevel
- 优先覆盖历史失败模式中的相似场景
- 不要生成与已覆盖维度重复的 case

输出格式：JSON
```

### 8.5 输出

- `TestCaseDraft[]`：每条标记 `source=MEMORY`
- 生成数量：2~5 条（由 `perSourceBudget.memory: 5` 控制上限）

## 9. AssertionSuggestionGenerator

### 9.1 职责

为所有来源的 TestCaseDraft 统一附加断言定义。核心价值：各层生成的 case 可能带断言也可能不带，这一步统一补齐，保证输出结构完整性。

### 9.2 断言附加策略

| 步骤 | 说明 |
|------|------|
| 1. 继承已有 | 如果 draft 自带了 assertionDefinitions，保留 |
| 2. caseType 自动补 | 按 caseType 自动补状态码断言（见下表） |
| 3. AI 补业务断言 | 对 BUSINESS 和 MEMORY 来源的 case，调用 AI 补充业务字段断言 |
| 4. 系统补基础断言 | 对所有 case 补 Content-Type、响应时间等基础断言 |

### 9.3 caseType → 自动补状态码断言

| caseType | 自动补断言 |
|----------|-----------|
| NORMAL | STATUS_CODE = 200 |
| AUTH_FAILURE | STATUS_CODE IN [401, 403] |
| REQUIRED_FIELD_MISSING | STATUS_CODE = 400 |
| INVALID_TYPE | STATUS_CODE = 400 |
| BOUNDARY_VALUE | min/max 边界 → 200; min-1/max+1 → 400 |
| INVALID_ENUM | STATUS_CODE = 400 |
| BUSINESS_RULE_EXCEPTION | 依赖 AI 推断（可能是 200 + errorCode，也可能是 400） |
| DEPENDENCY_EXCEPTION | 依赖 AI 推断 |

### 9.4 AI 补业务断言（仅对 BUSINESS 和 MEMORY 来源）

```
System: 为以下测试用例补充断言建议。

输入：
- 接口定义：{apiSpec}
- 用例信息：{draft}
- 知识库推荐断言模板：{memoryContext.recommendedAssertions}

要求：
- 补充 JSONPath 断言（如 $.code, $.data.xxx, $.msg）
- 补充关键字段存在性断言
- 每条断言附 severity (CRITICAL/WARNING/INFO)
- source 标记为 AI_GENERATED

输出格式：AssertionDefinition[]
```

### 9.5 输出

每个 draft 补充完整的 `assertionDefinitions[]`。每条断言标记 `source`：`SYSTEM_GENERATED` / `AI_GENERATED`。

## 10. CaseNormalizer

### 10.1 职责

把所有不同来源的 TestCaseDraft 统一为标准 TestCaseDraft，保证结构一致性。Normalizer 输出的是标准化 draft，`TestCase` 由用户确认后的提升动作（Promotion）创建或更新。

### 10.2 硬标准化

| 动作 | 规则 |
|------|------|
| draftId 生成 | UUID |
| coverageKey 计算 | `dedupKey = caseType + targetField + normalizedInputPatch + expectedStatusCode` |
| 枚举值校验 | caseType、riskLevel、priority、source 取值必须在定义范围内 |
| 字段补齐 | 缺失的 required 字段填充默认值（title 不能为空、riskLevel 默认 MEDIUM） |
| tags 格式统一 | 全小写，下划线连接 |
| assertionDefinitions 结构统一 | 每条断言必含 assertionId, assertionType, operator, target, expectedValue, severity, source |

### 10.3 轻风格统一

| 动作 | 规则 |
|------|------|
| title 模板化 | `{接口名}-{场景类型}-{关键变化点}`，如 `创建订单-必填缺失-userId` |
| description 压缩 | 1~2 句，保留核心测试语义；去掉"本用例旨在..."、"测试目的："等冗余前缀 |
| 字段命名统一 | camelCase |

### 10.4 不做的事

- 不重写核心测试语义（不把"测 userId 缺失"改成别的测试目标）
- 不做 LLM 语义去重（去重由各生成器内部 + CoverageKey 机制覆盖）
- 不修改 inputData 中的业务值（只做格式标准化）

## 11. 人工编辑保护与草稿提升机制

### 11.1 保护字段（位于 TestCase，非 Draft）

| 字段 | 类型 | 说明 |
|------|------|------|
| `manualEdited` | Boolean | 用户是否手工修改过。默认 false，用户编辑后置 true |
| `locked` | Boolean | 用户是否明确锁定，不允许任何自动覆盖。默认 false |

### 11.2 Draft → TestCase 提升流程

```
TestCaseDraft (绑定 taskId, targetApiSpecId)
  ↓ 用户确认
  按 dedupKey 在同一 primaryApiSpecId 下查找已有 TestCase
  ├─ 找到匹配：
  │   ├─ locked=true → 跳过，draft 标记 DISCARDED
  │   ├─ manualEdited=true → draft 标记为"候选更新"，用户逐条选择采纳
  │   └─ 都 false → 写 ChangeLog(beforeSnapshot) → 更新 TestCase.detail → draft 标记 PROMOTED
  └─ 未找到匹配：
      → 新建 TestCase（归属 ApiSpec） → draft 标记 PROMOTED
```

### 11.3 保护规则

| 条件 | 重新生成时的行为 |
|------|----------------|
| `locked = true` | **绝不覆盖**，跳过该 case |
| `manualEdited = true`（未锁定） | 不直接覆盖，新生成结果作为"候选更新"附加，由用户决定是否采纳 |
| `manualEdited = false` + `locked = false` | 自动替换为同 CoverageKey 的新生成 case（覆盖前写 ChangeLog） |

### 11.4 第一版简化策略

- 用户在 UI 上编辑某条 case → 自动标记 `manualEdited=true`
- 用户在 UI 上点击"锁定" → 标记 `locked=true`
- 重新生成时，前端展示"受保护 case（N 条）"和"候选更新（M 条）"
- 用户可逐条选择采纳或拒绝候选更新
- 纯自动生成未修改的 case 不提示，直接替换

## 12. 数量预算控制

### 12.1 分来源预算

```yaml
caseGeneration:
  perSourceBudget:
    structure: 15      # 规则系统产出上限（实际由规则系统三层预算控制）
    business: 8         # AI 业务增强产出上限
    memory: 5           # AI 记忆增强产出上限
  totalMaxCases: 25     # 全部来源合并后上限
```

### 12.2 裁剪优先级

如果合并后超 `totalMaxCases`，按以下优先级保留：

```
1. structure 来源（优先保留，保证基础覆盖）
2. memory 来源（历史经验不可替代）
3. business 来源（最后裁剪）
```

同来源内部按 `riskLevel` 降序排列裁剪。

## 13. 第一版支持的用例类型

| caseType | 来源 | 说明 |
|----------|------|------|
| `NORMAL` | STRUCTURE | 正常请求 |
| `REQUIRED_FIELD_MISSING` | STRUCTURE | 必填字段缺失 |
| `INVALID_TYPE` | STRUCTURE | 参数类型错误 |
| `BOUNDARY_VALUE` | STRUCTURE | 边界值 |
| `INVALID_ENUM` | STRUCTURE | 非法枚举值 |
| `AUTH_FAILURE` | STRUCTURE | 鉴权缺失 |
| `BUSINESS_RULE_EXCEPTION` | BUSINESS | 业务规则异常 |
| `DEPENDENCY_EXCEPTION` | BUSINESS / MEMORY | 依赖/前置条件异常 |
| `HISTORICAL_FAILURE` | MEMORY | 历史失败模式场景 |

## 14. 完整输入输出

### 输入

```
ApiSpec               // 接口结构定义（来自 04-接口分析）
UnifiedApiContext     // 业务 + 代码统一上下文（来自 04）
MemoryContext         // 记忆召回结果（来自 02-记忆系统）
GenerationConfig      // YAML 配置（来源预算、开关）
ExistingCases         // 已存在的 TestCase（检测 manualEdited/locked，按 dedupKey 去重）
```

### 输出

```
TestCaseDraft[]        // 任务驱动草稿列表
  ├─ draftId, taskId, targetApiSpecId, dedupKey, source, stage, status
  └─ draftContent (ApiTestCaseDetail / FunctionalTestCaseDetail 含 assertionDefinitions)

← 用户确认后 →

TestCase[]             // 正式测试用例资产
  ├─ caseId, primaryApiSpecId（归属 ApiSpec，脱离 Task）
  ├─ mode (SINGLE / SUITE)
  ├─ title, description, priority, riskLevel, tags, scenarioName, moduleName
  ├─ source (STRUCTURE / BUSINESS / MEMORY / MANUAL)
  ├─ manualEdited, locked
  ├─ SINGLE: detail (ApiTestCaseDetail)
  └─ SUITE: steps[] (TestCaseStep[], 每个 step 含 apiSpecId)
```

## 15. 与各模块的关系

| 模块 | 关系 |
|------|------|
| 04-接口自动分析 | 依赖 ApiSpec + UnifiedApiContext |
| 05-规则系统 | StructureCaseGenerator 调用规则系统 |
| 02-记忆系统 | MemoryCaseEnhancer 依赖 CASE_GENERATION_PROFILE 召回 |
| 07-断言系统 | AssertionSuggestionGenerator 输出的 assertionDefinitions 被断言系统消费 |
| 08-接口测试执行引擎 | TestCase 被执行引擎直接消费（RequestBuilder + VariableResolver） |
| 01-编排系统 | 编排系统决定何时触发生成、对哪些接口生成 |

## 16. Java 包结构建议

```
com.xxx.agent.casegen
com.xxx.agent.casegen.engine          // CaseGenerationEngine
com.xxx.agent.casegen.structure        // StructureCaseGenerator
com.xxx.agent.casegen.business         // BusinessCaseGenerator
com.xxx.agent.casegen.memory           // MemoryCaseEnhancer
com.xxx.agent.casegen.assertion        // AssertionSuggestionGenerator
com.xxx.agent.casegen.normalize        // CaseNormalizer
com.xxx.agent.casegen.protect          // ManualEditProtector（人工编辑保护）
com.xxx.agent.casegen.config           // GenerationConfig
com.xxx.agent.casegen.model            // TestCaseDraft, CoverageKey, CaseType
com.xxx.agent.casegen.rule             // 规则系统（05 模块，casegen 内部调用）
com.xxx.agent.casegen.value            // ValueFactory
```

## 17. 第一版落地范围

### 包含

- 串行增强链：Structure → Business → Memory → Assertion → Normalizer
- 9 种 caseType 支持
- Business 和 Memory 两次独立 LLM 调用
- 人工编辑保护（manualEdited + locked）
- 分来源数量预算控制
- CaseNormalizer（硬标准化 + 轻风格统一）
- AssertionSuggestionGenerator（自动补 + AI 补）

### 暂不包含

- 功能测试用例完整生成链路（V1 优先接口测试，功能用例先支撑基础结构）
- 多接口联合场景自动生成
- 用例优先级 AI 排序
- 生成质量 AI 自动评测
- 可视化用例编辑 IDE

## 18. 一句话总结

测试用例生成模块的核心是：**用规则保证基础覆盖，用 AI 补充业务深度，用记忆增强项目经验，用串行增强链保证不重复造轮子，用人工编辑保护机制防止覆盖用户修改，并统一输出可执行 TestCase。**
