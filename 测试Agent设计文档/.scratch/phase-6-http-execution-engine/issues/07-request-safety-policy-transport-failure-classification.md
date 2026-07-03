Status: ready-for-agent

# Request safety policy and transport failure classification

## Parent

`../PRD.md`

## What to build

Strengthen execution safety and failure classification. The executor should block unsafe targets, reject invalid URLs, enforce HTTP/HTTPS only, make redirect and retry behavior explicit, and classify timeout, network error, invalid request, blocked host, unsupported protocol, and assertion failure consistently.

This slice is important before handing execution work to multiple collaborators because it prevents accidental unsafe traffic and makes failures legible.

## Acceptance criteria

- [ ] Unsafe or disallowed hosts can be blocked by execution policy before transport.
- [ ] Invalid URLs are rejected before transport with a blocked or invalid-request result.
- [ ] Non-HTTP/HTTPS protocols are rejected before transport.
- [ ] Redirect behavior is explicit in request options/result metadata.
- [ ] Retry behavior is explicit and conservative; retries are not silently performed without configuration.
- [ ] Timeout, network error, blocked host, unsupported protocol, invalid request, and assertion failure have distinguishable result classifications.
- [ ] ExecutionRecord and execution result preserve classification and human-readable error messages.
- [ ] Tests verify blocked hosts, invalid URLs, non-HTTP protocols, redirect policy, retry policy metadata, timeout classification, network error classification, and assertion-failure distinction.
- [ ] The slice does not implement distributed execution, frontend, reports, browser/UI automation, service direct invocation, DB direct assertions, or real LLM calls.

## Blocked by

- `03-response-capture-execution-snapshot-persistence.md`
