# ProbeFlow Test Agent Backend

Spring Boot backend foundation for the ProbeFlow HTTP API Test Agent.

## Requirements

- Java 21
- Maven 3.9+
- Docker Compose

## Local Infrastructure

Start PostgreSQL with pgvector and Redis:

```bash
docker compose up -d
```

The default application settings expect:

- PostgreSQL: `jdbc:postgresql://localhost:5432/probeflow_test_agent`
- Redis: `localhost:6379`

## Run

```bash
mvn spring-boot:run
```

Flyway runs on application startup. The default profile applies common migrations and the PostgreSQL pgvector extension migration.

## Test

```bash
mvn test
```

Automated startup tests use the `test` profile with an in-memory H2 database, so they do not require locally running containers.

## V3-1 Manual Suite Agent Harness

V3-1 提供一个后端本地演示入口，用固定 fixture、deterministic fake provider 和 fake HTTP gateway 运行 Manual Suite Agent Harness。默认不会调用真实 LLM、真实 embedding 或真实外部 HTTP。

默认运行订单链路 demo：

```bash
mvn -q -DskipTests exec:java
```

指定 fixture 和输出目录：

```bash
mvn -q -DskipTests exec:java -Dexec.args="--fixture-id=order-suite-demo --output-dir=target/v3-manual-suite-agent"
```

可用 fixture：

- `order-suite-demo`：订单创建、支付、查询三步 fake HTTP 链路。
- `v3-smoke`：最小 smoke fixture。

运行完成后，命令会打印：

- `jsonReport=...`
- `markdownReport=...`
- `usesRealLlm=false`
- `usesExternalHttp=false`

Manual real LLM 模式必须显式传入 `--provider-mode=MANUAL_REAL_LLM --allow-manual-real-llm`，并且仍受 harness provider policy 约束；默认 demo 和 CI 不依赖真实 LLM。常见失败诊断包括 `FIXTURE_NOT_FOUND`、`FIXTURE_INVALID`、`INVALID_PROVIDER_MODE`、`PROVIDER_BLOCKED` 和 `REPORT_WRITE_FAILED`。
