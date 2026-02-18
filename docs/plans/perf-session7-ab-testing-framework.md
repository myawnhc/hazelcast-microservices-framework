# Performance Session 7: General-Purpose A/B Testing Framework

**Date**: 2026-02-18

## Summary

Built a general-purpose A/B testing framework for comparing any two configurations
(Hazelcast settings, memory limits, Enterprise features, etc.) with retained results
and cross-run comparisons.

## Components

1. **Config profiles** (`scripts/perf/ab-configs/*.conf`) - 6 pre-built profiles
2. **A/B orchestrator** (`scripts/perf/ab-test.sh`) - runs variant A then B, compares
3. **Comparison engine** (`scripts/perf/ab-compare.sh`) - metrics extraction, markdown reports
4. **Chart generator** (`scripts/perf/ab-chart.py`) - optional matplotlib PNG charts
5. **Claude skill** (`.claude/skills/ab-test/SKILL.md`) - guided A/B test setup
6. **Docker overrides** - HD Memory and TPC compose files

## Files Created

- `scripts/perf/ab-test.sh`
- `scripts/perf/ab-compare.sh`
- `scripts/perf/ab-chart.py`
- `scripts/perf/ab-configs/community-baseline.conf`
- `scripts/perf/ab-configs/hd-memory.conf`
- `scripts/perf/ab-configs/tpc.conf`
- `scripts/perf/ab-configs/hd-memory-tpc.conf`
- `scripts/perf/ab-configs/high-memory.conf`
- `scripts/perf/ab-configs/profiling.conf`
- `docker/docker-compose-hd-memory.yml`
- `docker/docker-compose-tpc.yml`
- `docker/hazelcast/hazelcast-hd-memory.yaml`
- `.claude/skills/ab-test/SKILL.md`
- `docs/plans/perf-session7-ab-testing-framework.md`
