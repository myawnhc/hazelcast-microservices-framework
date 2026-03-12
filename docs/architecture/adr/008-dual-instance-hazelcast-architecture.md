# ADR 008: Dual-Instance Hazelcast Architecture for Cross-Service Communication

## Status

**Accepted** - February 2026

## Context

The framework uses Hazelcast Jet pipelines for event sourcing (see ADR 004) and Hazelcast ITopic for cross-service saga coordination (see ADR 007). A fundamental tension exists between these two requirements:

### The Problem

When a Jet pipeline runs on a multi-node Hazelcast cluster, pipeline stages (lambdas) are **serialized and distributed** to all cluster members for execution. If a cluster member doesn't have the referenced classes on its classpath, deserialization fails:

```
com.hazelcast.jet.JetException: Class containing the lambda probably missing from class path
Caused by: ClassCastException: cannot assign instance of java.lang.invoke.SerializedLambda
    to field MapTransform.mapFn
```

This occurs because:
1. Each microservice has domain-specific classes (e.g., `CustomerViewUpdater`, `ProductViewUpdater`)
2. Jet pipelines use lambdas that reference these classes
3. When services join a shared cluster, Jet tries to run pipelines across all nodes
4. Nodes belonging to other services don't have each other's domain classes

### Approaches Evaluated

We evaluated five architectures over multiple iterations:

#### 1. Single Shared Cluster (All Services as Members)

**Configuration**: All microservices join the same Hazelcast cluster as full members.

**Problem**: Jet lambda serialization fails. When Order Service submits a pipeline job, Hazelcast distributes it to all cluster members including Inventory Service nodes, which lack `OrderViewUpdater.class`.

**Verdict**: Not viable without additional class deployment mechanisms.

#### 2. Separate Cluster Per Service

**Configuration**: Each service creates its own isolated Hazelcast cluster with a unique cluster name.

**Problem**: Cross-service communication (ITopic, shared IMap) is impossible. Sagas cannot propagate events between services because they're in different clusters.

**Verdict**: Breaks saga pattern entirely.

#### 3. Standalone Instance Per Service + External Cluster

**Configuration**: Services don't join any cluster; a separate 3-node Hazelcast cluster handles cross-service communication.

**Problem**: Services can't use ITopic/IMap on the external cluster without connecting to it. If they connect as clients, they can receive events, but Jet pipelines on the embedded instance can't serialize lambdas to the external cluster nodes anyway.

**Verdict**: Partial solution—still need a way to bridge local processing with shared communication.

#### 4. User Code Deployment / User Code Namespaces

**Configuration**: Use Hazelcast's class deployment features to upload service-specific classes to cluster nodes at runtime.

**Hazelcast provides two mechanisms**:

- **User Code Deployment** (deprecated in 5.x, removed in next major version): Distributed class loader that fetches classes from other members. **Community Edition users cannot use this going forward**—Hazelcast recommends adding resources to member classpaths manually.

- **User Code Namespaces** (5.4+): Modern replacement providing namespace isolation for user code. Appears to be **Enterprise Edition only** based on documentation code samples.

**Jet-specific alternative**: `JobConfig.addClass()` / `addJar()` can attach classes to individual Jet jobs. However:
- Requires knowing all transitive dependencies
- Spring Boot uber-JARs have non-standard structure (BOOT-INF/classes)
- Does not work well with Spring-managed beans and proxies
- Must be done at job submission time, not cluster configuration time

**Verdict**: Enterprise-only or deprecated. Not viable for Community Edition default (see ADR 005).

#### 5. Dual-Instance Architecture (Chosen)

**Configuration**: Each microservice runs TWO Hazelcast instances:

1. **Embedded Standalone Instance** (`hazelcastInstance`)
   - No cluster join (multicast disabled, no TCP-IP members)
   - Runs Jet pipeline locally
   - Stores local event store, view maps, pending events
   - Lambdas never need to serialize to other nodes

2. **Client Instance** (`hazelcastClient`)
   - Connects to external shared 3-node cluster
   - Used for cross-service ITopic pub/sub
   - Used for shared saga state IMap
   - No Jet jobs submitted through this instance

After the embedded Jet pipeline completes processing an event (persist → view update → local publish), the `EventSourcingController` **republishes** the event to the shared cluster's ITopic via the client instance.

## Decision

We adopt the **dual-instance architecture** with embedded standalone Hazelcast for local Jet processing and a Hazelcast client for cross-service communication.

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Docker Network                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │   hazelcast-1    │  │   hazelcast-2    │  │   hazelcast-3    │  │
│  │   (standalone)   │←→│   (standalone)   │←→│   (standalone)   │  │
│  │                  │  │                  │  │                  │  │
│  │  - ITopic        │  │  - ITopic        │  │  - ITopic        │  │
│  │  - saga-state    │  │  - saga-state    │  │  - saga-state    │  │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘  │
│           │                     │                     │             │
│           └──────────┬──────────┴──────────┬──────────┘             │
│                      │   (client connections)                       │
│           ┌──────────┼──────────┬──────────┼──────────┐             │
│           │          │          │          │          │             │
│  ┌────────▼────────┐ │ ┌────────▼────────┐ │ ┌────────▼────────┐   │
│  │  Order Service  │ │ │Inventory Service│ │ │ Payment Service │   │
│  ├─────────────────┤ │ ├─────────────────┤ │ ├─────────────────┤   │
│  │ [Embedded HZ]   │ │ │ [Embedded HZ]   │ │ │ [Embedded HZ]   │   │
│  │  - Jet Pipeline │ │ │  - Jet Pipeline │ │ │  - Jet Pipeline │   │
│  │  - Order_ES     │ │ │  - Product_ES   │ │ │  - Payment_ES   │   │
│  │  - Order_VIEW   │ │ │  - Product_VIEW │ │ │  - Payment_VIEW │   │
│  ├─────────────────┤ │ ├─────────────────┤ │ ├─────────────────┤   │
│  │ [HZ Client]─────┼─┘ │ [HZ Client]─────┼─┘ │ [HZ Client]─────┘   │
│  │  → ITopic sub   │   │  → ITopic sub   │   │  → ITopic sub   │   │
│  │  → ITopic pub   │   │  → ITopic pub   │   │  → ITopic pub   │   │
│  │  → saga-state   │   │  → saga-state   │   │  → saga-state   │   │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Event Flow

1. **Event Submitted** → `EventSourcingController.handleEvent()`
2. **Written to Pending Map** → Triggers embedded Jet pipeline
3. **Pipeline Stages 1-5** → Persist, update view, publish to local ITopic
4. **Pipeline Stage 6** → Write completion to completions map
5. **Completion Listener** → `republishToSharedCluster()`
6. **Cross-Service Publish** → Event sent to shared cluster's ITopic
7. **Saga Listeners** → Other services' `*SagaListener` classes receive event

### Configuration

```java
@Configuration
public class OrderServiceConfig {

    @Primary  // Required: hazelcast-spring needs a primary instance
    @Bean
    public HazelcastInstance hazelcastInstance() {
        Config config = new Config();
        // Standalone - no cluster join
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getJetConfig().setEnabled(true);
        return Hazelcast.newHazelcastInstance(config);
    }

    @Bean(name = "hazelcastClient")
    public HazelcastInstance hazelcastClient() {
        // Connect to external shared cluster
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName("ecommerce-cluster");
        clientConfig.getNetworkConfig().addAddress("hazelcast-1:5701", "hazelcast-2:5701");
        return HazelcastClient.newHazelcastClient(clientConfig);
    }

    @Bean
    public EventSourcingController<Order, String, DomainEvent<Order, String>> controller(
            HazelcastInstance hazelcastInstance,
            @Qualifier("hazelcastClient") HazelcastInstance hazelcastClient,
            ...) {
        return EventSourcingController.builder()
                .hazelcast(hazelcastInstance)        // For Jet pipeline
                .sharedHazelcast(hazelcastClient)    // For cross-service ITopic
                .build();
    }
}
```

### Saga Listeners

Saga listeners subscribe to the shared cluster's ITopic via the client:

```java
@Component
public class OrderSagaListener {

    public OrderSagaListener(
            @Qualifier("hazelcastClient") HazelcastInstance hazelcast) {
        // Subscribes to shared cluster topics
        ITopic<GenericRecord> topic = hazelcast.getTopic("PaymentProcessed");
        topic.addMessageListener(new PaymentProcessedListener());
    }
}
```

## Consequences

### Positive

- **No Enterprise dependency**: Works with Community Edition (ADR 005)
- **Clean separation**: Jet processing isolated from cross-service communication
- **Lambda serialization solved**: Pipelines never distribute across services
- **Full saga support**: ITopic works across all services via shared cluster
- **Independently scalable**: Shared cluster can scale separately from services

### Negative

- **Two instances per service**: Memory overhead of running two Hazelcast instances
- **Event republishing latency**: ~1-5ms added for cross-cluster publish
- **Configuration complexity**: Must manage two instances with different configurations
- **Spring bean disambiguation**: Requires `@Primary` and `@Qualifier` annotations

### Trade-offs vs Alternatives

| Aspect | Dual-Instance | Single Cluster + User Code |
|--------|---------------|---------------------------|
| Edition | Community | Enterprise only |
| Complexity | Medium | High (class management) |
| Memory | 2 instances/service | 1 instance/service |
| Latency | +1-5ms republish | None |
| Maintainability | Clear separation | Mixed concerns |

### Why Not User Code Namespaces?

User Code Namespaces would theoretically allow uploading service-specific classes to a shared cluster. However:

1. **Enterprise Edition only** - Conflicts with ADR 005 (Community default)
2. **Deprecated predecessor** - User Code Deployment is removed in next major version
3. **Spring complexity** - Spring-managed beans, proxies, and AOP don't serialize cleanly
4. **Ongoing maintenance** - Must update deployed classes with every code change

The dual-instance approach avoids these issues by keeping service-specific code entirely local.

## Future Considerations

### Why This Architecture Holds Even With Enterprise Edition

User Code Namespaces (Enterprise) solves the *technical* problem — classloading across services. It does not solve the *architectural* concerns that make dual-instance the better design regardless of edition:

- **Blast radius isolation**: A misbehaving Jet pipeline on a shared cluster affects all services. With embedded instances, it only affects the owning service.
- **Partial failure independence**: If the shared cluster goes down, each service continues processing local events. A single-cluster architecture turns a saga failure into a total system outage.
- **Hot-path performance**: In-process IMap access (11µs) vs. any network hop (270-340µs). UCN doesn't eliminate serialization and network overhead for distributed Jet execution.
- **Deployment independence**: Services can deploy and upgrade independently without coordinating shared cluster changes.
- **Data isolation by construction**: Separate instances make cross-service data access physically impossible, not just conventionally discouraged.

A single cluster with UCN would simplify configuration and eliminate the ~1-5ms republishing latency. For very small deployments (2-3 services) where operational simplicity outweighs isolation guarantees, it may be a reasonable trade-off. But for production-grade systems, the separation of local processing from shared communication is the right architectural boundary.

### Potential Optimizations

1. **Lazy client initialization**: Only create `hazelcastClient` when first saga event is processed
2. **Batch republishing**: Buffer multiple events and publish in batches to reduce round-trips
3. **Client connection pooling**: Share client instances across services in the same JVM (testing)

## References

- ADR 004: Six-Stage Pipeline Design (Jet pipeline architecture)
- ADR 005: Community Edition as Default (no Enterprise dependency)
- ADR 007: Choreographed Sagas (ITopic-based coordination)
- [Hazelcast User Code Namespaces](https://docs.hazelcast.com/hazelcast/5.6/clusters/user-code-namespaces)
- [Hazelcast User Code Deployment (deprecated)](https://docs.hazelcast.com/hazelcast/5.6/clusters/deploying-code-on-member)
- [Jet Job Submission](https://docs.hazelcast.com/hazelcast/5.6/pipelines/submitting-jobs)
