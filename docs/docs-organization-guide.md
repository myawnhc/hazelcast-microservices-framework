# Documentation Organization Guide
## Recommended Structure for hazelcast-microservices-framework/docs/

---

## Proposed Directory Structure

```
docs/
├── requirements/                      # High-level requirements
│   └── hazelcast-phased-requirements.md
│
├── design/                           # Conceptual design documents
│   └── hazelcast-ecommerce-design.md
│
├── architecture/                     # Architectural decisions & patterns
│   ├── phase1-event-sourcing-architecture.md
│   ├── code-review-and-recommendations.md
│   └── adr/                         # Architecture Decision Records (future)
│       ├── 001-event-sourcing-choice.md
│       ├── 002-hazelcast-for-views.md
│       └── 003-rest-over-grpc.md
│
├── implementation/                   # Implementation-specific plans
│   ├── phase1-implementation-plan.md   ← PUT HERE
│   ├── phase2-implementation-plan.md   (future)
│   └── phase3-implementation-plan.md   (future)
│
├── api/                              # API specifications (future)
│   ├── openapi/
│   │   ├── account-service.yaml
│   │   ├── inventory-service.yaml
│   │   └── order-service.yaml
│   └── events/
│       └── event-catalog.md          # All event schemas
│
├── guides/                           # How-to guides & walkthroughs
│   ├── getting-started.md
│   ├── setup-guide.md
│   ├── demo-walkthrough.md
│   └── troubleshooting.md
│
├── blog-posts/                       # Draft blog posts
│   ├── 01-event-sourcing-intro.md
│   ├── 02-jet-pipeline.md
│   └── 03-materialized-views.md
│
└── diagrams/                         # Visual diagrams (future)
    ├── architecture-overview.png
    ├── event-flow.png
    └── service-topology.png
```

---

## Rationale

### requirements/
**Purpose**: Captures WHAT we need to build and WHY
- High-level business and technical requirements
- Multi-phase roadmap
- Success criteria

**Current files**:
- `hazelcast-phased-requirements.md` - Perfect here ✓

### design/
**Purpose**: Describes the conceptual solution (domain model, patterns, approach)
- Domain models (Customer, Product, Order)
- Service boundaries
- Event catalog design
- View design

**Current files**:
- `hazelcast-ecommerce-design.md` - Perfect here ✓

### architecture/
**Purpose**: Technical architecture decisions and patterns
- Event sourcing pattern details
- Hazelcast Jet pipeline architecture
- Code structure and patterns
- Technology choices

**Current files**:
- `phase1-event-sourcing-architecture.md` - Perfect here ✓
- `code-review-and-recommendations.md` - Perfect here ✓

**Future additions**:
- `adr/` subdirectory for Architecture Decision Records (ADRs)
  - Format: `001-decision-title.md` with Context, Decision, Consequences

### implementation/
**Purpose**: Detailed implementation plans with timelines, tasks, code templates
- Day-by-day breakdown
- Code templates
- Test strategies
- Dependencies and setup

**Recommendation**: **PUT `phase1-implementation-plan.md` HERE** ✓

**Why separate from architecture?**
- Architecture = timeless patterns and decisions
- Implementation = specific tasks, schedules, versions
- Implementation plans may change; architecture should be stable

### api/
**Purpose**: API contracts and specifications
- OpenAPI/Swagger specs for REST endpoints
- Event schemas
- Request/response examples

**Create when**: Day 14 of Phase 1 (API documentation day)

### guides/
**Purpose**: Practical how-to documentation for users/developers
- Setup instructions
- Demo walkthroughs
- Troubleshooting

**Create when**: Week 3 of Phase 1

### blog-posts/
**Purpose**: Educational content for external publication
- Draft blog posts
- Code examples for posts
- Screenshots/demos

**Create when**: Day 15 of Phase 1

### diagrams/
**Purpose**: Visual representations
- Architecture diagrams
- Sequence diagrams
- Data flow diagrams

**Create when**: As needed, export from tools like draw.io or Mermaid

---

## Recommended Actions Now

### 1. Create implementation/ directory
```bash
cd hazelcast-microservices-framework/docs
mkdir implementation
```

### 2. Move phase1-implementation-plan.md
```bash
mv ~/Downloads/phase1-implementation-plan.md docs/implementation/
```

### 3. Git commit
```bash
git add docs/implementation/
git commit -m "Add Phase 1 implementation plan"
```

### 4. Optional: Create placeholder directories
```bash
mkdir -p docs/{api/openapi,api/events,guides,blog-posts,diagrams,architecture/adr}
```

---

## Document Lifecycle

### As You Build:

**Week 1 (Framework Core)**:
- Update `architecture/phase1-event-sourcing-architecture.md` if implementation differs
- Create ADRs for major decisions (optional but recommended)

**Week 2 (Services)**:
- Create `api/events/event-catalog.md` documenting all events
- Start OpenAPI specs in `api/openapi/`

**Week 3 (Integration & Docs)**:
- Create `guides/getting-started.md`
- Create `guides/setup-guide.md`
- Create `guides/demo-walkthrough.md`
- Draft blog posts in `blog-posts/`

**After Phase 1**:
- Create `implementation/phase2-implementation-plan.md`
- Update `requirements/hazelcast-phased-requirements.md` with learnings

---

## Example ADR Format (for future)

When you make a significant architectural decision, document it:

**File**: `docs/architecture/adr/001-use-hazelcast-imap-for-eventstore.md`

```markdown
# ADR 001: Use Hazelcast IMap for EventStore in Phase 1

## Status
Accepted

## Context
We need to store events durably for event sourcing. Options:
1. Hazelcast IMap (in-memory)
2. PostgreSQL (persistent)
3. Hybrid (both)

## Decision
Use Hazelcast IMap only in Phase 1. Migrate to PostgreSQL in Phase 2.

## Consequences
**Positive**:
- Simpler implementation
- Faster development
- Good for demo/development

**Negative**:
- Events lost on cluster restart
- Memory constraints on event history

**Mitigations**:
- Document as Phase 1 limitation
- Plan PostgreSQL migration for Phase 2
- Use Hazelcast backup for resilience within cluster
```

---

## Quick Reference

| Document Type | Directory | Example |
|--------------|-----------|---------|
| Requirements | `requirements/` | phased-requirements.md |
| Design | `design/` | ecommerce-design.md |
| Architecture | `architecture/` | event-sourcing-architecture.md |
| Implementation Plan | `implementation/` | **phase1-implementation-plan.md** ✓ |
| API Specs | `api/` | account-service.yaml |
| How-To Guides | `guides/` | setup-guide.md |
| Blog Drafts | `blog-posts/` | 01-intro.md |
| Diagrams | `diagrams/` | architecture.png |

---

## Summary

**Put `phase1-implementation-plan.md` in `docs/implementation/`**

This keeps:
- Requirements separate from design
- Design separate from architecture
- Architecture separate from implementation
- Implementation plans versioned by phase

Clean, logical, and scalable as project grows!
