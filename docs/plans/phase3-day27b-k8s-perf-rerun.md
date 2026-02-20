# Phase 3, Day 27b: K8s Performance Test Re-runs Across All Tiers

**Date**: 2026-02-20
**Status**: In Progress

## Context

Three significant commits landed since the last K8s test runs (Feb 19):
1. **Eviction fix** (2866947) — IMap eviction + saga purge to prevent OOM under sustained load
2. **Saga timeout fix** (1322487) — 4-part fix: CB exception classification, saga compensation, stock replenishment, multi-product load test
3. **Outbox wiring** (0c2abed) — OutboxPublisher wired into EventSourcingController in all 4 services

Previous test results are invalidated — need clean baselines across all tiers.

## Test Matrix

| Tier | Nodes | TPS Sweep | Duration | Status |
|------|-------|-----------|----------|--------|
| Local (Docker Desktop) | 1 | 10, 25, 50 | 3m each | Pending |
| AWS Small | 2x t3.xlarge | 10, 25, 50, 100 | 3m each | Pending |
| AWS Medium | 3x c7i.2xlarge | 10, 25, 50, 100, 200 | 3m each | Pending |
| AWS Large | 5x c7i.4xlarge | 10, 25, 50, 100, 200, 500 | 3m each | Conditional |

## Steps

1. Local K8s re-run (rebuild, redeploy, verify pods, TPS sweep)
2. AWS Small re-run (rebuild+push ECR, deploy, TPS sweep)
3. AWS Medium first run (cluster create, deploy, TPS sweep with HPA observation)
4. Decision gate (evaluate medium results)
5. AWS Large conditional run
6. Update deployment-comparison.md with all results

## Success Criteria

- Local: error rate < 2%, no OOM, no CrashLoopBackOff
- AWS Small: error rate < 1% at 100 TPS (was 1.73%)
- AWS Medium: error rate < 1% at 100 TPS, < 2% at 200 TPS, HPA triggers
- AWS Large: error rate < 1% at 200 TPS, < 5% at 500 TPS
