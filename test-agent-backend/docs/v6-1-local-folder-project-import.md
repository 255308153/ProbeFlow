# V6-1 本地文件夹项目导入

V6-1 把服务端可访问的本地 Spring 项目目录导入为 ProbeFlow 输入物料，并接入现有接口分析主链路：

```text
本地项目目录（服务端绝对路径）
-> 路径校验 + 允许根目录白名单
-> SourceMaterial（SOURCE_DIRECTORY）
-> Task + ApiAnalysis
-> ApiSpec 接口资产
-> 详情查询 / 接口列表 / 重新分析
```

第一版只提供 HTTP 接口和本手动验证说明，不实现完整前端控制台。

## 关键前提

本地路径是 **ProbeFlow 服务端机器路径**，不是浏览器所在电脑路径，也不是 curl 客户端机器路径。响应只返回接口元数据与源码位置，**不返回源码正文**。

默认验证与 CI **不依赖**：

- 真实 LLM / DeepSeek 密钥
- 外部网络 / GitHub
- 真实被测服务启动
- 数据库、中间件、环境变量、鉴权配置向导

自动化测试使用 `test` profile + H2 + fake LLM。内部试用闸门另见文末。

## 1. 启动基础设施

```bash
cd test-agent-backend
docker compose up -d
```

默认期望：

- PostgreSQL：`jdbc:postgresql://localhost:5432/probeflow_test_agent`
- Redis：`localhost:6379`
- 应用端口：`8080`

## 2. 配置允许读取的根目录

未配置白名单时，导入会被拒绝。请把真实 Spring 项目所在父目录加入允许根目录：

```bash
export PROBEFLOW_PROJECT_IMPORT_LOCAL_DIRECTORY_ALLOWED_ROOTS="/Users/lqc/work,/Users/lqc/Downloads"
```

或在 `application.yml` 中：

```yaml
probeflow:
  project-import:
    local-directory:
      allowed-roots: ${PROBEFLOW_PROJECT_IMPORT_LOCAL_DIRECTORY_ALLOWED_ROOTS:}
```

多个根目录用逗号分隔。导入前会规范化为真实路径（`toRealPath`），相对路径、软链绕出白名单、`..` 越界都会被拒绝。

## 3. 启动应用

```bash
cd test-agent-backend
mvn spring-boot:run
```

## 4. 导入真实本地 Spring 项目

将 `storagePath` 换成服务端上真实存在、可读、且位于白名单内的绝对路径：

```bash
curl -s http://localhost:8080/api/project-imports/local-directory \
  -H 'Content-Type: application/json' \
  -d '{
    "projectName": "order-service",
    "storagePath": "/Users/lqc/work/order-service",
    "requestedBy": "lqc"
  }'
```

成功响应示例字段：

| 字段 | 含义 |
| --- | --- |
| `materialId` | 输入物料编号，后续查询/重分析使用 |
| `taskId` | 接口分析任务编号 |
| `status` | `READY` 或 `FAILED` |
| `materialType` | 固定为 `SOURCE_DIRECTORY` |
| `apiSpecCount` | 识别到的接口数量 |
| `warnings` | 非致命问题（如部分文件解析失败） |
| `blockers` | 当前无法完成分析的阻塞原因 |

## 5. 查询导入详情

```bash
curl -s http://localhost:8080/api/project-imports/{materialId}
```

关注：`status`、`taskId`、`apiSpecCount`、`lastAnalyzedAt`、`warnings`、`blockers`、`storagePath`。

## 6. 查询接口资产列表

```bash
curl -s http://localhost:8080/api/project-imports/{materialId}/api-specs
```

每条接口包含方法、路径、模块、摘要、源码位置、就绪标志，以及 `presentInLatestAnalysis`。列表按路径 + HTTP 方法稳定排序。响应中的 `sourceLocation` **不含源码正文**。

## 7. 修改源码后重新分析

在服务端项目目录中修改或删除 Controller 后：

```bash
curl -s -X POST http://localhost:8080/api/project-imports/{materialId}/reanalyze
```

预期：

- 相同接口不重复创建
- 变化接口更新版本
- 源码中删除的接口仍保留，但 `presentInLatestAnalysis=false`
- 重分析前会再次校验真实路径、目录可读性和允许根目录白名单
- 成功时仍可能带回 `warnings`

## 8. 失败与阻塞场景命令示例

### 相对路径

```bash
curl -s http://localhost:8080/api/project-imports/local-directory \
  -H 'Content-Type: application/json' \
  -d '{
    "projectName": "bad-relative",
    "storagePath": "order-service",
    "requestedBy": "lqc"
  }'
```

期望：`400`，`code=LOCAL_DIRECTORY_PATH_NOT_ABSOLUTE`，不创建输入物料/任务/接口资产。

### 白名单外路径

```bash
curl -s http://localhost:8080/api/project-imports/local-directory \
  -H 'Content-Type: application/json' \
  -d '{
    "projectName": "outside",
    "storagePath": "/tmp/not-allowed/project",
    "requestedBy": "lqc"
  }'
```

期望：`400`，`code=LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS`。

### 不存在路径

```bash
curl -s http://localhost:8080/api/project-imports/local-directory \
  -H 'Content-Type: application/json' \
  -d '{
    "projectName": "missing",
    "storagePath": "/Users/lqc/work/does-not-exist",
    "requestedBy": "lqc"
  }'
```

期望：`400`，`code=LOCAL_DIRECTORY_PATH_NOT_FOUND`。

### 空项目 / 无可分析 Java 源码

准备一个位于白名单内、但没有 Java 源码的空目录后导入。期望：`status=FAILED`，blocker `code=NO_APIS_FOUND`。

### 无 Controller / 无 HTTP 路由

有 Java 文件但没有 Spring `@RestController` 与请求映射。期望：`status=FAILED`，blocker `code=NO_HTTP_APIS_FOUND`。

### 多模块歧义

同一项目根下存在多个 `src/main/java` 时：

期望：`status=FAILED`，blocker `code=MULTI_MODULE_SOURCE_ROOTS_AMBIGUOUS`，并给出“导入单一模块目录或配置明确源码模块”的建议。第一版不会静默合并多模块。

## 9. 如何理解 status / warnings / blockers

- `READY`：分析完成并生成了接口资产；`warnings` 可为空或包含部分文件解析失败等信息。
- `FAILED`：未能形成可用接口资产；看 `blockers[].code`、`summary`、`message`、`suggestedAction`。
- 路径类错误在分析前返回 HTTP `400`，不会创建输入物料。
- 扫描会忽略隐藏目录（如 `.git`）、构建产物（`target`/`build`/`out`）和依赖缓存（`node_modules`/`.gradle` 等）。忽略目录中的假 Controller 不会进入接口资产。

## 10. 历史接口为何不物理删除

重新分析时，源码中已消失的接口仍保留在 `ApiSpec` 中，并标记 `presentInLatestAnalysis=false`。原因是已有测试用例、执行记录、报告和记忆可能引用该接口资产编号。历史接口只表达“不在最新源码分析中”，不是删除。

## 11. V6-1 明确不做的事

- 不实现 Git 地址克隆导入
- 不实现压缩包上传页面
- 不实现 OpenAPI / Swagger 文件上传页面
- 不实现完整前端项目导入控制台
- 不自动启动被测服务，也不自动探测 HTTP readiness
- 不自动配置数据库、中间件、环境变量或鉴权
- 不实现多人权限系统
- 不引入第二套项目导入或测试资产体系；继续复用 `SourceMaterial`、`Task`、`ApiAnalysis`、`ApiSpec`、`ExecutionRecord`、`Observation`、`Report`
- 不包含 V6-2 Schemathesis / OpenAPI 契约测试

## 12. 默认自动化测试与内部试用闸门

默认自动化测试：

```bash
cd test-agent-backend
mvn test
```

默认路径只使用 fake / offline 能力。假模型可以保证本地与 CI 稳定，**不能作为内部试用的唯一验收依据**。

内部试用进入条件（三者缺一不可）：

1. **V5 完成**：记忆、检索、Rerank / Small-to-Big 等 RAG / Memory / Context 闭环已收口
2. **V6-1 完成**：本阶段本地文件夹项目导入可用（导入、详情、接口列表、重分析、诊断、扫描忽略）
3. **DeepSeek V4 Pro 真实大模型手动验证通过**：必须用真实模型跑通内部试用关键智能链路；该项是独立闸门，不由默认 `mvn test` 代替

## 13. 最小使用入口

当前最小入口就是下列 HTTP 接口：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/project-imports/local-directory` | 导入本地目录 |
| `GET` | `/api/project-imports/{materialId}` | 查询导入详情 |
| `GET` | `/api/project-imports/{materialId}/api-specs` | 查询接口资产 |
| `POST` | `/api/project-imports/{materialId}/reanalyze` | 重新分析 |

本阶段不开发完整前端控制台。HTTP 接口与上述命令示例足够完成手动验收。
