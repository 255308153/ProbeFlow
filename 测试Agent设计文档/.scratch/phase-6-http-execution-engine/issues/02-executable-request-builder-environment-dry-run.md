Status: ready-for-agent

# Executable request builder with environment variables and dry-run

## Parent

`../PRD.md`

## What to build

Turn generated TestCase detail and ApiSpec fallback data into a validated executable HTTP request. The execution service should resolve base URL, path, headers, query params, body, auth placeholders, and environment variables before transport. It should also support dry-run mode that returns the prepared request snapshot without sending any HTTP call.

This slice makes execution safe and inspectable before real transport behavior expands.

## Acceptance criteria

- [ ] Request building uses TestCase detail and steps as the primary source, with ApiSpec used as fallback for method, path, auth, and parameter structure.
- [ ] Environment values can resolve base URL and request placeholders.
- [ ] Auth placeholders can be resolved from explicit auth/environment inputs.
- [ ] Missing required variables block execution before transport with a clear blocked result.
- [ ] Unsupported or non-HTTP/HTTPS protocols are rejected before transport.
- [ ] Request snapshots redact secrets before persistence or result exposure.
- [ ] Dry-run mode builds and returns request snapshots without calling the HTTP client boundary.
- [ ] Tests verify request building, ApiSpec fallback, variable resolution, auth resolution, unresolved variable blocking, protocol validation, secret redaction, and dry-run no-transport behavior.
- [ ] The slice does not implement response snapshot enrichment, baseline assertions, batch execution, SUITE execution, reports, frontend, browser automation, service direct invocation, DB direct assertions, or real LLM calls.

## Blocked by

- `01-http-execution-entrypoint-fake-client-single-execution.md`
