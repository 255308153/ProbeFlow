Status: ready-for-agent

# Issue 07: Memory feedback and learning summary in reports

## Parent

Phase 8: Task Report Generation PRD

## What to build

Include memory and learning feedback in generated reports where Phase 7 and the memory system make it observable. The report should summarize what the system learned from the run, how many long-term memory candidates were accepted, and any useful rejected or noisy candidate notes. These entries should remain traceable through structured references rather than copied raw memory payloads.

This slice should make report generation a bridge between failure analysis and future test planning without introducing an Agent Loop.

## Acceptance criteria

- [ ] Reports include memory feedback summary where existing Task Memory or memory candidate data is observable.
- [ ] Reports include accepted long-term memory candidate counts where available.
- [ ] Reports include rejected or noisy candidate notes when useful and available.
- [ ] Memory and knowledge references are structured and traceable.
- [ ] Report generation avoids copying large raw memory or knowledge snapshots into Report fields.
- [ ] The feature remains deterministic and local.
- [ ] Tests cover memory feedback summaries using existing memory/refinery artifacts.

## Blocked by

- Issue 05: Failure analysis reuse and missing BASIC analysis handoff
