# Real-Time Fraud Scoring — Feature Sketch

**Status**: Ideation
**Date**: 2026-03-17
**Context**: Exploring ML inference as a Jet pipeline showcase within the existing ecommerce demo.

---

## The Pitch

Add a fraud-scoring step to the order saga. When an order is placed, a Jet pipeline
extracts features from materialized views (customer history, order patterns, account age),
computes a risk score, and the saga branches on the result: approve, reject, or flag for
manual review. Demonstrates real-time ML inference patterns using the same Hazelcast
primitives the demo already uses.

## Why This Feature

- **Saga extensibility**: Shows how to add a new compensatable step to an existing saga
  (fraud-check sits between inventory reservation and payment processing; rejection
  triggers inventory release via the existing compensation path).
- **Jet as a feature-engineering engine**: The genuinely hard part of ML inference in
  production is real-time feature extraction — joining streams with historical state,
  computing windowed aggregations, enriching context. Jet does this natively.
- **Hazelcast surface area**: IMap lookups (near-cached customer history), windowed
  aggregations, entry processors (updating customer risk profiles), ITopic (saga events),
  and potentially the vector store (similarity to known-fraud patterns).
- **Lightweight**: No GPU, no model server, no new heavy service. Scoring logic runs
  in-process within a Jet pipeline stage.

## The Data Problem

The demo's data generation is randomized — any patterns a model finds would be
meaningless. Two options:

### Option A: Obvious Injection (Weak)
Flag N% of orders with a single suspicious trait (high value, mismatched address). A rules
engine catches this trivially. The ML adds nothing. This is theater.

### Option B: Behavioral Personas (Preferred)
Generate a small population of "fraudster personas" whose signals emerge from *sequences
of events across time*, not individual transaction fields:

- **Account-building fraud**: New account → several small orders over days → sudden
  high-value order to a different shipping address.
- **Velocity spike**: Burst of orders from a single account within a short window,
  especially targeting recently-restocked items.
- **Ring pattern**: Multiple accounts sharing shipping addresses but using different
  payment methods (requires cross-account correlation).
- **Value escalation**: Gradually increasing order values that stay just under
  review thresholds.

No single field on any single order is a red flag. The signal is in the *combination
across time and entities*. This is the honest argument for why a rules engine gets ugly:
you're writing rules about temporal sequences across multiple materialized views, and the
rule set becomes brittle and hard to maintain.

## Architecture

### Feature Engineering Pipeline (the interesting part)

A Jet pipeline that computes a `TransactionFeatures` vector for each incoming order:

- **Account age** — days since customer creation (from account view)
- **Order history stats** — count, average value, stddev (from order view, windowed)
- **Velocity** — orders in last N minutes for this customer (windowed aggregation)
- **Address consistency** — does shipping address match prior orders?
- **Value deviation** — how many stddevs is this order from customer's mean?
- **Cross-account signals** — other accounts sharing this shipping address?

This is genuinely complex streaming work: multi-map joins, stateful windowed processing,
per-customer state management. It's a real Jet showcase regardless of what does the
final scoring.

### Scorer (pluggable)

```java
public interface FraudScorer {
    FraudScore score(TransactionFeatures features);
}
```

- **`RuleBasedFraudScorer`** ships with the demo. Handles the composite behavioral
  patterns. Not trivial rules — temporal and cross-entity logic — but transparent
  and debuggable.
- **Swappable**: The interface exists so the blog post / talk can say "in production,
  plug in an ONNX model, call SageMaker, or use Hazelcast's ML inference pipeline."
  The feature engineering pipeline stays the same.

### Demo Story

> "Here's the part of ML inference nobody talks about — real-time feature engineering
> on streaming events. The model is the easy part to swap in. The pipeline that feeds
> it is the hard part, and that's what Jet does."

This is a defensible and honest claim. It sidesteps the awkwardness of training a real
model on synthetic data while still demonstrating the production pattern.

### Saga Integration

```
Order Created
  → Reserve Inventory (existing)
  → Fraud Check (new — async, with timeout)
      → APPROVE: continue to Payment
      → REJECT: compensate inventory, move to DLQ with reason
      → REVIEW: park order, notify (could integrate with MCP for AI triage)
  → Process Payment (existing)
  → Confirm Order (existing)
```

The REVIEW path is interesting — a flagged order could be surfaced through the MCP
server as a tool that an AI agent (or human) can inspect and adjudicate. Nice tie-in
to the existing MCP work.

## Open Questions

1. **New service or component within order-service?** A separate `fraud-service` is
   cleaner architecturally and better demonstrates service isolation, but adds resource
   pressure. A fraud-scoring component within order-service is lighter but muddies the
   service boundary story. Leaning toward a thin new service, but need to test memory
   footprint.

2. **Where does feature state live?** The windowed aggregations (order velocity,
   running averages) need to persist somewhere. Options: Jet job state (lost on restart),
   a dedicated IMap (survives restarts, adds memory), or computed on-demand from the
   event store (correct but potentially slow).

3. **Fraud persona injection**: Should the load generator inject fraud personas
   continuously (steady ~5% rate), in bursts (simulate an attack), or both? Bursts
   are more visually dramatic on the dashboard. Continuous injection better tests
   steady-state scoring.

4. **Dashboard treatment**: A fraud-specific Grafana panel showing score distribution,
   accept/reject/review rates, and detection latency would make the demo compelling.
   How much dashboard work is this?

5. **Vector store tie-in**: Could represent known fraud patterns as vectors and use
   similarity search to compare incoming transaction feature vectors against them.
   Elegant if it works, but might be forcing two features together. Worth prototyping.

6. **Blog post angle**: This feature has a natural narrative arc — the ideation dialog
   (this sketch and the conversation that produced it), the implementation, and the
   "what would production look like" discussion. Could be a two-part post.

## Resource Budget

- **Memory**: One additional service at ~384Mi-1Gi, or zero if embedded in order-service.
- **CPU**: Feature extraction pipeline is map lookups + arithmetic. Lightweight.
- **New IMaps**: `fraud-features` (customer feature vectors), `fraud-results` (score
  history for dashboard). Both small, bounded by customer count.
- **Risk**: Main concern is laptop deployment already running 4 services + Hazelcast
  cluster + Grafana + Postgres. A 5th service might push past comfortable limits.
  Test this early.