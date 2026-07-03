Status: ready-for-agent

# KnowledgeContext assembly and ApiSpec readiness update

## Parent

`../PRD.md`

## What to build

Assemble ranked retrieval results into a structured KnowledgeContext that downstream case generation and failure analysis can consume. The context should group evidence into business rules, API notes, test specs, error code guides, environment notes, incident hints, and cited chunks. When retrieval is run for an ApiSpec, the system should update knowledgeContextReady only when useful grounded context exists.

This slice turns retrieval results into the stable RAG output contract for later phases.

## Acceptance criteria

- [ ] Retrieval can produce a KnowledgeContext grouped into businessRules, apiNotes, testSpecs, errorCodeGuides, environmentNotes, incidentHints, and citedChunks.
- [ ] Context entries include chunkId, documentId, documentRevisionId, title or chunk title, score, evidence type, source reference, and relevant metadata where available.
- [ ] Low-confidence or low-coverage context is explicitly represented rather than hidden.
- [ ] Empty or no-match retrieval produces an empty KnowledgeContext and does not throw.
- [ ] ApiSpec knowledgeContextReady is set true only when useful context is found for that ApiSpec.
- [ ] ApiSpec knowledgeContextReady remains false when retrieval runs but no relevant context is found.
- [ ] Tests verify grouping, citations, confidence/coverage flags, empty behavior, and ApiSpec readiness behavior through the retrieval service seam.
- [ ] The slice does not implement Memory Refinery, Unified Context Builder memory merge, TestCase generation, HTTP execution, report generation, or frontend behavior.

## Blocked by

- `06-hybrid-scoring-basic-rerank.md`
