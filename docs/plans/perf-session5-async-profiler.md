# Session 5: Profiling Setup and First Flame Graphs

**Date:** 2026-02-17
**Permanent copy:** `docs/plans/perf-session5-async-profiler.md`

---

## Context

Sessions 1-4 established baseline measurements (10/25/50 TPS), k6 scripts, sample data, and a Grafana dashboard. Follow-up work resolved the saga timeout bottleneck and measured gateway overhead. Now we need to identify CPU and allocation hotspots via flame graphs to guide optimization in Session 6.

## Approach: Volume-Mount (no Dockerfile changes)

Download async-profiler v4.3 to the host, mount it into containers via a Docker Compose override file. This avoids modifying any service Dockerfiles while being fully reproducible.

**Key constraints (macOS Docker Desktop):**
- `perf_events` NOT available — async-profiler auto-falls back to `itimer` (wall-clock profiling)
- Wall-clock is fine for identifying hotspots; allocation profiling (`-e alloc`) uses JVMTI and works fully
- Need `SYS_PTRACE` capability + `seccomp:unconfined`
- Need JVM flags: `-XX:+PreserveFramePointer -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`

---

## Files Created

| # | File | Purpose |
|---|------|---------|
| 1 | `docker/profiling/download-async-profiler.sh` | Download async-profiler binary (auto-detects arm64/x64) |
| 2 | `docker/docker-compose-profiling.yml` | Compose override: mounts profiler, adds capabilities, JVM flags |
| 3 | `scripts/perf/profile-service.sh` | Main script: warm up, run k6 load, capture flame graph, extract |
| 4 | `docs/perf/flamegraph-analysis-session5.md` | Analysis template (filled in after profiling) |
| 5 | `docs/perf/flamegraphs/.gitkeep` | Output directory for HTML flame graphs |

## Files Modified

| File | Change |
|------|--------|
| `.gitignore` | Added `docker/profiling/async-profiler/`, `docker/profiling/output/`, `docs/perf/flamegraphs/*.html` |

**No existing Dockerfiles or docker-compose.yml modified.**

---

## End-to-End Workflow

```bash
# 1. Download async-profiler (one-time)
./docker/profiling/download-async-profiler.sh

# 2. Start services with profiling enabled
cd docker && docker compose -f docker-compose.yml -f docker-compose-profiling.yml up -d && cd ..

# 3. Wait for health, load sample data
./scripts/perf/generate-sample-data.sh

# 4. Capture CPU flame graph
./scripts/perf/profile-service.sh --service order-service --event cpu --duration 60 --tps 25

# 5. Capture allocation flame graph
./scripts/perf/profile-service.sh --service order-service --event alloc --duration 60 --tps 25

# 6. View results
open docs/perf/flamegraphs/order-service-cpu-*.html
open docs/perf/flamegraphs/order-service-alloc-*.html
```

## Key Technical Notes

- **PID detection**: Dockerfile uses `sh -c "java $JAVA_OPTS -jar app.jar"` so Java is NOT PID 1 — must use `pgrep`
- **libstdc++6**: Already included in `eclipse-temurin:17-jre-jammy` (Ubuntu 22.04) — no extra install needed
- **itimer limitations**: Shows wall-clock time including I/O wait; allocation profile is unaffected and more actionable for GC optimization
- **`-XX:+PreserveFramePointer`**: Critical for accurate stack walking; slight JIT performance cost (~1-2%) is acceptable for profiling runs
