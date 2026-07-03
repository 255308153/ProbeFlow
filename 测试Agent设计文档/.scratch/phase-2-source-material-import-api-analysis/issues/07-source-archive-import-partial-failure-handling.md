Status: ready-for-agent

# Source archive 导入与部分成功/失败处理

## Parent

`../PRD.md`

## What to build

Support source archives as SourceMaterial and make Spring Boot analysis resilient to partial failures. A user should be able to submit an archive containing source files, have the system unpack it safely, analyze valid controllers, and report warnings or errors for invalid files without losing all valid ApiSpec output.

This slice hardens the source analysis path for realistic user input.

## Acceptance criteria

- [ ] Source archive material can be submitted through the analysis entrypoint.
- [ ] Archives are unpacked to a controlled temporary or workspace location with basic path traversal protection.
- [ ] Valid Spring Boot controllers inside an archive are analyzed into ApiSpec records.
- [ ] Invalid or unreadable files produce warnings or errors visible through SourceMaterial, Task, or PlanStep status details.
- [ ] One invalid controller does not discard valid ApiSpec records from other controllers when partial success is possible.
- [ ] Fully invalid archives fail clearly without creating garbage ApiSpec records.
- [ ] Tests cover a successful archive import, partial success, and full failure.
- [ ] The slice does not introduce distributed job orchestration or frontend upload UI.

## Blocked by

- `01-source-material-analysis-entrypoint-task-planstep.md`
- `05-spring-controller-route-analysis-to-apispec.md`
