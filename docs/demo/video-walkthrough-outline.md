# Video Walkthrough Outline

A scripted outline for recording a video demonstration of the Hazelcast Event Sourcing Microservices Framework.

**Target duration**: 15-20 minutes
**Audience**: Developers evaluating event sourcing with Hazelcast, architects designing microservice patterns

---

## Part 1: Introduction (2 minutes)

### Talking Points

- "Today we'll walk through a complete event sourcing microservices framework built on Hazelcast"
- "We'll see how Hazelcast powers every layer: event storage, stream processing, materialized views, and cross-service saga orchestration"
- Brief architecture overview:
  - 4 microservices: Account, Inventory, Order, Payment
  - 3-node Hazelcast cluster for shared state
  - Embedded Hazelcast instances for local Jet pipelines

### On Screen

- Show the architecture diagram (from demo-walkthrough.md)
- Show `docker compose ps` to demonstrate running services
- Quick health check of all services

### Script

```bash
docker compose -f docker/docker-compose.yml ps
curl -s http://localhost:8081/actuator/health | jq .status
curl -s http://localhost:8082/actuator/health | jq .status
curl -s http://localhost:8083/actuator/health | jq .status
curl -s http://localhost:8084/actuator/health | jq .status
```

---

## Part 2: Event Sourcing Basics — Happy Path (4 minutes)

### Talking Points

- "Every state change is an immutable event — nothing is ever overwritten"
- "Let's create a customer and see what happens under the hood"
- Walk through: `CustomerCreatedEvent` → Jet pipeline → event store → materialized view
- "Now let's create a product and place an order"
- Highlight denormalized order view: "Notice the customer name and email are embedded in the order response — no service-to-service call needed"

### On Screen

- Run `./scripts/demo-scenarios.sh 1` (Happy Path)
- Pause at each step to explain the event flow
- Show the enriched order JSON response, highlighting denormalized fields

### Key Moments to Highlight

1. **Customer creation**: Show the response, explain the 6-step event flow
2. **Order placement**: Show the saga starting automatically
3. **Saga completion**: Order reaches CONFIRMED status across 3 services
4. **Enriched view**: Point out `customerName`, `customerEmail` in order response
5. **Stock levels**: Show `quantityReserved` changed by saga

---

## Part 3: Saga Pattern — Payment Failure (4 minutes)

### Talking Points

- "What happens when something goes wrong? This is where the saga pattern shines"
- "We'll place an order that's too expensive — the payment service will decline it"
- "Watch how compensation happens automatically: stock released, order cancelled"
- "No manual intervention, no data inconsistency"

### On Screen

- Run `./scripts/demo-scenarios.sh 5` (Payment Failure)
- Show the high-value product creation ($5,999.99)
- Show the order being placed (total > $10,000)
- Wait for compensation to complete
- Show stock restored to original levels
- Show order status: CANCELLED

### Key Moments to Highlight

1. **Before**: Stock has 0 reserved
2. **Order placed**: Saga starts, stock gets temporarily reserved
3. **Payment fails**: Amount exceeds $10,000 threshold
4. **Compensation**: Stock automatically released, order cancelled
5. **After**: Stock back to 0 reserved — system is consistent

### Diagram to Show

```
OrderCreated → StockReserved → PaymentFailed
                                    ↓
                            Compensation Chain:
                            StockReleased → OrderCancelled
                            Saga: COMPENSATED
```

---

## Part 4: Saga Timeout — Service Outage (4 minutes)

### Talking Points

- "Real distributed systems have outages. What if a service goes down mid-saga?"
- "We'll stop the payment service and place an order"
- "The saga will stall — stock is reserved but payment never processes"
- "After 60 seconds, the timeout detector kicks in and triggers compensation"
- "This is production-grade resilience — no human intervention required"

### On Screen

- Run `./scripts/demo-scenarios.sh 6` (Timeout)
- Show payment service being stopped
- Place the order, show saga stalling at payment step
- Show countdown timer (60 seconds)
- Show compensation triggering automatically
- Show payment service being restarted

### Key Moments to Highlight

1. **Service down**: `docker compose stop payment-service`
2. **Saga stalls**: Order stuck in PENDING, stock reserved
3. **Timeout fires**: After ~60 seconds, detector finds the stalled saga
4. **Auto-compensation**: Stock released, order cancelled
5. **Recovery**: Payment service restarted, system healthy

### Configuration to Show

```yaml
saga:
  timeout:
    enabled: true
    check-interval: 5000          # Check every 5 seconds
    default-deadline: 30000       # 30 seconds default
    auto-compensate: true
    saga-types:
      OrderFulfillment: 60000     # 60 seconds for order sagas
```

---

## Part 5: Event Sourcing Power — Views & Rebuilding (2 minutes)

### Talking Points

- "Because we stored every event, we can do things CRUD can't"
- "Views are derived — if view logic has a bug, fix it and replay events"
- "Add new views at any time by replaying the event history"
- "Complete audit trail: every state change, who, when, what"

### On Screen

- Run `./scripts/demo-scenarios.sh 3` (View Rebuilding — conceptual)
- Show the event store → view updater → materialized view diagram
- Discuss the four rebuild scenarios: bug fix, new view, disaster recovery, schema migration

---

## Part 6: Observability (2 minutes)

### Talking Points

- "Every event carries a correlation ID for distributed tracing"
- "Saga state is tracked in Hazelcast — you can query active sagas, timeouts, compensations"
- "Prometheus metrics give you real-time visibility"

### On Screen

- Show Prometheus at http://localhost:9090
- Query `saga_timeouts_detected_total`
- Query `events_submitted_total`
- Show `sagas_active_count` gauge
- Show service health endpoints

### Metrics to Highlight

| Metric | What It Tells You |
|--------|-------------------|
| `events_submitted_total` | Event throughput |
| `saga_timeouts_detected_total` | System reliability issues |
| `sagas_active_count` | Current load / backlog |
| `saga_compensation_steps_total` | How often things go wrong |

---

## Part 7: Wrap-Up (1 minute)

### Talking Points

- "We've seen event sourcing, materialized views, saga orchestration, compensation, and timeout recovery — all powered by Hazelcast"
- "The framework works with Community Edition by default — Enterprise features like vector search are optional enhancements"
- "Everything is open source and designed to be educational"
- "Check out the blog series for deep dives into each pattern"

### On Screen

- Show the project README
- Link to the 6-part blog series
- Show the `CLAUDE.md` for developer guidance

---

## Production Notes

### Recording Setup

- Terminal with dark background, large font (14-16pt)
- Split screen: terminal on left, architecture diagram on right (for Part 1)
- Use `jq` for all JSON output (readable formatting)
- Run `./scripts/load-sample-data.sh` before recording

### Pre-Recording Checklist

- [ ] Docker services running and healthy
- [ ] Sample data loaded
- [ ] Prometheus accessible
- [ ] Terminal font size visible for recording
- [ ] All scenario scripts tested end-to-end

### Post-Production

- Add chapter markers at each Part boundary
- Add captions for key architecture concepts
- Add callout annotations for important JSON fields
- Speed up the 60-second timeout wait (Part 4) to ~10 seconds with time-lapse

---

## Appendix: Quick Reference Commands

```bash
# Start services
./scripts/build-docker.sh && ./scripts/start-docker.sh

# Load sample data
./scripts/load-sample-data.sh

# Run individual scenarios
./scripts/demo-scenarios.sh 1    # Happy path
./scripts/demo-scenarios.sh 5    # Payment failure
./scripts/demo-scenarios.sh 6    # Timeout

# Run all scenarios
./scripts/demo-scenarios.sh all

# Check service health
for port in 8081 8082 8083 8084; do
  echo "Port $port: $(curl -s http://localhost:$port/actuator/health | jq -r .status)"
done
```
