# Session 8: Sustained Load and Stability Testing

**Date:** 2026-02-18
**Type:** Performance exercise — sustained load, memory/GC stability, automated dashboard capture

---

## Objectives

1. Run 30-minute sustained load tests to find memory leaks, GC accumulation, and unbounded map growth
2. Capture automated Grafana dashboard screenshots during long runs for showcase-quality artifacts
3. Document stability analysis with before/after comparisons
4. Fix any stability issues discovered (completions map eviction, pendingCompletions growth)

---

## Deliverables

| File | Purpose |
|------|---------|
| `docker/docker-compose-renderer.yml` | Docker Compose override adding grafana-image-renderer |
| `scripts/perf/capture-dashboards.sh` | Captures all 6 Grafana dashboards as PNG screenshots |
| `scripts/perf/k6-scenarios/sustained-load.js` | k6 script for 30-60 minute sustained load |
| `scripts/perf/run-sustained-test.sh` | Master orchestrator: setup, load + periodic capture, summary |
| `docs/perf/stability-analysis.md` | Findings document with trends and fix recommendations |
| `docs/perf/screenshots/` | Showcase-quality dashboard PNGs from 30-minute run |

---

## Key Risks to Monitor

| Risk | Metric | Threshold |
|------|--------|-----------|
| `pendingCompletions` orphan growth | `eventsourcing.pending.completions` gauge | Monotonic increase = leak |
| Completions map unbounded growth | IMap `*_COMPLETIONS` size | 50 TPS x 30 min = 90K entries (no eviction) |
| Event store unbounded growth | IMap `*_ES` size | Grows monotonically without eviction |
| JVM heap exhaustion | `jvm.memory.used` / `jvm.memory.max` | >90% sustained = risk |
| GC pressure | `jvm.gc.pause` duration trend | Increasing pause times = accumulation |
| PostgreSQL connection pool | `hikaricp.connections.active` | Sustained at max = pool exhaustion |
| Docker OOM kills | `docker inspect` exit code 137 | Any OOM = memory limit too low |

---

## Test Configuration

- **Duration**: 30 minutes (configurable)
- **Target TPS**: 50 (configurable)
- **Workload**: 60% orders / 25% stock reservations / 15% customer creations
- **Executor**: `constant-arrival-rate` (same as mixed-workload.js)
- **Checkpoint captures**: Every 10 minutes
- **Dashboard screenshots**: All 6 dashboards at each checkpoint + final

---

## Success Criteria

- 30 min at 50 TPS with no OOM kills
- Error rate < 1%
- No monotonic memory growth (heap should show sawtooth GC pattern)
- `pendingCompletions` gauge stays bounded
- All stability issues documented with fixes applied
