# Claude Code Agents

This directory contains specialized agent prompts for different types of tasks in the Hazelcast Microservices Framework project.

## Available Agents

| Agent | File | Use When |
|-------|------|----------|
| **Framework Developer** | `framework-developer.md` | Building core framework components |
| **Service Developer** | `service-developer.md` | Building Account, Inventory, Order services |
| **Test Writer** | `test-writer.md` | Writing unit and integration tests |
| **Documentation Writer** | `documentation-writer.md` | Writing JavaDoc, README, guides |
| **Pipeline Specialist** | `pipeline-specialist.md` | Working with Hazelcast Jet pipelines |
| **Config Manager** | `config-manager.md` | Managing application configuration |
| **Performance Optimizer** | `performance-optimizer.md` | Optimizing for performance targets |
| **Debugging Helper** | `debugging-helper.md` | Troubleshooting issues |

## Usage

Reference an agent's context when working on relevant tasks:

```
Please use the framework-developer agent context for this task:
Implement the EventStore interface.
```

Or for multi-step work:

```
Using the service-developer agent, create the AccountService with:
- Customer domain object
- CustomerCreatedEvent
- REST endpoints for CRUD operations
```

## Task Routing Guide

| Task Type | Recommended Agent |
|-----------|-------------------|
| Implement EventStore | Framework Developer |
| Create AccountService | Service Developer |
| Write pipeline tests | Test Writer |
| Update README | Documentation Writer |
| Build Jet pipeline | Pipeline Specialist |
| Setup Hazelcast config | Config Manager |
| Improve query performance | Performance Optimizer |
| Fix flaky test | Debugging Helper |

## Multi-Agent Workflows

### New Framework Component
1. **Framework Developer** → Define interface + implementation
2. **Test Writer** → Write comprehensive tests
3. **Documentation Writer** → JavaDoc + usage guide
4. **Performance Optimizer** → Verify performance

### New Microservice
1. **Service Developer** → Implement service + controllers
2. **Test Writer** → Unit + integration tests
3. **Config Manager** → Service configuration
4. **Documentation Writer** → API docs + README

### New Feature
1. **Service Developer** → Implement feature
2. **Pipeline Specialist** → Update pipeline if needed
3. **Test Writer** → Add tests
4. **Performance Optimizer** → Verify performance
5. **Documentation Writer** → Update docs

## Quality Gates

All agents should ensure:
- [ ] Code compiles without warnings
- [ ] Tests pass (>80% coverage)
- [ ] JavaDoc complete
- [ ] Only Hazelcast Community Edition features used
