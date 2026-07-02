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
