# Framework CLI Tool — Feature Sketch

**Status**: Parked (future phase)
**Date**: 2026-02-27
**Context**: Teased at the end of blog post 13 (performance engineering). No implementation work started.

---

## Overview

A developer-experience CLI (`hzmf`) for scaffolding new services, running demos, and managing the framework's configuration.

## 1. Scaffolding (`hzmf new`)

- **`hzmf new service <name>`** — Generate a new microservice module with the dual-instance Hazelcast config (ADR 008), Spring Boot main class, `application.yml`, Dockerfile, and Helm subchart. Prompts for domain entity name, event types, and whether it participates in an existing saga.
- **`hzmf new event <ServiceName> <EventName>`** — Generate a `DomainEvent` subclass with Compact serialization (`toGenericRecord`/`fromGenericRecord`), `apply()` stub, and a matching unit test.
- **`hzmf new saga-listener <ServiceName> <TopicName>`** — Generate a saga listener wired to the `hazelcastClient` with idempotency guard, circuit breaker, and DLQ scaffolding already in place.

## 2. Running & Demo (`hzmf run`)

- **`hzmf run demo`** — `docker compose up` with health-check polling, then run the demo script (create customers, products, place orders). One-command "see it working" experience.
- **`hzmf run load <tps> [--duration 3m]`** — Wrapper around the k6 load tests with sensible defaults.
- **`hzmf run profile <service>`** — Attach async-profiler to a running container and produce a flame graph.

## 3. Management (`hzmf status`, `hzmf config`)

- **`hzmf status`** — Show running services, Hazelcast cluster state, saga in-flight counts, DLQ depth, circuit breaker states. Aggregates the existing REST admin endpoints.
- **`hzmf config edition`** — Show current edition (Community/Enterprise), detected features, and which beans are active.
- **`hzmf config show <service>`** — Dump the effective configuration for a service (resilience settings, outbox config, timeout config).
- **`hzmf dlq list|replay|discard`** — Shortcuts to the DLQ admin endpoints across all services.

## Implementation Considerations

- **Language**: A shell script (`bin/hzmf`) would be simplest and consistent with the existing `scripts/` tooling, but macOS bash 3.2 limits are painful. A Java-based CLI (Picocli + GraalVM native image) would be more robust and dogfood the framework's own stack. Could also be a simple Spring Boot CLI app.
- **Scaffolding templates**: Mustache or plain string replacement over template files in `src/main/resources/templates/`.
- **MCP overlap**: The MCP server already exposes `queryView`, `getEventHistory`, and `submitEvent`. The CLI's "status" commands could call the same REST endpoints or delegate to the MCP tools.
