Status: ready-for-agent

# Context conflict detection and token budget pruning

## Parent

`../PRD.md`

## What to build

Extend Unified Context Builder with deterministic conflict detection and token budget pruning. The builder should preserve source boundaries, mark contradictions between Knowledge RAG and Memory, and trim context by priority when a budget is exceeded.

This slice keeps ContextBundle trustworthy and compact before downstream generators consume it.

## Acceptance criteria

- [ ] ContextBundle marks conflicts when Knowledge and Memory provide contradictory hints for the same API, tag, error code, environment, requirement, or status where deterministic rules can detect it.
- [ ] Conflict entries preserve both sides with source identifiers, confidence, score, and source type.
- [ ] Current Task Memory is prioritized over stale Long-term Memory.
- [ ] High-authority Knowledge is prioritized over low-confidence Memory.
- [ ] High-confidence/high-contribution failure patterns can remain as risk hints even when not authoritative.
- [ ] Token budget pruning is deterministic and uses estimated token counts if no tokenizer exists.
- [ ] Pruning priority keeps current task facts and ApiSpec/code context first, then high-authority Knowledge, then high-confidence/high-contribution Memory.
- [ ] Tests verify conflict detection, source preservation, priority rules, budget pruning, and deterministic output through the builder seam.
- [ ] The slice does not implement TestCase generation, HTTP execution, report rendering, frontend behavior, LLM conflict resolution, or external providers.

## Blocked by

- `06-unified-context-builder-baseline-bundle.md`
