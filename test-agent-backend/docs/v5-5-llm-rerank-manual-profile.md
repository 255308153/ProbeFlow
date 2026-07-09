# V5-5 LLM Rerank Manual Profile

V5-5 Issue 04 adds an explicit LLM rerank profile for manual internal validation. It is disabled by default, consumes only the existing V5-5 `RerankCandidate` list, and falls back to deterministic rerank whenever the model output is unsafe or invalid.

The profile does not recall knowledge, memory, graph data, or any other source again. The prompt contains safe candidate summaries only:

- candidate id
- corpus type
- title and bounded summary text
- source type
- before rank and fused score
- matched routes
- query variants
- allowed evidence refs copied from those routes and variants

The prompt requires structured JSON with `candidateId`, `rank`, `reason`, `confidence`, and optional `evidenceRefs`. The service rejects invalid JSON, partial output, duplicate ids, unknown candidate ids, missing fields, out-of-range confidence, and evidence refs that were not present in the input candidate. Every rejected result uses the deterministic rerank baseline and a redacted diagnostic.

Default configuration keeps the real LLM path off:

```yaml
probeflow:
  rerank:
    llm:
      enabled: false
      provider: disabled
      model: deepseek-v4-pro
```

Automated tests use fake LLM or stub responses only. They must run without a DeepSeek key, real LLM, external network, Cross Encoder service, or external graph database.

For internal alpha validation, fake LLM is only a CI/no-key fallback and is not sufficient as the only acceptance path. A manual acceptance run should enable the profile explicitly, route it through the existing manual real LLM provider policy, use DeepSeek V4 Pro, and record the candidate fixture, model response summary, fallback status, and final rerank order.

Example manual environment:

```bash
PROBEFLOW_RERANK_LLM_ENABLED=true
PROBEFLOW_RERANK_LLM_PROVIDER=manual-real
PROBEFLOW_RERANK_LLM_MODEL=deepseek-v4-pro
PROBEFLOW_LLM_ALLOW_REAL_PROVIDERS=true
PROBEFLOW_LLM_MANUAL_REAL_ENABLED=true
PROBEFLOW_LLM_MANUAL_REAL_MODEL=deepseek-v4-pro
```

The manual path still uses the same validation rules: the model may only rank known candidate ids and may only cite evidence refs already present in the prompt.
