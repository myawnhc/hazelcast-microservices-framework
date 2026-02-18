# A/B Performance Test

Run an A/B performance comparison between two configuration variants.

## Steps

1. **Ask what to compare**: Ask the user which two configs they want to compare. Show the available profiles:
   - `community-baseline` - Community Edition defaults (no Enterprise needed)
   - `hd-memory` - HD Memory / native off-heap (Enterprise)
   - `tpc` - Thread-Per-Core networking (Enterprise)
   - `hd-memory-tpc` - Both HD Memory + TPC (Enterprise)
   - `high-memory` - Doubled memory limits (no Enterprise needed)
   - `profiling` - Baseline with async-profiler overhead

   If the user wants a custom config, create a new `.conf` file in `scripts/perf/ab-configs/` using the same KEY=VALUE format.

2. **Check prerequisites**:
   - Docker is running: `docker info`
   - k6 is installed: `k6 version`
   - jq is installed: `jq --version`
   - If Enterprise configs are selected, check `echo $HZ_LICENSEKEY` is set
   - Services are buildable: `mvn clean package -DskipTests` (or use `--skip-build` if already built)

3. **Run the A/B test**:
   ```bash
   ./scripts/perf/ab-test.sh \
     --config-a scripts/perf/ab-configs/<config-a>.conf \
     --config-b scripts/perf/ab-configs/<config-b>.conf \
     --name <descriptive-name>
   ```
   Optional overrides: `--tps`, `--duration`, `--scenario`, `--skip-build`, `--skip-data`

4. **Monitor progress**: The test runs each variant sequentially (typically 8-12 minutes total). Watch for:
   - Service health check timeouts
   - k6 threshold failures (non-fatal, results still captured)
   - Docker stats capture

5. **Read and present the report**: After the test completes, read the generated report:
   ```bash
   cat scripts/perf/ab-results/<run-name>/report.md
   ```
   Present key findings to the user: latency deltas, throughput differences, error rates, and the winner.

6. **Copy to docs**: The report is automatically saved to `docs/performance/`. Verify it's there and remind the user to commit it if desired.

## Compare-Only Mode

To re-compare previous results without re-running tests:
```bash
./scripts/perf/ab-test.sh --compare-only \
  scripts/perf/ab-results/<run-1>/manifest-a.json \
  scripts/perf/ab-results/<run-2>/manifest-b.json
```

This also works for cross-run comparisons (e.g., comparing today's baseline against last week's optimized run).
