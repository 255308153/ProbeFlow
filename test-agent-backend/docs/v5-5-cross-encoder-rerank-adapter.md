# V5-5 Cross Encoder Rerank Adapter

V5-5 Issue 03 adds a Cross Encoder rerank adapter contract as an extension point. It is not a default runtime dependency and it does not add a concrete external model client.

The adapter consumes the V5-5 `RerankCandidate` list that already came from V5-4 multi-route retrieval. It does not recall knowledge, memory, graph data, or any other source again. The provider request contains only:

- the task query;
- stable candidate id;
- candidate text;
- safe diagnostic metadata such as corpus type, source type, before rank, fused score, matched route names, and query variant ids.

Candidate metadata that may contain API keys, Authorization headers, tokens, raw request bodies, or other sensitive request content is not forwarded to the provider.

Provider responses must return known candidate ids with model scores and ranks. Unknown candidate ids, missing scores, invalid responses, partial responses, timeout, and remote errors are classified and fall back to the deterministic rerank baseline.

Default configuration keeps the extension disabled:

```yaml
probeflow:
  rerank:
    cross-encoder:
      enabled: false
      provider: disabled
```

Automated tests use fake providers and local stub responses only. A future real Cross Encoder profile must be explicitly enabled and must keep deterministic fallback and redacted diagnostics.
