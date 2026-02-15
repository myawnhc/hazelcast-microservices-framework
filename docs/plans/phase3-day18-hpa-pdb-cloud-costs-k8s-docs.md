# Phase 3, Day 18: HPA, PDB, Cloud Cost Estimates & K8s Documentation

**Date**: 2026-02-15
**Area**: 4 — Kubernetes Deployment (Days 15–18)
**Status**: Completed

## Summary

Added autoscaling (HPA), disruption protection (PDB), cloud cost estimates, and comprehensive deployment documentation — completing the Kubernetes area.

## What Was Built

### HPA Templates (5 services)
- `autoscaling/v2` HorizontalPodAutoscaler for account, inventory, order, payment, api-gateway
- Gated by `autoscaling.enabled` (default: false)
- CPU metric always present; memory metric optional via `targetMemoryUtilizationPercentage`
- Deployment templates updated to omit `replicas` when HPA is enabled

### PDB Templates (6 resources)
- **Hazelcast Cluster**: `minAvailable: 2` — preserves quorum during node drains (enabled by default)
- **5 microservice/gateway PDBs**: `maxUnavailable: 1` — safe at any replica count (disabled by default)

### Values Updates
- All 6 subcharts updated with `autoscaling` and/or `podDisruptionBudget` sections
- Umbrella `values.yaml` updated with commented override examples

### Documentation
- `k8s/README.md`: Prerequisites, quick start, architecture diagram, services table, HPA/PDB/Ingress configuration, troubleshooting, chart directory tree
- `docs/guides/cloud-deployment-guide.md`: AWS EKS / GCP GKE / Azure AKS cost estimates across demo/staging/production tiers with on-demand and spot pricing

## Files Created (14)
- 5 HPA templates (`charts/*/templates/hpa.yaml`)
- 6 PDB templates (`charts/*/templates/pdb.yaml`)
- `k8s/README.md`
- `docs/guides/cloud-deployment-guide.md`
- `docs/plans/phase3-day18-hpa-pdb-cloud-costs-k8s-docs.md`

## Files Modified (12)
- 5 deployment templates (conditional replicas)
- 6 subchart `values.yaml` (autoscaling + PDB sections)
- 1 umbrella `values.yaml` (override examples)

## Validation
- `helm lint .` passes
- `helm template` with defaults: Deployments have `replicas`, no HPA rendered, only Hazelcast PDB rendered
- `helm template` with `--set account-service.autoscaling.enabled=true`: account Deployment omits `replicas`, HPA rendered
