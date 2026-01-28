# ADR 006: Cross-Service Data via Materialized Views

## Status

**Accepted** - January 2026

## Context

In a microservices architecture, services often need data from other services:

- **Order Service** needs customer names and product details
- **Reports** need aggregated data across domains
- **Dashboards** need real-time consolidated views

Traditional approaches create problems:

1. **Synchronous API calls**: Coupling, latency, cascading failures
2. **Shared database**: Breaks service autonomy
3. **Data replication**: Complex sync, eventual consistency issues

### Options Considered

1. **Synchronous Service Calls**
   ```java
   // Order service calls Account service
   Customer customer = accountService.getCustomer(customerId);  // HTTP call!
   ```
   - Pros: Always current data
   - Cons: Latency, coupling, cascading failures, requires all services running

2. **Shared Database**
   - All services read/write same database
   - Pros: Simple, always consistent
   - Cons: Tight coupling, schema changes affect all, no autonomy

3. **Event-Driven Materialized Views**
   - Services subscribe to events and maintain local views
   - Pros: Decoupled, fast, works offline
   - Cons: Eventual consistency, more storage

## Decision

We use **event-driven materialized views** for cross-service data.

### Pattern

Each service that needs data from another:
1. Subscribes to relevant events
2. Maintains a local materialized view
3. Reads from local view (no service calls)

```
Account Service                    Order Service
┌────────────────┐                ┌─────────────────────────┐
│ CustomerCreated │               │ Local Customer View     │
│ Event          │────────────────►│ (customerId → name,    │
│                │   (via Topic)  │  email)                 │
└────────────────┘                └─────────────────────────┘
                                            │
                                            ▼
                                  ┌─────────────────────────┐
                                  │ Enriched Order View     │
                                  │ (orderId → order +      │
                                  │  customerName +         │
                                  │  productNames)          │
                                  └─────────────────────────┘
```

### Implementation: Enriched Order View

```java
public class EnrichedOrderViewUpdater extends ViewUpdater<String> {

    // Local copies of cross-service data
    private final IMap<String, GenericRecord> customerView;
    private final IMap<String, GenericRecord> productView;

    @Override
    protected GenericRecord applyEvent(GenericRecord event, GenericRecord current) {
        if ("OrderCreated".equals(getEventType(event))) {
            String customerId = event.getString("customerId");

            // Read from LOCAL view - no service call!
            GenericRecord customer = customerView.get(customerId);
            String customerName = customer != null ?
                customer.getString("name") : "Unknown";

            // Build enriched order with denormalized data
            return GenericRecordBuilder.compact("EnrichedOrder")
                .setString("orderId", event.getString("key"))
                .setString("customerId", customerId)
                .setString("customerName", customerName)  // Denormalized!
                .setString("customerEmail", ...)
                // ... rest of order data
                .build();
        }
        // ...
    }
}
```

### View Types

| View | Purpose | Data Source |
|------|---------|-------------|
| Customer View | Customer profile | CustomerCreated/Updated events |
| Product View | Product catalog | ProductCreated/Updated events |
| Order View | Basic order data | OrderCreated/Updated events |
| **Enriched Order** | Order + customer + product names | All above |
| **Customer Order Summary** | Aggregated stats per customer | Order events |

## Consequences

### Positive

- **No service calls**: All data local, sub-millisecond reads
- **Resilient**: Works even if other services are down
- **Scalable**: Each service scales independently
- **Fast**: No network latency for cross-service data
- **Decoupled**: Services don't know about each other at runtime

### Negative

- **Eventual consistency**: Views may briefly show stale data
- **Storage duplication**: Same data in multiple places
- **View maintenance**: Must update views when events change
- **Reference integrity**: Can have orders for deleted customers

### Handling Edge Cases

**Stale Reference Data:**
```java
// Customer might have been deleted or not yet created
GenericRecord customer = customerView.get(customerId);
String customerName = customer != null ?
    customer.getString("name") : "Customer " + customerId;
```

**Update When Source Changes:**
```java
// Listen for customer updates to refresh enriched orders
// (Not implemented in Phase 1 - accept eventual consistency)
```

**Consistency Requirements:**
| Scenario | Acceptable Staleness |
|----------|---------------------|
| Display customer name | Yes (seconds) |
| Stock availability | Maybe (depends on business) |
| Financial data | No (use synchronous check) |

### Cross-Service Views in This Framework

1. **EnrichedOrderView** - Order + Customer + Product data
2. **ProductAvailabilityView** - Product stock from Inventory events
3. **CustomerOrderSummaryView** - Aggregated order stats per customer

## References

- [CQRS Pattern](https://docs.microsoft.com/en-us/azure/architecture/patterns/cqrs)
- [Event-Driven Architecture](https://martinfowler.com/articles/201701-event-driven.html)
- [Materialized View Pattern](https://docs.microsoft.com/en-us/azure/architecture/patterns/materialized-view)
