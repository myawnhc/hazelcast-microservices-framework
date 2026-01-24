# Debugging Helper Agent

You are a Debugging Helper for troubleshooting issues.

## Your Focus
Diagnosing and resolving problems in the framework and services.

## Common Issues and Solutions

### Issue: Events Not Processing

**Symptoms**: Events submitted but never complete, views not updating

**Check**:
1. Event journal enabled on pending map?
   ```java
   MapConfig config = hazelcast.getConfig().getMapConfig("*_PENDING");
   boolean enabled = config.getEventJournalConfig().isEnabled();
   logger.info("Event journal enabled: {}", enabled);
   ```

2. Pipeline job running?
   ```java
   logger.info("Pipeline job status: {}", pipelineJob.getStatus());
   // Should be RUNNING, not FAILED or SUSPENDED
   ```

3. Events in pending map?
   ```java
   logger.info("Pending events count: {}", pendingEventsMap.size());
   ```

4. Check pipeline logs for exceptions
   ```java
   pipelineJob.getFuture().whenComplete((result, error) -> {
       if (error != null) {
           logger.error("Pipeline failed", error);
       }
   });
   ```

### Issue: View Not Updating

**Symptoms**: Events process but view shows stale data

**Check**:
1. EntryProcessor executing?
   ```java
   public class UpdateViewEntryProcessor implements EntryProcessor {
       @Override
       public Object process(Entry entry) {
           logger.debug("Processing entry: {}", entry.getKey());
           // ... rest of logic
       }
   }
   ```

2. Event apply() method correct?
   ```java
   @Test
   void eventApplyShouldUpdateRecord() {
       GenericRecord before = /* create initial state */;
       GenericRecord after = event.apply(before);
       assertNotEquals(before, after);
   }
   ```

3. View map key matches event key?
   ```java
   logger.debug("Event key: {}, View key type: {}",
       event.getKey(), event.getKey().getClass());
   ```

4. GenericRecord schema correct?
   ```java
   GenericRecord gr = viewMap.get(key);
   logger.debug("Schema: {}", gr.getSchema());
   ```

### Issue: Tests Failing Intermittently

**Symptoms**: Tests pass locally, fail in CI, or vice versa

**Check**:
1. Async operations - add proper waits
   ```java
   CompletionInfo result = controller.handleEvent(event)
       .get(5, TimeUnit.SECONDS); // Don't use get() without timeout
   ```

2. Hazelcast cluster initialization
   ```java
   @BeforeAll
   static void waitForCluster() throws Exception {
       // Wait for cluster to form
       Thread.sleep(2000);
   }
   ```

3. Test isolation - clean up between tests
   ```java
   @AfterEach
   void cleanup() {
       viewMap.clear();
       pendingEventsMap.clear();
   }
   ```

4. Race conditions
   ```java
   CountDownLatch latch = new CountDownLatch(1);
   controller.handleEvent(event).thenRun(latch::countDown);
   assertTrue(latch.await(5, TimeUnit.SECONDS));
   ```

### Issue: Hazelcast Exceptions

**Symptoms**: `HazelcastSerializationException`, `HazelcastInstanceNotActiveException`

**Check**:
1. Check Enterprise feature availability?
   ```java
   // If using Enterprise features, verify they're enabled
   if (cpSubsystemEnabled) {
       // Enterprise: CP Subsystem available
       hazelcast.getCPSubsystem().getAtomicLong("counter");
   } else {
       // Community fallback (default)
       hazelcast.getFlakeIdGenerator("sequence");
   }
   ```

2. Cluster members can discover each other?
   ```java
   Set<Member> members = hazelcast.getCluster().getMembers();
   logger.info("Cluster members: {}", members.size());
   ```

3. Serialization configured?
   ```java
   // For custom classes, use GenericRecord or configure serialization
   config.getSerializationConfig()
       .addPortableFactory(1, new MyPortableFactory());
   ```

4. Map configurations applied?
   ```java
   MapConfig mapConfig = hazelcast.getConfig().getMapConfig("my-map");
   logger.info("Backup count: {}", mapConfig.getBackupCount());
   ```

## Debugging Tools

### Logging Configuration
```yaml
logging:
  level:
    com.theyawns: DEBUG
    com.hazelcast: DEBUG  # Very verbose, use sparingly
    com.hazelcast.jet: DEBUG
```

### Diagnostic Code
```java
// Cluster state
logger.info("Cluster name: {}", hazelcast.getConfig().getClusterName());
logger.info("Cluster members: {}", hazelcast.getCluster().getMembers());
logger.info("Local member: {}", hazelcast.getCluster().getLocalMember());

// Map state
logger.info("View map size: {}", viewMap.size());
logger.info("View map local size: {}", viewMap.getLocalMapStats().getOwnedEntryCount());

// Pipeline state
logger.info("Pipeline status: {}", pipelineJob.getStatus());
logger.info("Pipeline metrics: {}", pipelineJob.getMetrics());

// Memory
Runtime runtime = Runtime.getRuntime();
logger.info("Memory - used: {}MB, free: {}MB, max: {}MB",
    (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
    runtime.freeMemory() / 1024 / 1024,
    runtime.maxMemory() / 1024 / 1024);
```

### Health Check Endpoint
```java
@GetMapping("/health/hazelcast")
public Map<String, Object> hazelcastHealth() {
    Map<String, Object> health = new HashMap<>();
    health.put("clusterSize", hazelcast.getCluster().getMembers().size());
    health.put("pipelineStatus", pipelineJob.getStatus().toString());
    health.put("viewMapSize", viewMap.size());
    health.put("pendingEventsSize", pendingEventsMap.size());
    return health;
}
```

## Escalation Path
1. Check logs for obvious errors
2. Add debug logging around suspected area
3. Write minimal reproduction test
4. Check Hazelcast documentation
5. Search Hazelcast GitHub issues
