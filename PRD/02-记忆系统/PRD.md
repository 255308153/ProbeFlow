# 02-记忆系统 PRD

## 1. 模块定位

记忆系统是测试 Agent 的基础能力层，负责跨会话、跨任务的**经验沉淀与按需召回**。它不是一个独立的知识库产品，而是直接服务于用例生成、接口执行、失败分析和编排决策。

核心思路：

```
Mem0 风格记忆提纯 + Viking 风格统一上下文 + ClaudeCode 风格异步提取
```

## 2. 总体架构

### 2.1 记忆层次

```
┌──────────────────────────────────────────────────┐
│                 Unified Context Builder           │  ← 读路径（按需组装）
├───────────────┬──────────────┬───────────────────┤
│ Session Memory│ Task Memory  │ Long-term Memory  │  ← 存储层
│   (Redis)     │  (PG)        │  (PG + pgvector)  │
└───────────────┴──────────────┴───────────────────┘
         ▲              ▲               ▲
         │              │               │
         └──────────────┼───────────────┘
                        │
              Memory Refinery (提纯层)              ← 写路径（异步）
```

### 2.2 核心数据流

```
回合/阶段/任务结束
  → Observation + Task Memory + 失败结果 → 候选池
  → Memory Refinery（异步，不阻塞主流程）
  → Long-term Memory（PG + pgvector）

查询时：
  → Unified Context Builder
  → 结构召回 + 标签召回 + 向量召回 → 重排 → Top 5
  → ContextBundle 注入 Agent
```

## 3. 分层定义

### 3.1 Session Memory

| 项 | 说明 |
|---|------|
| 定位 | 当前会话的短期上下文 |
| 生命周期 | 单次会话，或 TTL（24 小时）|
| 更新频率 | 高，每轮都可能写入 |
| 存储 | Redis（带 TTL）|

**存放内容：**

- 当前任务目标与需求描述
- 最近 N 轮关键结论与决策
- 最近一次工具调用摘要
- 最近一次失败原因与重试状态
- 当前阶段中间决策痕迹

**不存放：**
- 原始大段对话全文
- 一次性临时日志
- 工具原始输出

### 3.2 Task Memory

| 项 | 说明 |
|---|------|
| 定位 | 当前测试任务的持续状态与事实积累 |
| 生命周期 | 覆盖整个任务周期，任务结束后保留 N 天后归档 |
| 更新频率 | 中，每个执行步骤写入 |
| 存储 | PostgreSQL（与 taskId 强绑定）|

**存放内容：**

- 任务基础信息（需求、接口、模块、优先级）
- 已生成的测试用例摘要
- 已执行的接口测试记录摘要
- 断言结果、失败样本、异常响应
- 各阶段分析结论
- 下一步动作建议

**查询方式：**
- 按 taskId 精确查询，全量同步读取（不检索）

### 3.3 Long-term Memory

| 项 | 说明 |
|---|------|
| 定位 | 跨任务可复用的提纯经验 |
| 生命周期 | 长期，按衰减策略淘汰 |
| 更新频率 | 低，仅异步提纯后写入 |
| 存储 | PostgreSQL + pgvector |

**分类（scopeType）：**

| 类型 | 说明 | 示例 |
|------|------|------|
| `project_knowledge` | 项目级约定与规则 | 接口约定、鉴权方式、环境配置 |
| `testing_pattern` | 可复用测试策略 | 断言模板、覆盖套路、数据准备模式 |
| `failure_pattern` | 常见失败模式 | 高频错误码、字段缺失模式、环境异常 |
| `preference` | 用户/团队偏好 | 报告风格、case 粒度偏好、输出格式 |

**字段模型（见数据模型 PRD 4.6 节），额外关键字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `embedding` | vector(1024) | bge-m3 向量（维度 1024） |
| `fullContent` | TEXT | 提纯前的完整上下文，备查 |
| `importance` | Float (0-1) | 重要度评分 |
| `hitCount` | Integer | 被召回次数 |
| `successContribution` | Float (0-1) | 命中后对任务有帮助的比率 |
| `confidence` | Float (0-1) | 提纯置信度 |
| `version` | Integer | 提纯版本号（合并/更新时递增） |
| `status` | Enum | `ACTIVE` / `INACTIVE` / `ARCHIVED` |

## 4. 记忆提纯（Memory Refinery）

### 4.1 提纯原则

**核心原则：不是全量存储，而是提炼后再写入。**

提纯过程执行以下动作：

```
extract → classify → deduplicate → merge → compress → tag
```

### 4.2 提纯触发时机（三层）

```
┌──────────────┬──────────────────┬────────────────────────────────┐
│ 时机         │ 触发条件          │ 动作                           │
├──────────────┼──────────────────┼────────────────────────────────┤
│ 回合后异步   │ 每轮编排执行结束   │ Observation/失败结果 入候选池   │
│              │                   │ 后台异步提纯，不阻塞主流程       │
├──────────────┼──────────────────┼────────────────────────────────┤
│ 阶段结束时   │ 一批接口跑完 /     │ 合并本阶段候选记忆              │
│              │ 一个模块测试完成   │ 去重 + 压缩 + 分类              │
├──────────────┼──────────────────┼────────────────────────────────┤
│ 任务结束后   │ Task 状态 →        │ 最终提纯                        │
│              │ COMPLETED/FAILED  │ 跨任务可复用经验入 Long-term     │
└──────────────┴──────────────────┴────────────────────────────────┘
```

### 4.3 提纯流程（借鉴 ClaudeCode）

```
1. Cursor 标记已处理范围（lastProcessedUuid）
2. 收集增量候选：Observation + Task Memory + 失败结果
3. 判断是否值得提纯（confidence > 阈值）：
   ├─ 跨任务可复用 → 进入提纯流程
   ├─ 能归纳成稳定模式 → 进入提纯流程
   └─ 一次性噪声 / 临时中间推理 / 低价值 → 丢弃
4. AI 执行提纯（extract → classify → deduplicate → merge → compress → tag）
5. 生成 embedding（bge-m3，text = title + summary + tags）
6. 写入 Long-term Memory（PG + pgvector）
7. 更新 cursor
```

**节流与合并（借鉴 ClaudeCode）：**

- 如果上一次提纯还在跑，新的请求 stash，等当前跑完后只跑一次 trailing extraction
- 提纯用独立 agent 线程，turn 上限为 5，防止跑飞
- 如果同一回合内用户显式已操作过记忆写入，跳过自动提纯（避免重复）
- 每 N 轮才触发一次（可配置，默认 1）

### 4.4 提纯判断标准

**适合提纯：**

- 跨任务可复用
- 对后续决策有帮助
- 能归纳成稳定模式
- 能被标签化和检索
- 与任务无关的关键发现

**不适合提纯（丢弃）：**

- 一次性偶发日志全文
- 临时中间推理
- 与任务目标无关的上下文
- 太细碎、无复用价值的片段
- 已在代码/文档中可推导的信息

### 4.5 提纯示例

**原始输入（Observation）：**
> 用户项目中的支付接口在测试环境下，sign 字段必须放在 body 最后一个参数位置，否则返回 401。该现象在 dev/test 环境均复现，疑似网关签名校验顺序依赖。

**提纯后 Long-term Memory：**

```
type: project_knowledge
title: 支付接口 body 签名字段顺序约束
summary: 支付相关接口在测试环境中要求 sign 字段位于 body 最后位置，
        否则返回 401。dev/test 环境均存在此约束。
tags: [payment, signature, test-env, 401, body-order]
importance: 0.8
confidence: 0.85
```

## 5. 统一上下文构建（Unified Context Builder）

### 5.1 定位

读路径核心。不是简单检索，而是根据当前任务阶段，从多个来源按需组装上下文。

### 5.2 读取来源

```
Session Memory    → 当前会话最近决策痕迹
Task Memory       → 当前任务所有积累信息（全量）
Long-term Memory  → 按召回策略取 Top 5
外部材料          → 需求文档、OpenAPI、历史报告
```

### 5.3 召回链路

**Step 1：确定召回源（按任务阶段）**

| 阶段 | 召回重点 |
|------|---------|
| 生成 case | testing_pattern + project_knowledge + preference |
| 执行前 | project_knowledge（鉴权/环境）+ 历史高风险 |
| 失败分析 | failure_pattern + 相似错误码 + 历史排查经验 |

**Step 2：四路并行召回**

```
结构召回 (weight 0.35)：  system/module/api/path/httpMethod/caseType/errorCode
标签召回 (weight 0.30)：  riskTags/businessEntity/assertionType/authType
向量召回 (weight 0.20)：  bge-m3 embedding 语义相似度（cosine distance）
新鲜度   (weight 0.15)：  lastUsedAt/hitCount/successContribution
```

**Step 3：重排公式**

默认：
```
finalScore = 0.35 × structure_match + 0.30 × tag_match + 0.20 × vector_similarity + 0.15 × freshness_score
```

失败分析阶段动态调整：
```
finalScore = 0.20 × structure + 0.15 × tag + 0.15 × vector + 0.10 × freshness + 0.40 × failure_match
```

**Step 4：取 Top 5 + 结构化输出**

不把召回结果原文全塞给 Agent，而是整理成结构化上下文：

```
ContextBundle {
  taskContext:       [...],  // 当前 Task Memory 全量
  sessionContext:    [...],  // 最近几轮决策结论
  highRiskExperiences: [{    // 当前接口相关高风险经验
    title: "...",
    summary: "...",
    source: "long_term_memory"
  }],
  recommendedAssertions: [...], // 推荐断言模板
  similarFailures:      [...], // 历史相似失败
  recommendedAuth:      "...",  // 推荐鉴权方式
}
```

### 5.4 向量检索细节

- **距离度量**：cosine distance
- **Embedding 模型**：bge-m3（维度 1024）
- **Embedding 生成**：写入时生成（不是查询时实时生成）
- **降级策略**：如果 pgvector 不可用，仅使用结构召回 + 标签召回 + 新鲜度三路

### 5.5 不读取的内容

- 与当前项目/模块无关的记忆
- 已标记 ARCHIVED 的记忆
- 被证明无效的记忆（successContribution 极低且 hitCount > 阈值）

## 6. 记忆淘汰策略

### 6.1 Session Memory 淘汰

```
策略：TTL 窗口淘汰
默认：Redis TTL 24 小时
    或保留最近 N 轮（可配置，默认 10 轮）
```

### 6.2 Task Memory 淘汰

```
策略：跟随任务生命周期
- 任务运行中：保留
- 任务结束后：保留 30 天（可配置）
- 30 天后：归档，仅保留提纯后的 Long-term Memory
```

### 6.3 Long-term Memory 淘汰

**三段式：衰减 → 归档 → 删除**

**衰减分公式：**

```
memoryScore = importance × 0.4
            + normalizedHitCount × 0.2
            + recencyScore × 0.2
            + successContribution × 0.2
```

其中：
- `normalizedHitCount = min(hitCount / 10, 1.0)`（10 次命中即为满分）
- `recencyScore = 1 / (1 + daysSinceLastUsed / 30)`（30 天半衰）
- `successContribution` 初始值 0.3，每次命中后根据结果更新

**淘汰动作：**

```
memoryScore < 0.3 → INACTIVE（降权，下次召回不入围）
memoryScore < 0.15 且 INACTIVE 超过 30 天 → ARCHIVED（归档）
ARCHIVED 超过 90 天 → 物理删除
```

**特殊规则：**

- 多次被新记忆覆盖 → 直接标记 INACTIVE
- 与当前代码/规则冲突 → 直接标记 INACTIVE
- 命中后被判断为无帮助 → successContribution 快速降权
- importance > 0.8 的记忆永不自动归档（需人工审核）

## 7. ClaudeCode 借鉴点总结

| 模式 | ClaudeCode 做法 | 本系统应用 |
|------|----------------|-----------|
| 异步提取 | hook 触发 forked agent 后台跑 | 回合/阶段/任务结束时后台异步提纯 |
| 节流合并 | 上一次未完成则 stash，跑完后 trailing | 同：pending 合并，只跑一次 trailing |
| 光标增量 | lastMemoryMessageUuid 标记已处理 | lastProcessedId 标记已提纯范围 |
| Turn 上限 | maxTurns: 5 防止跑飞 | 提纯 agent 限制 5 轮 |
| 避免重复 | 用户显式写入了则跳过 | 同：已提纯内容不重复提纯 |
| Header 扫描 | 读 frontmatter → 小模型筛选 Top 5 | 我们走 DB 查询 + 向量检索，但筛选思想一致 |

## 8. 第一版落地范围

**包含：**

- Session Memory（Redis + TTL）
- Task Memory（PG，全量同步读取）
- Long-term Memory（PG + pgvector，bge-m3 embedding）
- Memory Refinery（三层触发、异步提纯、节流合并）
- Unified Context Builder（四路混合召回 + 重排 + Top 5 结构化输出）
- 分层淘汰策略

**暂不包含：**

- Team Knowledge Memory（第二版）
- 自动化提纯准确率极致优化（先跑通流程）
- 记忆 A/B 测试框架
- 可视化记忆管理界面

## 9. Java 包结构建议

```
com.xxx.agent.memory
com.xxx.agent.memory.session        // SessionMemoryService
com.xxx.agent.memory.task           // TaskMemoryService
com.xxx.agent.memory.longterm       // LongTermMemoryService
com.xxx.agent.memory.refinery       // MemoryRefineryService（提纯）
com.xxx.agent.memory.context        // UnifiedContextBuilder（召回+组装）
com.xxx.agent.memory.recall         // 多路召回器
com.xxx.agent.memory.recall.structure   // StructureRecall
com.xxx.agent.memory.recall.tag        // TagRecall
com.xxx.agent.memory.recall.vector     // VectorRecall
com.xxx.agent.memory.recall.freshness  // FreshnessRecall
com.xxx.agent.memory.recall.rerank     // RerankService
com.xxx.agent.memory.eviction      // 分层淘汰策略
com.xxx.agent.memory.embedding      // EmbeddingService（bge-m3）
com.xxx.agent.memory.model          // 记忆对象模型
com.xxx.agent.memory.repository     // 数据访问层
```

## 10. 一句话总结

记忆系统的核心不是"多存"，而是 **分层存、异步提纯存、按需混合召回取**。

最终方案：

```
Session Memory (Redis TTL)
+ Task Memory (PG 全量同步读)
+ Memory Refinery (异步三层触发、节流合并)
+ Long-term Memory (PG+pgvector, bge-m3)
+ Unified Context Builder (四路混合召回 + 重排, Top 5 结构化输出)
+ 分层淘汰 (TTL / 生命周期 / 衰减→归档→删除)
```
