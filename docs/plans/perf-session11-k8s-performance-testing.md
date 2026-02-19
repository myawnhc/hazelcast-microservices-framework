# Session 11: Multi-Deployment K8s Performance Testing

**Date:** 2026-02-19
**Phase:** Performance Engineering

## Context

Sessions 1-10 established comprehensive performance baselines on Docker Compose (k6 load tests, JMH benchmarks, A/B testing, sustained load). Session 11 infrastructure (AWS EKS scripts, Helm enhancements, tier configs) was committed in `030af19`. What remains is the **test orchestration** and **execution**: run k6 against K8s deployments (local Docker Desktop first, then AWS EKS tiers), capture K8s-specific metrics, and produce a cross-deployment comparison.

The existing k6 scripts work transparently against K8s because `k8s-local/start.sh` sets up port-forwards to the same `localhost:808x` ports that k6-config.js defaults to.

## Deliverables

| # | File | Purpose |
|---|------|---------|
| 1 | `scripts/perf/k8s-perf-test.sh` | K8s performance test orchestrator (TPS sweep) |
| 2 | `scripts/perf/k8s-compare.sh` | Cross-deployment comparison report generator |
| 3 | `docs/perf/deployment-comparison.md` | Results document (populated after test runs) |
| 4 | `docs/plans/perf-session11-k8s-performance-testing.md` | This plan |

## Implementation

### Step 1: `scripts/perf/k8s-perf-test.sh`

**CLI interface:**
```bash
./scripts/perf/k8s-perf-test.sh --target local --tps-levels "10,25,50"
./scripts/perf/k8s-perf-test.sh --target aws-small --tps-levels "10,25,50,100"
./scripts/perf/k8s-perf-test.sh --target aws-medium --duration 3m
```

**Flags:** `--target` (required: local|aws-small|aws-medium|aws-large), `--scenario` (default: constant), `--tps-levels` (default: "10,25,50"), `--duration` (default: 3m), `--namespace` (default: hazelcast-demo), `--skip-data`, `--skip-health-wait`

**Execution flow:**
1. Preflight — verify kubectl, k6, jq reachable; verify cluster accessible
2. Verify K8s environment — check pods running, ensure port-forwards active, health check all services (300s timeout)
3. Load sample data — call `generate-sample-data.sh` (unless `--skip-data`)
4. TPS sweep — for each TPS level: capture pre-test `kubectl top pods`, call `run-perf-test.sh`, copy k6 result JSON to run dir, capture post-test metrics, 15s stabilization
5. Final capture — `kubectl top pods`, `kubectl top nodes` (AWS), pod distribution
6. Summary — write `manifest.json`, generate `sweep-summary.md` with per-TPS table

### Step 2: `scripts/perf/k8s-compare.sh`

Compares two K8s sweep directories or Docker Compose baseline vs K8s results. Reads `manifest.json`, extracts metrics from k6 JSON per matching TPS level, generates markdown comparison table.

### Step 3: `docs/perf/deployment-comparison.md`

Template populated from actual test results. Structure:
- Test matrix (deployment, environment, TPS levels, resources)
- Throughput/latency comparison tables
- Resource usage comparison
- Vertical scaling analysis
- Key findings

## Key Design Decisions

1. **Reuse `run-perf-test.sh`** for k6 invocation — consistent options, result naming, ID loading
2. **No Docker lifecycle** — expects K8s already deployed
3. **Graceful `kubectl top` handling** — Docker Desktop may lack metrics-server; warn and continue
4. **Port-forward for all targets** — consistent measurement methodology
5. **Bash 3.2 compatible** — no associative arrays, POSIX parameter expansion only

## Verification

1. `bash -n scripts/perf/k8s-perf-test.sh` — syntax check
2. `bash -n scripts/perf/k8s-compare.sh` — syntax check
3. Run local K8s sweep at `--tps-levels "10,25"` with `--duration 1m` (quick validation)
4. Verify manifest.json written correctly and sweep-summary.md generated
