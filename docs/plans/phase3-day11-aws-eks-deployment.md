# Phase 3 Day 11 — AWS EKS Deployment Scripts & Configuration

**Date**: 2026-02-19
**Status**: Implemented

## Summary

Added AWS EKS deployment infrastructure for Session 11 multi-deployment performance testing.

## Changes Made

### Step 1: Docker Script Reorganization
- Moved `scripts/build-docker.sh` → `scripts/docker/build.sh`
- Moved `scripts/start-docker.sh` → `scripts/docker/start.sh`
- Moved `scripts/stop-docker.sh` → `scripts/docker/stop.sh`
- Updated `PROJECT_ROOT` from `$SCRIPT_DIR/..` to `$SCRIPT_DIR/../..`
- Updated 35 references across 14 files (README, docs, scripts, HTML datasheet)

### Step 2: Helm Chart Enhancements
- **hazelcast-cluster values.yaml**: Added `imagePullSecrets`, `topologySpreadConstraints`, configurable `terminationGracePeriodSeconds`, `rbac.create`, `partitionGroup`, `backPressure`, `jvm.gcOpts`
- **hazelcast-cluster statefulset.yaml**: Added `imagePullSecrets`, `topologySpreadConstraints`, configurable `terminationGracePeriodSeconds`, GC opts in JAVA_OPTS
- **hazelcast-cluster configmap.yaml**: Added conditional `partition-group` and `properties` (back-pressure) sections
- **New: hazelcast-cluster rbac.yaml**: Namespace-scoped Role/RoleBinding for K8s API discovery (gated by `rbac.create`)
- **All 6 service subcharts**: Added `imagePullSecrets` to deployment templates and values files
- **Umbrella values.yaml**: Added `global.imagePullSecrets`

### Step 3: AWS Tier Values Files
- `values-aws-small.yaml` — 2x t3.xlarge, 3 Hz replicas (1GB heap), ~$0.76/hr
- `values-aws-medium.yaml` — 3x c7i.2xlarge, 3 Hz replicas (4GB heap), ZONE_AWARE, HPA, ~$1.18/hr
- `values-aws-large.yaml` — 5x c7i.4xlarge, 5 Hz replicas (8GB heap), dedicated node pool, untested, ~$3.50/hr

### Step 4: AWS Scripts (`scripts/k8s-aws/`)
- `setup-cluster.sh` — EKS cluster + ECR repos via eksctl
- `build.sh` — Maven build + ECR push (linux/amd64, dual-tagged)
- `start.sh` — Helm deploy with tier overlay + ALB detection
- `stop.sh` — Helm uninstall + port-forward cleanup
- `teardown-cluster.sh` — Interactive cluster + ECR deletion
- `status.sh` — Pods, health, HPA, PDB, node info, cost estimate

### Step 5: AWS Deployment Guide
- `docs/guides/aws-deployment-guide.md` — Prerequisites, quick start, tier comparison, Hazelcast AWS best practices, cost estimation, troubleshooting

### Step 6: Housekeeping
- `.gitignore` — Added `scripts/k8s-aws/*.pids`
- `k8s/README.md` — Added AWS EKS section with tier summary and links

## Verification Results
- All 9 scripts pass `bash -n` syntax check
- No secrets/credentials in scripts
- `helm template` with small tier: RBAC rendered, no partition groups/back-pressure
- `helm template` with medium tier: RBAC, partition groups, back-pressure, topology spread all render correctly
- `helm template` with large tier: Hard anti-affinity, dedicated node selector, tolerations render correctly
- Default values (no overlay): No AWS features render — k8s-local unchanged

## File Summary

| Type | Count | Files |
|------|-------|-------|
| New | 14 | 3 docker scripts, 6 AWS scripts, 3 tier values, 1 RBAC template, 1 deployment guide |
| Deleted | 3 | Old docker scripts |
| Modified | ~28 | Helm values/templates, docs, scripts, .gitignore |
