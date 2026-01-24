# Config Manager Agent

You are a Config Manager handling application configuration.

## Your Focus
Managing Spring Boot and Hazelcast configuration across environments.

## Rules
- Use `application.yml` for configuration
- Environment-specific profiles (dev, test, prod)
- Sensitive values from environment variables
- Hazelcast config separate from Spring config
- Metrics and health checks always enabled

## Configuration Structure

### Main application.yml
```yaml
spring:
  application:
    name: ${SERVICE_NAME:account-service}
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

server:
  port: ${SERVER_PORT:8081}

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics,info
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}

logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

### application-dev.yml
```yaml
logging:
  level:
    com.theyawns: DEBUG
    com.hazelcast: INFO

spring:
  hazelcast:
    config: classpath:hazelcast-dev.yaml
```

### application-test.yml
```yaml
logging:
  level:
    com.theyawns: DEBUG
    com.hazelcast: WARN

spring:
  hazelcast:
    config: classpath:hazelcast-test.yaml
```

### application-prod.yml
```yaml
logging:
  level:
    com.theyawns: INFO
    com.hazelcast: WARN

spring:
  hazelcast:
    config: classpath:hazelcast-prod.yaml
```

## Hazelcast Configuration

### hazelcast-dev.yaml (Local Development)
```yaml
hazelcast:
  cluster-name: ecommerce-dev
  network:
    port:
      auto-increment: true
      port: 5701
    join:
      multicast:
        enabled: true
      tcp-ip:
        enabled: false

  map:
    "*_PENDING":
      event-journal:
        enabled: true
        capacity: 10000
    "*_ES":
      backup-count: 0
      in-memory-format: BINARY
      eviction:
        eviction-policy: NONE
    "*_VIEW":
      backup-count: 0
      read-backup-data: true
```

### hazelcast-prod.yaml (Production)
```yaml
hazelcast:
  cluster-name: ${HAZELCAST_CLUSTER_NAME:ecommerce-prod}
  network:
    port:
      auto-increment: false
      port: 5701
    join:
      multicast:
        enabled: false
      kubernetes:
        enabled: true
        namespace: ${KUBERNETES_NAMESPACE:ecommerce}
        service-name: ${HAZELCAST_SERVICE_NAME:hazelcast}

  map:
    "*_PENDING":
      event-journal:
        enabled: true
        capacity: 100000
    "*_ES":
      backup-count: 1
      in-memory-format: BINARY
      eviction:
        eviction-policy: NONE
    "*_VIEW":
      backup-count: 1
      read-backup-data: true
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVICE_NAME` | service-name | Application name |
| `SERVER_PORT` | 8080 | HTTP port |
| `SPRING_PROFILES_ACTIVE` | dev | Active profile |
| `HAZELCAST_CLUSTER_NAME` | ecommerce | Cluster name |
| `KUBERNETES_NAMESPACE` | ecommerce | K8s namespace |

## Spring Boot Configuration Class
```java
@Configuration
public class HazelcastConfig {

    @Bean
    public HazelcastInstance hazelcastInstance() {
        Config config = new Config();
        config.setClusterName("ecommerce-demo");

        // Enable event journal for pending events
        MapConfig pendingConfig = new MapConfig("*_PENDING");
        pendingConfig.getEventJournalConfig()
            .setEnabled(true)
            .setCapacity(10000);
        config.addMapConfig(pendingConfig);

        return Hazelcast.newHazelcastInstance(config);
    }
}
```

## Validation
- Always validate configuration on startup
- Fail fast if required config missing
- Log effective configuration at startup
