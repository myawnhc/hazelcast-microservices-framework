# Phase 2 Days 18-19: Blog Posts & Demo Scenarios (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original sessions.

## Context

Days 18-19 covered Week 4's documentation and demo work. Day 18 focused on drafting three blog posts (posts 04-06) covering observability, saga patterns, and vector similarity search. Day 19 focused on creating interactive demo scenarios for saga failure and timeout, updating the demo walkthrough guide, and creating a video walkthrough outline.

These days correspond to two commits:
- Day 18: `d5f7b96` — blog posts 04, 05, 06 and series numbering update
- Day 19: `b41b1e2` — saga failure/timeout demo scenarios, walkthrough updates, video outline

## What Was Built

### Day 18: Blog Posts
- **Blog post 04**: Observability in Event-Sourced Systems — pipeline and saga metrics instrumentation, Prometheus scrape config, Grafana dashboards, pre-configured alerts, Jaeger tracing, and useful PromQL queries
- **Blog post 05**: The Saga Pattern for Distributed Transactions — choreography vs orchestration, Order Fulfillment flow, compensation, timeout detection, saga state tracking, and how to add new sagas
- **Blog post 06**: Vector Similarity Search with Hazelcast — embeddings, cosine similarity, IMap-based brute-force search, Community/Enterprise fallback pattern, and the edition-aware bean selection design
- Updated posts 01-03 series subtitle from "Part X of 3" to "Part X of 6"
- Expanded post 01 "What's Next" section to list all 6 parts
- Added "Next" navigation link to post 03

### Day 19: Demo Scenarios
- **Scenario 5** (payment failure compensation): order > $10K triggers payment decline, automatic stock release and order cancellation via compensation flow
- **Scenario 6** (saga timeout recovery): stop payment service, saga stalls, timeout detector triggers compensation after 60s, service restarted afterward
- Updated demo walkthrough guide with saga architecture diagrams, payment service endpoints, new scenario documentation, and expanded talking points
- Created video walkthrough outline with 7-part scripted structure covering event sourcing basics, saga patterns, and observability

## Key Decisions

- Blog posts numbered 04-06 to continue the series started in Phase 1 (posts 01-03)
- Demo scenarios designed as interactive shell scripts (not automated tests) for live presentation use
- Payment failure triggered by a dollar threshold ($10K) rather than a random/flag-based approach, making it predictable and demo-friendly
- Saga timeout demo uses a 60-second timeout, which is the configured `order-fulfillment` saga timeout
- Video walkthrough structured as 7 parts to cover the full breadth of the framework, not just saga patterns

## Files Changed

### Day 18 (commit `d5f7b96`)

| File | Change |
|------|--------|
| `docs/blog/04-observability-in-event-sourced-systems.md` | Created — blog post on metrics, dashboards, tracing |
| `docs/blog/05-saga-pattern-for-distributed-transactions.md` | Created — blog post on saga choreography and compensation |
| `docs/blog/06-vector-similarity-search-with-hazelcast.md` | Created — blog post on vector store and edition-aware beans |
| `docs/blog/01-event-sourcing-with-hazelcast-introduction.md` | Modified — updated series count to "of 6", expanded "What's Next" |
| `docs/blog/02-building-event-pipeline-with-hazelcast-jet.md` | Modified — updated series count to "of 6" |
| `docs/blog/03-materialized-views-for-fast-queries.md` | Modified — updated series count to "of 6", added "Next" link |

### Day 19 (commit `b41b1e2`)

| File | Change |
|------|--------|
| `scripts/demo-scenarios.sh` | Modified — added Scenario 5 (payment failure) and Scenario 6 (saga timeout), +427 lines |
| `docs/demo/demo-walkthrough.md` | Modified — added saga architecture diagrams, payment service endpoints, new scenario docs, talking points (+352/-45 lines) |
| `docs/demo/video-walkthrough-outline.md` | Created — 7-part video walkthrough outline (253 lines) |
