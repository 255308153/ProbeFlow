# 04-接口自动分析与知识库融合 PRD

## 1. 模块定位

本模块是测试 Agent 的"感知层"，负责从两个维度理解被测接口：

- **代码维度**：从源码中自动提取接口结构（路径、参数、DTO、校验、鉴权）
- **知识维度**：从项目文档中召回业务语义（流程、规则、风险、规范）

两者通过 Unified Context Builder 融合为 `UnifiedApiContext`，供下游用例生成、执行引擎和断言系统消费。

```
代码自动分析 (Controller + DTO)
        +
知识库 RAG (8 段检索增强链路)
        ↓
Unified Context Builder
        ↓
UnifiedApiContext → 用例生成 / 执行引擎 / 断言系统
```

核心目标不是"把文档存下来"，而是：

**代码告诉 Agent 接口怎么写，知识库告诉 Agent 接口为什么存在、应该重点测什么。**

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                   04-接口自动分析与知识库融合                   │
├───────────────────────────┬─────────────────────────────────┤
│   代码自动分析子系统        │      知识库 RAG 子系统            │
│                           │                                 │
│   FrameworkDetector       │   ① 知识工程 (KnowledgeIngest)    │
│   RouteExtractor          │   ② Query 改写 (QueryRewrite)     │
│   ModelExtractor          │   ③ 混合检索召回                  │
│   ValidationExtractor     │   ④ Rerank (RRF + 权威/新鲜度)    │
│   AuthExtractor           │   ⑤ 截断压缩 (上下文预算控制)      │
│   ServiceContextAnalyzer  │   ⑥ 可信生成 (带证据引用)          │
│         (V2)              │   ⑦ 过程评测                      │
│                           │   ⑧ 反馈闭环                      │
├───────────────────────────┴─────────────────────────────────┤
│                  Unified Context Builder                     │
│                  UnifiedApiContext                            │
└─────────────────────────────────────────────────────────────┘
```

## 3. 核心设计原则

### 3.1 代码分析与知识库互补

- 代码分析回答：接口路径是什么、有哪些参数、怎么校验、怎么鉴权
- 知识库回答：接口在业务流程中的位置、业务规则是什么、历史出过什么问题
- 两者不互相替代，融合后形成完整上下文

### 3.2 知识库 ≠ 文档堆砌

文档需要经过结构化增强（元数据 + 分块 + 索引）才能被有效检索。

### 3.3 RAG 链路分层治理

检索增强不是"搜一下喂给模型"，而是 8 段可控链路：

```
知识工程 → Query改写 → 混合检索召回 → Rerank → 截断压缩 → 可信生成 → 过程评测 → 反馈闭环
```

### 3.4 第一版聚焦接口测试主链路

代码分析 V1 = Controller + DTO 展开。知识库 V1 = 7 类文档 + 3 套阶段 Profile。

---

## 第一部分：代码自动分析子系统

## 4. 分析深度定义

| 层级 | V1 | V2 | 提取内容 |
|------|:--:|:--:|---------|
| Controller 层 | ✅ | ✅ | 路径、HTTP Method、参数来源注解、@RequestBody、校验注解、鉴权注解 |
| DTO 递归展开 | ✅ | ✅ | 字段名、类型、@NotNull/@Size/@Pattern、枚举值、嵌套对象 |
| Service 调用链 | ❌ | ✅ | Service 依赖、DB 操作、外部服务调用、关键分支 |

**V1 目标：** 提取到足够支撑规则引擎生成基础用例 + AI 生成断言建议的程度。

## 5. 代码分析模块拆分

| 组件 | 职责 | V1 |
|------|------|:--:|
| `FrameworkDetector` | 识别 Web 框架类型（Spring Boot 优先） | ✅ |
| `RouteExtractor` | 提取接口路径与 HTTP Method | ✅ |
| `ModelExtractor` | 提取请求参数、DTO 字段、返回对象 | ✅ |
| `ValidationExtractor` | 提取校验注解与约束规则 | ✅ |
| `AuthExtractor` | 提取鉴权注解与要求 | ✅ |
| `ServiceContextAnalyzer` | 分析 Service 调用链与业务上下文 | ❌ V2 |

### 5.1 FrameworkDetector

**目标：** 判断上传项目是否是 Spring Boot，决定后续提取策略。

**多信号打分制：**

| 信号 | 检测方式 | 权重 |
|------|---------|------|
| 构建文件含 `spring-boot-starter-*` | 解析 `pom.xml` / `build.gradle` | **高分 (3)** |
| 存在 `@SpringBootApplication` | AST 扫描启动类 | **高分 (3)** |
| 存在 `@RestController` / `@Controller` | AST 扫描 Controller 类 | **中分 (2)** |
| 标准目录结构 | 检测 `src/main/java` + `application.yml` | **低分 (1)** |

**判定规则：** 总分 ≥ 5 判定为 Spring Boot 项目。

**输入：** 代码目录路径
**输出：** `FrameworkInfo { frameworkType, version, confidence }`

**第一版仅支持 Spring Boot。** 后续可扩展 Express、FastAPI、Django、Gin 等。

### 5.2 RouteExtractor

**目标：** 从 Controller 类提取接口路由。

**Spring Boot 提取规则：**

| 注解 | 提取内容 |
|------|---------|
| `@RestController` / `@Controller` | 标记为 Controller 类 |
| `@RequestMapping("/api/orders")` | 类级别路径前缀 |
| `@GetMapping("/{id}")` | HTTP Method + 路径 |
| `@PostMapping` | HTTP Method + 路径 |
| `@PutMapping("/{id}")` | HTTP Method + 路径 |
| `@DeleteMapping("/{id}")` | HTTP Method + 路径 |
| `@PatchMapping` | HTTP Method + 路径 |

**输出：** `RouteInfo { controllerName, methodName, httpMethod, fullPath, pathParams }`

### 5.3 ModelExtractor

**目标：** 递归提取请求参数和 DTO 字段。

**提取来源：**

| 注解 | 提取内容 |
|------|---------|
| `@RequestParam` | 参数名、类型、是否必填、默认值 |
| `@PathVariable` | 路径参数名、类型 |
| `@RequestBody` | 关联的 DTO 类 |
| `@RequestHeader` | Header 参数 |
| DTO 类字段 | 字段名、类型、注解、嵌套对象引用 |

**递归展开规则：**

1. 从 Controller 方法签名定位入参 DTO
2. 读取 DTO 类的所有字段（含 getter/setter）
3. 如果字段类型是自定义类 → 继续递归展开（最大深度 3 层）
4. 收集每个字段的校验注解

**输出：** `ParameterInfo { name, type, location(PATH/QUERY/HEADER/BODY), required, defaultValue, nestedFields, constraints }`

### 5.4 ValidationExtractor

**目标：** 提取字段级别的校验规则。

| 注解 | 提取为约束 |
|------|-----------|
| `@NotNull` | `required = true` |
| `@NotEmpty` | `required = true, minLength = 1` |
| `@NotBlank` | `required = true, minLength = 1 (trimmed)` |
| `@Size(min=1, max=100)` | `minLength = 1, maxLength = 100` |
| `@Min(0)` / `@Max(999)` | `minValue = 0, maxValue = 999` |
| `@Pattern(regexp="...")` | `pattern = "..."` |
| `@Email` | `format = "email"` |
| `@Positive` / `@Negative` | `minValue = 1` / `maxValue = -1` |
| 枚举类型字段 | `enumValues = [A, B, C]` |

**输出：** `FieldConstraint { fieldPath, dataType, required, nullable, enumValues, minValue, maxValue, minLength, maxLength, pattern, format }`

### 5.5 AuthExtractor

**目标：** 提取接口级鉴权要求。

| 注解 | 提取内容 |
|------|---------|
| `@PreAuthorize("hasRole('ADMIN')")` | `authRequired = true, requiredRoles = ["ADMIN"]` |
| `@Secured("ROLE_USER")` | `authRequired = true, requiredRoles = ["ROLE_USER"]` |
| 类级别 `@PreAuthorize` | 该 Controller 所有接口继承 |
| 无任何鉴权注解 | `authRequired = false`（但仍建议在报告中标出） |

**输出：** `AuthInfo { authRequired, authType(BEARER/API_KEY/BASIC/NONE), requiredRoles, requiredHeaders }`

### 5.6 ServiceContextAnalyzer (V2 预留)

**V2 将支持：**
- 从 Controller → Service → Repository 的调用链分析
- 数据库操作识别（`@Transactional`、`JpaRepository` 方法调用）
- 外部服务依赖识别（`@FeignClient`、`RestTemplate` 调用点）
- 关键业务分支识别（`if/switch` 中涉及的业务状态判断）

**V1 策略：** 预留 `ServiceContextAnalyzer` 接口，默认实现返回空。`codegraph` 可作为 V2 的实现方案之一接入。

## 6. 代码分析执行模式

### 同步快速首屏 + 后台异步增强

```
同步返回 (目标 < 5s)：
  ├─ FrameworkDetector → 项目类型
  ├─ RouteExtractor → 接口列表（路径、Method、Controller 名）
  └─ 基础参数结构（@RequestParam、@PathVariable）

后台异步补强：
  ├─ DTO 递归展开（嵌套对象、完整字段树）
  ├─ 校验注解全量提取
  ├─ 鉴权模式推断
  ├─ 知识库业务上下文召回
  └─ 风险标签生成

前端展示：分析中 → 已补全更多上下文
```

### 分析结果存储

- `ApiSpec` 主表：接口基础信息（方法、路径、Controller）
- `ApiSpec.parameters` JSONB：完整的参数树（含 DTO 展开结果）
- `ApiSpec.constraints` JSONB：所有字段的约束信息
- `ApiSpec.auth` JSONB：鉴权配置

---

## 第二部分：知识库 RAG 子系统

## 7. RAG 8 段总览

```
① 知识工程 → ② Query改写 → ③ 混合检索召回 → ④ Rerank →
⑤ 截断压缩 → ⑥ 可信生成 → ⑦ 过程评测 → ⑧ 反馈闭环
```

## 8. ① 知识工程（Knowledge Engineering）

### 8.1 文档导入

**支持来源：**

| 来源 | 格式 | 导入方式 |
|------|------|---------|
| PRD / 需求文档 | Markdown | 直接导入 |
| 接口补充说明 | Markdown + frontmatter | 直接导入 |
| 测试规范 | Markdown | 直接导入 |
| FAQ | Markdown | 直接导入 |
| 事故复盘 | Markdown | 直接导入 |
| 环境说明 | Markdown | 直接导入 |
| Wiki 导出 | Markdown | 预处理后导入 |
| 代码注释中的业务说明 | JavaDoc | 提取后转为 api_note |

### 8.2 文档清洗

```
原始文档
  → 去除导航栏、侧边栏、页脚等模板噪音
  → 去除重复内容（相同段落去重）
  → 按一级/二级标题拆章节
  → 保留表格、列表等结构化内容
```

### 8.3 元数据抽取

**Markdown frontmatter 格式（推荐）：**

```yaml
---
docId: kb-order-create
title: 订单创建接口业务规则
system: order-system
module: order
docType: api_note
bizEntity: order
tags: [api, order, auth, validation]
authority: high          # high / medium / low
sourceType: wiki         # wiki / prd / faq / postmortem
applicableStages: [api_analysis, case_generation]
version: 1
updatedAt: 2026-07-02
---
```

**从文档内容 + 文件名自动推断未填的元数据字段。**

### 8.4 分块策略

**语义分块（非固定长度）：**

```
优先级 1：按 ## 二级标题分块
优先级 2：按段落分块（保留段落完整性）
优先级 3：表格单独成块
优先级 4：列表块保留整组

每块大小：建议 200-500 tokens
最大块：不超过 1000 tokens
最小块：不低于 50 tokens（太小的合并到上一块）
```

### 8.5 索引构建

```
关键词索引：PG tsvector 全文检索（中文分词用 jieba）
向量索引：pgvector (bge-m3, dimension=1024)
结构索引：BTREE on (system, module, docType, applicableStages)
标签索引：GIN on tags[]
```

### 8.6 知识文档分类

| docType | 说明 | 典型内容 |
|---------|------|---------|
| `business_flow` | 业务流程 | 上下游关系、状态流转、时序依赖 |
| `api_note` | 接口补充说明 | OpenAPI/代码里没写的业务语义 |
| `test_spec` | 测试规范 | 断言规则、测试策略、覆盖标准 |
| `env_guide` | 环境说明 | 鉴权方式、公共前置条件、环境差异 |
| `error_code_guide` | 错误码说明 | 业务失败语义、典型处理方式 |
| `incident_postmortem` | 事故复盘 | 历史事故、风险模式、已知坑点 |
| `domain_rule` | 领域规则 | 行业规则、业务边界、合规限制 |

### 8.7 模块拆分

| 组件 | 职责 |
|------|------|
| `KnowledgeIngestService` | 文档导入与标准化入口 |
| `KnowledgeChunker` | 语义分块 |
| `KnowledgeMetadataExtractor` | 元数据提取与补全 |
| `KnowledgeDocumentRepository` | 原始文档 + chunk + embedding + 版本管理 |

## 9. ② Query 改写（QueryRewrite）

### 9.1 目标

不拿用户原始问题直接去检索，而是先改写成更适合检索的多个 query。

### 9.2 改写流程

```
用户原始输入
  → 意图识别：判断当前阶段 (API_ANALYSIS / CASE_GENERATION / FAILURE_ANALYSIS)
  → 关键词补全：补全接口名、模块名、错误码、业务实体、HTTP Method
  → 多 Query 扩展：原始 query + 关键词 query + 业务语义 query
  → 输出：List<RewrittenQuery>（带权重标记）
```

### 9.3 改写示例

**输入：**
> 帮我分析订单创建接口应该测什么

**意图识别：** `API_ANALYSIS + CASE_GENERATION`

**改写输出：**

| Query | 权重 | 用途 |
|-------|------|------|
| "订单创建接口 业务规则 鉴权方式" | 0.35 | 结构召回用 |
| "order create api 测试规范 风险点" | 0.30 | 标签召回用 |
| "订单创建 参数校验 必填字段 边界值" | 0.20 | 语义召回用 |
| "订单创建 前置依赖 上下游接口" | 0.15 | 补充召回用 |

### 9.4 不同阶段的 Query 构建参数

**API 分析阶段 → 构建 KnowledgeQuery：**

```
system, module, apiPath, httpMethod, bizEntity, controllerName
```

**Case 生成阶段 → 构建 KnowledgeQuery：**

```
system, module, bizEntity, caseType, riskTags, apiSummary
```

**失败分析阶段 → 构建 KnowledgeQuery：**

```
system, module, apiPath, errorCode, errorMessage, assertionFailureSummary
```

### 9.5 模块

| 组件 | 职责 |
|------|------|
| `QueryRewriteService` | 主入口，协调意图识别 + 关键词补全 + 多 Query 扩展 |
| `IntentRecognizer` | 判断当前阶段 |
| `KeywordCompleter` | 补全接口名、模块名、错误码等关键词 |
| `QueryExpander` | 单 query → 多 query 扩展 |

## 10. ③ 混合检索召回（Hybrid Retrieval）

### 10.1 四路并行召回

```
┌─ 结构过滤 (Structure Filter)
│   PG WHERE: system=? AND module=? AND docType IN (?) AND applicableStages @> ?
│
├─ 关键词召回 (BM25 / Full-Text)
│   PG tsvector + ts_query (jieba 中文分词)
│   或 ES（量级大之后）
│
├─ 向量召回 (Semantic Recall)
│   pgvector <=> query_embedding (bge-m3, cosine distance)
│   topK = 20
│
└─ 权威/新鲜度增强
    authority desc, updatedAt desc
```

### 10.2 阶段化召回 Profile

| Profile | 触发阶段 | 优先召回 docType | 重点过滤字段 |
|---------|---------|-----------------|-------------|
| `API_ANALYSIS_PROFILE` | 接口分析 | business_flow, api_note, env_guide, domain_rule | system, module, apiPath, bizEntity |
| `CASE_GENERATION_PROFILE` | 用例生成 | test_spec, incident_postmortem, domain_rule, api_note | module, bizEntity, riskTags |
| `FAILURE_ANALYSIS_PROFILE` | 失败分析 | error_code_guide, incident_postmortem, env_guide | errorCode, apiPath, system |

### 10.3 候选池大小控制

| 步骤 | 数量 |
|------|------|
| 结构过滤后 | ≤ 100 |
| 关键词召回 | top 20 |
| 向量召回 | top 20 |
| 合并去重 | ≤ 30 |
| Rerank 后 | top 10 |
| 截断后最终注入 | ≤ 8 |

### 10.4 降级策略

```
优先级 1: 结构过滤 + 关键词召回 + 向量召回 (全量)
   ↓ (pgvector 不可用)
优先级 2: 结构过滤 + 关键词召回
   ↓ (全文索引不可用)
优先级 3: 仅结构过滤 (WHERE 条件匹配)
   ↓ (知识库为空)
优先级 4: 仅依赖代码上下文 + 记忆系统
```

**知识库是强增益，但不成为单点阻塞。**

### 10.5 模块

| 组件 | 职责 |
|------|------|
| `KnowledgeRetriever` | 主入口，协调多路召回 |
| `StructureFilter` | 元数据结构过滤 |
| `KeywordRetriever` | BM25 / PG 全文检索 |
| `VectorRetriever` | pgvector cosine 检索 |
| `ProfileSelector` | 根据阶段切换召回 Profile |

## 11. ④ Rerank（重排）

### 11.1 目标

召回结果的原始排序不够准，需要基于多维信号重新排序。

### 11.2 重排公式

**Step 1: RRF (Reciprocal Rank Fusion) 融合 BM25 排名和 Vector 排名**

```
RRF_score(chunk) = Σ 1/(k + rank_i(chunk))
```
其中 k=60，rank_i 取 BM25 排名和 Vector 排名。

**Step 2: 权威/新鲜度/阶段系数调整**

```
finalScore = RRF_score × authority_coef × freshness_coef × stageMatch_coef
```

| 系数 | 计算方式 |
|------|---------|
| `authority_coef` | high=1.2, medium=1.0, low=0.8 |
| `freshness_coef` | 1.0 / (1 + daysSinceUpdate/180)（半年半衰） |
| `stageMatch_coef` | applicableStages 匹配当前阶段=1.2，不匹配=0.6 |

### 11.3 不同阶段可调权重

| Profile | RRF 中 Vector 权重 | 说明 |
|---------|-------------------|------|
| `API_ANALYSIS_PROFILE` | 标准 | 结构匹配更重要 |
| `CASE_GENERATION_PROFILE` | 标准 | 标签和风险匹配更重要 |
| `FAILURE_ANALYSIS_PROFILE` | **提升** | 错误码和语义相似度权重更高 |

### 11.4 模块

| 组件 | 职责 |
|------|------|
| `KnowledgeReranker` | 主入口，计算 finalScore |
| `RRFCalculator` | RRF 融合计算 |
| `AuthorityEvaluator` | 权威度系数计算 |
| `FreshnessEvaluator` | 新鲜度系数计算 |

## 12. ⑤ 截断压缩（Truncation & Compression）

### 12.1 上下文预算控制

不是把 topK 全塞给模型，而是做有策略的截断。

### 12.2 截断规则

| 规则 | 阈值 |
|------|------|
| 最终注入 chunk 总数 | ≤ 8 |
| 每类 docType 最多几条 | ≤ 3 |
| 同一文档最多取几段 | ≤ 2 |
| 相似 chunk 去重 | cosine > 0.85 去重保留 authority 高的 |
| 每 chunk token 数 | ≤ 500 |
| 总注入 token 数 | ≤ 2500 |

### 12.3 压缩策略

- 列表型内容：保留完整列表结构（不截断到半条）
- 表格型内容：保留完整表格
- 段落型内容：优先保留首段和末段（通常含关键信息）
- 代码块：保留完整代码块

### 12.4 模块

| 组件 | 职责 |
|------|------|
| `ContextBudgetController` | 上下文预算控制主入口 |
| `ChunkDeduplicator` | 相似 chunk 去重 |
| `ChunkTruncator` | 按规则截断单 chunk |

## 13. ⑥ 可信生成（Grounded Generation）

### 13.1 目标

生成的上下文结论必须基于检索证据，区分"文档明确说了"和"模型推断"，证据不足时允许说"不确定"。

### 13.2 输出格式

```json
{
  "businessFlowSummary": {
    "content": "订单创建接口位于下单流程的第三步，依赖用户登录和商品校验。创建成功后触发库存扣减和支付流程。",
    "sourceDocIds": ["kb-order-flow", "kb-order-create"],
    "confidence": "high"
  },
  "ruleSummary": {
    "content": "1. 用户必须已登录（token 校验）\n2. amount 字段需在 (0, 99999] 范围内\n3. sign 字段必须放在 body 最后",
    "sourceDocIds": ["kb-order-create", "kb-auth-rule"],
    "confidence": "high"
  },
  "riskSummary": {
    "content": "历史事故：2025-Q3 库存不足时仍创建订单成功，导致超卖。原因：未校验库存前置条件。",
    "sourceDocIds": ["kb-incident-2025q3"],
    "confidence": "high"
  },
  "testHintSummary": {
    "content": "1. 推荐补鉴权失败场景（无 token、过期 token、无角色 token）\n2. 推荐补 amount 边界值（0, 1, 99999, 100000）\n3. 推荐补库存不足前置条件",
    "sourceDocIds": ["kb-order-create", "kb-test-spec"],
    "confidence": "medium"
  },
  "assertionSuggestions": [
    { "assertion": "HTTP 200 + $.code==0", "basedOn": "kb-order-create" },
    { "assertion": "HTTP 401 when no token", "basedOn": "kb-auth-rule" }
  ],
  "referenceSnippets": [
    {
      "docId": "kb-order-create",
      "text": "创建订单时必须先调用 /api/inventory/check 检查库存...",
      "relevance": "high"
    }
  ],
  "uncertainties": [
    "未从文档中找到关于并发下单的幂等性规则说明"
  ]
}
```

### 13.3 可信度标记

| 标记 | 含义 |
|------|------|
| `high` | 有明确文档证据支撑 |
| `medium` | 基于文档推断，非原文直接陈述 |
| `low` | 模型根据通用知识推断，无文档证据 |
| `uncertain` | 明确标注"信息不足，无法确定" |

### 13.4 模块

| 组件 | 职责 |
|------|------|
| `KnowledgeContextBuilder` | 主入口，组装结构化上下文 |
| `EvidenceAnnotator` | 给每条结论附上 sourceDocIds |
| `ConfidenceMarker` | 标记每条结论的可信度 |
| `UncertaintyDetector` | 识别证据不足的领域，输出 uncertainties |

## 14. ⑦ 过程评测（Process Evaluation）

### 14.1 目标

不只评最终答案好坏，要评 RAG 链路本身各环节的质量。

### 14.2 评测指标

| 指标 | 评估内容 | 计算方式 |
|------|---------|---------|
| Query Rewrite Quality | 改写后 query 是否比原始 query 召回更准 | 对比改写前后 Top 5 命中率 |
| Retrieval Recall | 关键证据是否被召回 | 标注集对比（需人工标注种子集） |
| Retrieval Precision | 召回结果中相关比例 | 相关 chunk / 总召回 chunk |
| Rerank Gain | 重排后 Top 3 是否优于原始 Top 3 | 对比 Rerank 前后 MRR |
| Context Usefulness | 注入上下文是否被 AI 实际引用 | AI 输出中 sourceDocIds 覆盖的 chunk 比例 |
| Groundedness | 生成结论是否有检索依据 | 有 sourceDocId 的结论 / 总结论 |
| Latency | 各环节耗时 | 分环节计时 |

### 14.3 第一版评测策略

第一版不强依赖人工标注，先做自动评测：

| 自动可评 | 方式 |
|---------|------|
| Rerank Gain | 对比 Rerank 前后 MRR |
| Context Usefulness | 统计 sourceDocIds 覆盖的 chunk |
| Groundedness | 统计有 source 标记的结论比例 |
| Latency | 分环节打点计时 |

| 需人工标注（V1 视资源决定做不做） | 方式 |
|--------------------------------|------|
| Retrieval Recall | 标注 20 条种子 query 的理想召回集 |
| Retrieval Precision | 标注召回结果的相关性 |

### 14.5 模块

| 组件 | 职责 |
|------|------|
| `RAGEvaluator` | 主入口，协调各类评测 |
| `RetrievalEvaluator` | Recall / Precision 评估 |
| `RerankEvaluator` | Rerank Gain (MRR) 评估 |
| `GroundednessEvaluator` | 生成结论的引用覆盖率评估 |
| `LatencyTracker` | 各环节耗时跟踪 |

## 15. ⑧ 反馈闭环（Feedback Loop）

### 15.1 目标

让 RAG 链路随使用不断优化，越用越准。

### 15.2 反馈来源与动作

| 反馈信号 | 来源 | 闭环动作 |
|---------|------|---------|
| 用户采纳生成的 case/断言 | 前端交互（采纳/修改/拒绝按钮） | 命中文档 authority +0.05 |
| 某 chunk 被高频引用 | 可信生成中的 sourceDocIds 统计 | chunk importance 提升 |
| 某文档长期无命中 | KnowledgeRetriever 命中日志 | 标记 `stale`，降低 freshness |
| AI 生成的断言在真实执行中通过 | ExecutionRecord.result = PASSED | 对应 chunk successContribution +0.1 |
| AI 生成的断言在真实执行中失败 | ExecutionRecord.result = FAILED | 对应 chunk successContribution -0.05 |
| 错误分析被后续事实证实 | Observation 被人工标记 correct | → 记忆系统 `failure_pattern` |
| 错误分析被后续事实推翻 | Observation 被人工标记 incorrect | → 降权，标记待复核 |

### 15.3 与记忆系统的联动

```
反馈闭环
  ├─ 调整知识库 chunk authority / freshness / importance
  ├─ 高频有效引用 → 记忆系统 failure_pattern / testing_pattern
  └─ 误判经验 → 记忆系统标记 confidence=low，等待更多证据
```

### 15.4 模块

| 组件 | 职责 |
|------|------|
| `FeedbackCollector` | 收集各类反馈信号 |
| `AuthorityAdjuster` | 调整文档/chunk 的 authority 权重 |
| `StaleDetector` | 检测长期无命中文档 |
| `MemoryFeedbackBridge` | 将反馈结果写入记忆系统 |

---

## 第三部分：Unified Context Builder

## 16. 统一上下文融合

### 16.1 多源融合

```
CodeContext (ApiSpec)
  + KnowledgeContext (知识库 RAG 输出)
  + MemoryContext (记忆系统召回)
  + CurrentTaskContext (当前任务状态)
  → Unified Context Builder
  → UnifiedApiContext
```

### 16.2 UnifiedApiContext 结构

```java
public class UnifiedApiContext {
    // 代码分析结果
    ApiSpec apiSpec;                          // 接口结构定义
    CodeContext codeContext;                  // DTO 展开、约束、鉴权
    
    // 知识库增强结果
    KnowledgeContext knowledgeContext;         // 业务流程、规则、风险、测试提示
    
    // 记忆增强结果
    MemoryContext memoryContext;               // 历史经验、失败模式、偏好
    
    // 测试洞察
    TestInsight testInsight;                   // 推荐测试点、断言建议、风险优先级
    
    // 溯源引用
    List<SourceRef> sourceRefs;               // 每条结论的来源追踪
}
```

### 16.3 与其他模块的关系

| 下游模块 | 消费 UnifiedApiContext 的哪些字段 |
|---------|--------------------------------|
| 测试用例生成 | apiSpec + knowledgeContext + memoryContext + testInsight |
| 规则系统 | apiSpec（主要消费 FieldConstraint） |
| 断言系统 | testInsight.assertionSuggestions |
| 执行引擎 | apiSpec.auth + knowledgeContext.envGuide |
| 编排系统 | testInsight.riskPriority（决定执行策略） |

## 17. 第一版落地范围

### 包含

**代码分析：**
- Spring Boot FrameworkDetector（多信号打分）
- RouteExtractor（路径、Method、参数来源）
- ModelExtractor（DTO 递归展开，最大深度 3）
- ValidationExtractor（所有常见校验注解）
- AuthExtractor（@PreAuthorize、@Secured）
- 同步首屏 + 异步增强执行模式

**知识库 RAG：**
- ① 知识工程：7 类文档导入 + 语义分块 + 元数据 + 3 类索引
- ② Query 改写：意图识别 + 关键词补全 + 多 Query 扩展
- ③ 混合检索：结构过滤 + BM25 + pgvector + 权威/新鲜度（4 路并行）
- ④ Rerank：RRF + authority × freshness × stageMatch
- ⑤ 截断压缩：≤8 chunk、≤3/类、≤2/文档、去重
- ⑥ 可信生成：带 sourceDocIds + confidence 标记的结构化输出
- ⑦ 过程评测：自动评测（Rerank Gain、Context Usefulness、Groundedness、Latency）
- ⑧ 反馈闭环：authority 调整 + stale 检测 + 记忆系统联动

**统一上下文：**
- UnifiedApiContext 完整结构
- 3 套阶段 Profile（API_ANALYSIS / CASE_GENERATION / FAILURE_ANALYSIS）
- 知识库降级策略（3 级降级，不阻塞主流程）

### 暂不包含

- 多语言/多框架支持（V1 仅 Spring Boot）
- Service 调用链分析（V2 + codegraph）
- 复杂 Query Decomposition（多轮拆解子问题）
- 图谱/关系召回
- 人工标注的 Retrieval Recall 评测
- 可视化知识编辑器
- 多知识源实时同步

## 18. Java 包结构建议

```
com.xxx.agent.analysis
com.xxx.agent.analysis.code
com.xxx.agent.analysis.code.detect       // FrameworkDetector
com.xxx.agent.analysis.code.route        // RouteExtractor
com.xxx.agent.analysis.code.model        // ModelExtractor
com.xxx.agent.analysis.code.validation   // ValidationExtractor
com.xxx.agent.analysis.code.auth         // AuthExtractor
com.xxx.agent.analysis.code.service      // ServiceContextAnalyzer (V2)
com.xxx.agent.analysis.code.model        // 代码分析对象模型

com.xxx.agent.knowledge
com.xxx.agent.knowledge.ingest           // KnowledgeIngestService
com.xxx.agent.knowledge.chunk            // KnowledgeChunker
com.xxx.agent.knowledge.metadata         // KnowledgeMetadataExtractor
com.xxx.agent.knowledge.query             // QueryRewriteService, IntentRecognizer, QueryExpander
com.xxx.agent.knowledge.retrieve         // KnowledgeRetriever, StructureFilter, KeywordRetriever, VectorRetriever
com.xxx.agent.knowledge.rerank           // KnowledgeReranker, RRFCalculator
com.xxx.agent.knowledge.truncate         // ContextBudgetController, ChunkDeduplicator
com.xxx.agent.knowledge.generate         // KnowledgeContextBuilder, EvidenceAnnotator
com.xxx.agent.knowledge.evaluate         // RAGEvaluator, RetrievalEvaluator, GroundednessEvaluator
com.xxx.agent.knowledge.feedback         // FeedbackCollector, AuthorityAdjuster, StaleDetector
com.xxx.agent.knowledge.model            // 知识库对象模型
com.xxx.agent.knowledge.repository       // 数据访问层

com.xxx.agent.context
com.xxx.agent.context.builder            // UnifiedContextBuilder
com.xxx.agent.context.model              // UnifiedApiContext, CodeContext, KnowledgeContext, MemoryContext
com.xxx.agent.context.profile            // API_ANALYSIS_PROFILE, CASE_GENERATION_PROFILE, FAILURE_ANALYSIS_PROFILE
```

## 19. 一句话总结

接口自动分析与知识库融合模块的核心是双线互补：

- **代码分析线**：从源码中自动提取接口结构（Controller + DTO），做到底层覆盖
- **知识库 RAG 线**：通过 8 段检索增强链路（知识工程→Query改写→混合检索→Rerank→截断→可信生成→评测→反馈闭环），为接口补充业务语义

最终通过 Unified Context Builder 融合，输出 `UnifiedApiContext`，让测试 Agent 从"知道接口长什么样"升级为"理解接口该怎么测"。
