# Demo Guide & Talk Track

How to run and present the Hazelcast Microservices Framework for customer demos, trade shows, and technical deep dives.

## Quick Start

```bash
# Build Docker images (first time only)
mvn clean package -DskipTests
docker compose -f docker/docker-compose.yml build

# One command to start everything
./scripts/docker/start-demo.sh
```

This starts all services in demo mode, loads sample data (customers + products), and runs a k6 load generator at 30 TPS for 10 minutes.

**What to open**:
- Grafana: http://localhost:3000 (admin/admin)
- API Gateway: http://localhost:8080
- Management Center: http://localhost:8888

---

## 5-Minute Talk Track

The fast version for conference booths, meetings, and quick demos.

### Act 1 — Architecture Overview (1 minute)

**What to show**: Terminal with `docker ps`

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -E "(hazelcast|account|inventory|order|payment|gateway|grafana|prometheus)"
```

**Talk track**:

> "This is a complete event-sourcing microservices platform running on your laptop. Four services — Account, Inventory, Order, and Payment — each with an embedded Hazelcast instance running a Jet streaming pipeline. They communicate through a shared 3-node Hazelcast cluster using ITopic pub/sub.
>
> Every state change is an event. Events flow through the pipeline, get persisted to an event store, update materialized views, and publish to other services — all in sub-millisecond time."

### Act 2 — Business Dashboard (1.5 minutes)

**What to show**: Grafana → Business Overview dashboard

**Talk track**:

> "Let's look at the business view first — this is what a product manager or executive would see.
>
> [Point to stat panels] Orders are flowing in at about 30 per minute. Success rate is at 100% — every order saga completes. We can see revenue accumulating, items being sold, and our customer base growing.
>
> [Point to time-series] Here's orders per minute over time — smooth and consistent. And here are the saga outcomes — all green, all completing successfully.
>
> This is a real distributed transaction — an order touches four services: order creation, stock reservation, payment processing, and confirmation. Hazelcast orchestrates this as a saga pattern."

### Act 3 — Event Flow Dashboard (1.5 minutes)

**What to show**: Grafana → Event Flow dashboard

**Talk track**:

> "Now let's look under the hood at the event pipeline.
>
> [Point to events received/processed] Every event type is tracked — CustomerCreated, OrderCreated, StockReserved, PaymentProcessed, OrderConfirmed. You can see them flowing through in real time.
>
> [Point to latency panels] End-to-end pipeline latency is under 5 milliseconds at p95. The queue wait time is typically under 1 millisecond — events are processed almost instantly by the Jet pipeline.
>
> [Point to stage durations] Each pipeline stage — persist to event store, update materialized view, publish to other services — takes microseconds. This is the power of keeping everything in-memory with Hazelcast."

### Act 4 — Force a Failure (1 minute)

**What to show**: Terminal + Business Overview dashboard

```bash
# Create an order with quantity exceeding inventory
curl -s http://localhost:8080/api/orders -H "Content-Type: application/json" -d '{
  "customerId": "test-customer-1",
  "customerName": "Demo User",
  "customerEmail": "demo@example.com",
  "shippingAddress": "123 Demo St",
  "lineItems": [{"productId": "PROD-001", "productName": "Widget", "sku": "WDG-001", "quantity": 99999, "unitPrice": 29.99}]
}'
```

**Talk track**:

> "Now let's see what happens when things go wrong. I'm submitting an order with quantity 99,999 — way more than what's in stock.
>
> [Switch to Business Overview] Watch the success rate — it dips briefly, then recovers. The saga detected that inventory couldn't fulfill this order, automatically triggered compensation: the payment was refunded, the stock reservation was released, and the order was cancelled.
>
> [Switch to Saga Dashboard] You can see the compensated saga here — one compensation event, automatically handled. No manual intervention, no inconsistent state. That's the saga pattern with Hazelcast."

### Wrap (30 seconds)

**Talk track**:

> "Three takeaways: First, sub-second distributed transactions across four services using Hazelcast Jet and ITopic. Second, automatic compensation when things fail — no orphaned state. Third, zero external dependencies beyond Hazelcast — no Kafka, no RabbitMQ, no separate event bus. Hazelcast is the compute grid, the event bus, and the data store."

---

## 15-Minute Deep Dive

Extends the 5-minute version with additional sections. Run all 5 minutes first, then continue.

### Management Center Walkthrough (3 minutes)

**What to show**: http://localhost:8180

**Talk track**:

> "Hazelcast Management Center gives us visibility into the cluster itself.
>
> [Navigate to Map Browser] Here are our IMaps — event stores, materialized views, saga state, outbox. You can browse the actual data. Let's look at the Order_VIEW map — here's the order we just created, stored as a Compact Serialized GenericRecord.
>
> [Navigate to Cluster view] Three Hazelcast nodes in our shared cluster, 271 partitions distributed across them. Each service also has its own embedded standalone instance for Jet processing.
>
> [Navigate to Jet Jobs] Here are our Jet streaming jobs — one per service. Each runs a continuous pipeline: read from event journal, persist, update view, publish. They've been running since startup."

### Materialized Views Dashboard (2 minutes)

**What to show**: Grafana → Materialized Views dashboard

**Talk track**:

> "Materialized views are the read side of our CQRS pattern. When an event is processed, the view updater projects it into a queryable format.
>
> [Point to view update rate] View updates track 1:1 with events processed — every event updates its corresponding view. The update_view stage latency is typically under 1 millisecond because it's an in-memory IMap write.
>
> Customer queries always hit the view, never the event store. This gives us fast reads without replaying events."

### Persistence Dashboard (2 minutes, production mode only)

**What to show**: Grafana → Persistence Layer dashboard

> Note: This dashboard only shows data when running in production mode (`--mode production`), which enables PostgreSQL write-behind persistence.

**Talk track**:

> "In production mode, we add PostgreSQL as a durable store. Hazelcast's MapStore write-behind mechanism batches writes to the database asynchronously.
>
> [Point to store count] You can see events being persisted at the batch level — the write-behind delay is 5 seconds by default, so writes accumulate and flush in batches for efficiency.
>
> [Point to latency] Store duration shows the actual database write time. Even under load, batch writes complete in under 50 milliseconds. The application never blocks — Hazelcast handles durability in the background."

### Jaeger Trace Walkthrough (2 minutes, if tracing enabled)

**What to show**: Jaeger UI at http://localhost:16686

> Note: Tracing is disabled in demo mode to reduce resource usage. Enable with `./scripts/docker/start.sh --mode production`.

**Talk track**:

> "OpenTelemetry traces show the full journey of a request across services.
>
> [Search for traces from order-service] Here's an order creation trace — you can see the HTTP request hit the API Gateway, route to the Order Service, which publishes an event. The saga then fans out to Inventory Service for stock reservation and Payment Service for processing, before coming back to Order Service for confirmation.
>
> Each span shows the exact timing — you can see where time is spent and identify bottlenecks."

---

## Trade Show Variant

For extended demos running on a booth laptop for hours at a time.

### Setup

```bash
./scripts/docker/start-demo.sh --tps 3 --duration 8h
```

This runs at a gentle 3 TPS for 8 hours — enough to keep dashboards active without stressing the laptop.

### Considerations

- **Memory**: Demo mode uses ~5.5GB total. Monitor with `docker stats`.
- **IMap eviction**: Demo mode has aggressive eviction (2K entries max) — maps won't grow unbounded.
- **Battery**: On a laptop, expect 3-4 hours on battery at this load level. Keep charger connected for full-day events.
- **Screen burn-in**: Use Grafana auto-refresh (default 30s) and consider rotating between dashboards.
- **Recovery**: If a service crashes, `docker compose restart <service-name>` will rejoin. The pipeline auto-restarts.

### What to Watch For

- **Dashboard goes blank**: Prometheus may have restarted. Check `docker ps` and `docker compose restart prometheus`.
- **Success rate drops**: Normal — a small percentage of sagas may timeout during GC pauses. Rate recovers quickly.
- **Memory creeping up**: Check `docker stats`. If order-service exceeds 1.2GB, restart it.

---

## Audience-Specific Talking Points

### For Architects

Focus on patterns and design decisions.

- **Event Sourcing**: Events are immutable facts. State is derived, not stored. Full audit trail by design.
- **CQRS**: Write model (event store) and read model (materialized views) are separate. Views are optimized for queries.
- **Saga Pattern**: Distributed transactions without 2PC. Choreography via ITopic, compensation via step reversal.
- **Dual-Instance Architecture**: Each service runs an embedded Hazelcast for local Jet processing, plus a client to the shared cluster for cross-service communication. This solves the Jet lambda serialization problem.
- **Why not Kafka?**: Hazelcast serves as compute grid, event bus, AND data store. One technology instead of three. Sub-millisecond latency vs Kafka's 2-10ms.

### For Executives

Focus on business outcomes and risk reduction.

- **Reliability**: 99.9%+ saga success rate under sustained load. Automatic compensation means no orphaned orders.
- **Performance**: 200 TPS sustained with sub-second saga completion. Scales horizontally in Kubernetes.
- **Operational Simplicity**: One infrastructure dependency (Hazelcast) instead of Kafka + Redis + message queue. Fewer moving parts = fewer outage vectors.
- **Cost**: Runs on 3 Hazelcast nodes. Demo fits in a laptop. Production fits in 3 AWS m5.xlarge instances (~$460/month).
- **Developer Productivity**: Event sourcing controller handles the hard parts. Service developers write domain logic, not infrastructure code.

### For Developers

Focus on code and show the implementation.

```bash
# Show the event sourcing controller — the core abstraction
open order-service/src/main/java/com/theyawns/ecommerce/order/config/OrderServiceConfig.java

# Show a saga listener — cross-service event handling
open inventory-service/src/main/java/com/theyawns/ecommerce/inventory/saga/InventorySagaListener.java

# Show the Jet pipeline — the event processing engine
open framework-core/src/main/java/com/theyawns/framework/pipeline/EventSourcingPipeline.java
```

Key points to highlight:
- `EventSourcingController.builder()` — one builder sets up event store, view store, pipeline, and metrics
- `handleEvent()` returns `CompletableFuture` — async by default
- Saga listeners use `@Qualifier("hazelcastClient")` — cross-service communication through the shared cluster
- Pipeline stages are modular — persist, update view, publish — each independently testable

---

## What Each Dashboard Shows

Quick reference for all 7 dashboards.

| Dashboard | Purpose | Key Questions It Answers |
|-----------|---------|--------------------------|
| **Business Overview** | Executive/PM view | How many orders? What's the revenue? What's the success rate? |
| **Event Flow** | Event pipeline health | Are events flowing? What's the latency? Where are bottlenecks? |
| **Saga Dashboard** | Distributed transaction monitoring | Are sagas completing? Any compensations? Circuit breakers open? |
| **System Overview** | Service health at a glance | Are all services up? What's the HTTP request rate and latency? |
| **Materialized Views** | CQRS read-side health | Are views being updated? What's the view update latency? |
| **Persistence Layer** | Database write-behind monitoring | Are events being persisted? What's the batch write latency? |
| **Performance Testing** | Load test analysis | What's the throughput ceiling? Where does latency degrade? |

---

## Troubleshooting During Demos

### Services Not Ready

**Symptom**: API returns 503 or connection refused.

**Fix**: Services take 15-30 seconds to start. Check readiness:
```bash
curl -s http://localhost:8081/actuator/health | python3 -m json.tool
```

Wait for all services to report `UP` before starting the load generator.

### No Data on Dashboard

**Symptom**: Grafana panels show "No data".

**Possible causes**:
1. **Prometheus not scraping**: Check http://localhost:9090/targets — all targets should be UP
2. **Wrong time range**: Set Grafana to "Last 5 minutes" or "Last 15 minutes"
3. **No load running**: Start the k6 load generator or manually create orders
4. **Dashboard not provisioned**: Verify the dashboard JSON exists in `docker/grafana/dashboards/`

### k6 Load Generator Errors

**Symptom**: k6 reports high error rate.

**Fix**: Usually means services aren't ready. Stop k6, wait 30 seconds, restart:
```bash
# Check service health first
for port in 8081 8082 8083 8084; do
  echo "Port $port: $(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/actuator/health)"
done

# Then restart k6 manually
k6 run scripts/perf/k6-scenarios/demo-ambient.js
```

### Order Creation Fails

**Symptom**: POST to `/api/orders` returns 400 or 500.

**Common causes**:
- Missing `unitPrice` in line items AND product not in availability cache → Add `unitPrice` to the request
- Invalid `customerId` → Create a customer first via POST to `/api/customers`
- Saga timeout → Check if inventory and payment services are running

### Docker Memory Issues

**Symptom**: Service restarts (OOMKilled) or laptop slowdown.

**Fix**:
```bash
# Check memory usage
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}" | sort -k2 -h

# Restart a specific service
docker compose -f docker/docker-compose.yml -f docker/docker-compose-demo.yml restart order-service
```

If persistent, reduce TPS: stop k6 and restart with `--tps 1`.

### Grafana Login

Default credentials: `admin` / `admin`. Skip password change when prompted.

Dashboards are pre-provisioned — find them in the "Dashboards" menu (hamburger icon → Dashboards).
