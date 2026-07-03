Status: ready-for-agent

# Spring Boot DTO、Validation、Auth 基础提取

## Parent

`../PRD.md`

## What to build

Enrich the Spring Boot source analysis path with request/response DTO structure, validation constraints, and auth hints. Given a Spring Boot fixture with DTO classes and annotations, the analysis pipeline should enhance ApiSpec records with structured request/response body details, validation hints, and authentication/security hints.

This slice builds on the route extraction path and keeps the output deterministic without LLM-based code understanding.

## Acceptance criteria

- [ ] Request DTO class names and basic fields are extracted for common controller method signatures.
- [ ] Response DTO class names and basic fields are extracted where practical from method return types.
- [ ] Common validation annotations such as `@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Max`, and `@Pattern` are converted into validation hints.
- [ ] Auth annotations such as `@PreAuthorize` or security-related annotations are converted into auth hints.
- [ ] ApiSpec request body, response body, validation hints, and auth hints are persisted in schema-supported structured fields.
- [ ] dtoExpanded, validationReady, and authReady are set appropriately; knowledgeContextReady remains false.
- [ ] Tests verify the enriched ApiSpec output through the application service seam using Spring Boot fixtures.
- [ ] The slice does not implement TestCase generation or assertion suggestion.

## Blocked by

- `05-spring-controller-route-analysis-to-apispec.md`
