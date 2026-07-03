Status: ready-for-agent

# ApiSpec upsert、去重、版本更新与重复导入幂等

## Parent

`../PRD.md`

## What to build

Make ApiSpec import deterministic. Re-importing the same SourceMaterial should not create duplicate ApiSpec records, while changed operations should update the existing ApiSpec and preserve version/update metadata for future stale TestCase detection.

This slice should work through the same OpenAPI analysis entrypoint from the prior slice, not through direct repository-only calls.

## Acceptance criteria

- [ ] ApiSpec identity is deterministic for a source scope plus normalized method and path, with operationId used as an additional matching hint when available.
- [ ] Importing the same OpenAPI fixture twice does not create duplicate ApiSpec records.
- [ ] Changing an operation updates the existing ApiSpec instead of creating a duplicate.
- [ ] Version, updated timestamp, or equivalent change metadata is updated when an ApiSpec changes.
- [ ] Unchanged ApiSpec records remain stable across repeated imports.
- [ ] Removed routes are detected as absent from the latest SourceMaterial and represented with the safest currently supported metadata or status behavior.
- [ ] Tests cover idempotent re-import and changed-operation update behavior through the application service seam.
- [ ] The slice does not generate or update TestCase assets.

## Blocked by

- `02-openapi-import-to-apispec.md`
