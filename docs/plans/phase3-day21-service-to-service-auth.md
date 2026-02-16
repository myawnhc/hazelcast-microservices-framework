# Phase 3, Day 21: Service-to-Service Authentication

## Context

Second day of **Area 5: Security**. Builds on Day 20's Spring Security & OAuth2 foundation to add JWT propagation through the API Gateway and HMAC-based service identity for ITopic saga events.

## Architecture

```
               Internet
                  |  (Bearer JWT)
         +--------v--------+
         |   API Gateway   |  ServiceTokenRelayFilter
         |  JWT Validation  |  Copies Authorization header
         |  (port 8080)    |  to downstream requests
         +--------+--------+
                  |  (Authorization: Bearer <token>)
     +------------+------------+
     |            |            |
 +---v---+  +----v----+  +----v----+  +----------+
 |Account|  |Inventory|  | Order   |  | Payment  |
 |:8081  |  |:8082    |  |:8083    |  |:8084     |
 +-------+  +---------+  +---------+  +----------+
     |            |            |            |
     +----------->+<-----------+<-----------+
          ITopic (shared Hazelcast cluster)
          Events wrapped in AuthenticatedEventEnvelope
          HMAC-SHA256 signature per event
```

## Design Decisions

1. **Gateway token relay** - GlobalFilter that copies `Authorization` header to downstream services when `framework.security.enabled=true`
2. **Authenticated event envelope** - events published to ITopic are wrapped in a GenericRecord envelope containing `sourceService`, `eventSignature` (HMAC-SHA256), and the original event as a nested `payload` GenericRecord
3. **Backward compatible** - when ServiceIdentity is not configured, events publish unwrapped (legacy behavior). Consumers handle both wrapped and unwrapped events.
4. **HMAC shared secret** - all services in the cluster share a secret key configured via `framework.security.service-identity.shared-secret`. Simple, practical, and educational.
5. **Warn-only validation** - signature verification logs warnings on failure but does not reject events (future enhancement: strict mode)

## Files Created

### Framework Core (`framework-core`)

| File | Purpose |
|------|---------|
| `security/identity/ServiceIdentityProperties.java` | Config: service name + shared secret |
| `security/identity/ServiceIdentity.java` | Holds identity, provides HMAC sign/verify |
| `security/identity/EventAuthenticator.java` | Wraps/unwraps events in authenticated envelope |
| `security/identity/ServiceIdentityAutoConfiguration.java` | Auto-config for ServiceIdentity + EventAuthenticator beans |

### API Gateway (`api-gateway`)

| File | Purpose |
|------|---------|
| `filter/ServiceTokenRelayFilter.java` | GlobalFilter that propagates JWT to downstream services |

### Tests

| File | Purpose |
|------|---------|
| `security/identity/ServiceIdentityTest.java` | HMAC signing/verification unit tests |
| `security/identity/EventAuthenticatorTest.java` | Envelope wrap/unwrap/verify tests |
| `security/identity/ServiceIdentityAutoConfigurationTest.java` | Auto-config conditional tests |
| `gateway/filter/ServiceTokenRelayFilterTest.java` | Token relay filter tests |

## Files Modified

| File | Change |
|------|--------|
| `AutoConfiguration.imports` | Register `ServiceIdentityAutoConfiguration` |
| `EventSourcingController.java` | Add `eventAuthenticator` to builder; wrap events in `republishToSharedCluster()` |
| `OutboxPublisher.java` | Accept optional `EventAuthenticator`; wrap events before ITopic publish |
| `InventorySagaListener.java` | Add optional `EventAuthenticator`; unwrap/verify in listeners |
| `PaymentSagaListener.java` | Add optional `EventAuthenticator`; unwrap/verify in listeners |
| `OrderSagaListener.java` | Add optional `EventAuthenticator`; unwrap/verify in listeners |

## Configuration

```yaml
framework:
  security:
    service-identity:
      name: order-service          # Unique service name
      shared-secret: change-me-in-production  # HMAC-SHA256 key (all services share this)
      enabled: true                # false = no signing/verification (default)
```

## Verification

1. `mvn install -pl framework-core -DskipTests`
2. `mvn clean test` (full suite - all existing tests must still pass)
3. When service-identity is not configured, behavior is unchanged (backward compatible)
4. When configured, events are wrapped in authenticated envelope
5. Saga listeners unwrap and verify transparently
