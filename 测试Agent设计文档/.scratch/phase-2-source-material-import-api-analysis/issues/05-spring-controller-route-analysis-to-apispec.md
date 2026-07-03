Status: ready-for-agent

# Spring Boot Controller 路由分析最小端到端链路

## Parent

`../PRD.md`

## What to build

Build the Spring Boot source analysis path through the Phase 2 analysis entrypoint. Given a fixture source directory containing Spring Boot controllers, the system should discover controller routes and persist ApiSpec records with method, normalized path, source metadata, request parameter basics, and readiness flags.

This slice focuses on route and basic parameter extraction. Deep DTO, validation, and auth enrichment belong to the next slice.

## Acceptance criteria

- [ ] A Spring Boot source directory SourceMaterial can be submitted through the analysis entrypoint.
- [ ] `@RestController` classes are discovered.
- [ ] Class-level and method-level mappings are combined into normalized final paths.
- [ ] `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, and `@PatchMapping` are handled for common cases.
- [ ] `@RequestParam`, `@PathVariable`, `@RequestHeader`, and `@RequestBody` are represented at a basic level.
- [ ] Each discovered handler method creates or updates one ApiSpec.
- [ ] Source metadata such as file path, class name, method name, and line number is preserved where practical.
- [ ] routeReady and basicParamReady are set appropriately; knowledgeContextReady remains false.
- [ ] Tests use a Spring Boot fixture and verify persisted ApiSpec and Task/PlanStep state through the application service seam.
- [ ] The slice does not rely entirely on brittle regex for route extraction when a structured Java parser is practical.

## Blocked by

- `01-source-material-analysis-entrypoint-task-planstep.md`
