# VectorCollection Migration Plan

## Context

The current vector store Enterprise implementation (`SimpleVectorStoreService`) uses two IMaps with brute-force O(n) cosine similarity search. While functional, it doesn't demonstrate Hazelcast's native `VectorCollection` data structure (Enterprise, available since 5.5, HNSW-indexed). This migration:

1. Preserves the IMap implementation on a branch
2. Creates a new `framework-enterprise` Maven module (for this and future Enterprise features)
3. Replaces `SimpleVectorStoreService` with `HazelcastVectorStoreService` using native `VectorCollection` API — **direct imports, no reflection**
4. Updates docs, blogs, and tests

---

## Step 1: Preserve IMap Implementation on Branch

Create `feature/imap-vector-store` branch from current `main` and push it, preserving the IMap-based approach.

---

## Step 2: Create `framework-enterprise` Maven Module

### 2a. Root POM Changes (`pom.xml`)

Add `framework-enterprise` as a managed dependency and gate it behind a Maven profile:

```xml
<!-- In <dependencyManagement> -->
<dependency>
    <groupId>com.theyawns</groupId>
    <artifactId>framework-enterprise</artifactId>
    <version>${project.version}</version>
</dependency>

<!-- New profile -->
<profiles>
    <profile>
        <id>enterprise</id>
        <modules>
            <module>framework-enterprise</module>
        </modules>
    </profile>
</profiles>
```

Add `hazelcast-enterprise` to `<dependencyManagement>` (alongside the existing Community entry):

```xml
<dependency>
    <groupId>com.hazelcast</groupId>
    <artifactId>hazelcast-enterprise</artifactId>
    <version>${hazelcast.version}</version>
</dependency>
```

Add the Hazelcast Enterprise Maven repository:

```xml
<repositories>
    <repository>
        <id>hazelcast-enterprise</id>
        <url>https://repository.hazelcast.com/release/</url>
    </repository>
</repositories>
```

### 2b. New Module POM (`framework-enterprise/pom.xml`)

```
Parent: hazelcast-microservices-framework
ArtifactId: framework-enterprise
Dependencies:
  - framework-core (compile)
  - hazelcast-enterprise (compile — provides VectorCollection class)
  - spring-boot-starter (compile)
  - spring-boot-starter-test (test)
```

### 2c. Module Directory Structure

```
framework-enterprise/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/theyawns/framework/
    │   │   └── vectorstore/
    │   │       ├── HazelcastVectorStoreService.java
    │   │       └── EnterpriseVectorStoreAutoConfiguration.java
    │   └── resources/META-INF/spring/
    │       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/
        └── java/com/theyawns/framework/
            └── vectorstore/
                └── HazelcastVectorStoreServiceTest.java
```

---

## Step 3: Implement `HazelcastVectorStoreService`

**File**: `framework-enterprise/src/main/java/.../vectorstore/HazelcastVectorStoreService.java`

Direct imports of Enterprise classes (no reflection):
- `com.hazelcast.vector.VectorCollection`
- `com.hazelcast.vector.VectorDocument`
- `com.hazelcast.vector.VectorValues`
- `com.hazelcast.vector.SearchOptions`
- `com.hazelcast.vector.SearchResult`

Key design:
- `VectorCollection<String, String>` — String keys, String values (JSON-serialized metadata)
- Configure via `VectorCollectionConfig` + `VectorIndexConfig` added to Hazelcast config in constructor
- Uses `maxConnections` and `efConstruction` from `VectorStoreProperties` (already defined, currently reserved for this purpose)
- `storeEmbedding()` -> `VectorDocument.of(jsonMetadata, VectorValues.of(embedding))` + `collection.putAsync()`
- `findSimilar()` -> `collection.searchAsync(VectorValues.of(query), SearchOptions.builder().limit(limit).build())`
- `findSimilarById()` -> get stored vector, then call `findSimilar()`, exclude self
- Block on `CompletionStage` results (keeps `VectorStoreService` interface synchronous)
- `getImplementationType()` returns `"Hazelcast VectorCollection (Enterprise)"`

---

## Step 4: Enterprise Auto-Configuration

**File**: `framework-enterprise/src/main/java/.../vectorstore/EnterpriseVectorStoreAutoConfiguration.java`

```java
@AutoConfiguration
@AutoConfigureBefore(VectorStoreAutoConfiguration.class)
@ConditionalOnBean(HazelcastInstance.class)
@EnableConfigurationProperties(VectorStoreProperties.class)
public class EnterpriseVectorStoreAutoConfiguration {

    @Bean
    @ConditionalOnEnterpriseFeature(EnterpriseFeature.VECTOR_STORE)
    public VectorStoreService hazelcastVectorStoreService(
            HazelcastInstance hazelcastInstance,
            VectorStoreProperties properties) {
        return new HazelcastVectorStoreService(hazelcastInstance, properties);
    }
}
```

Register in `framework-enterprise/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

**Result**: If Enterprise module is on classpath + Enterprise detected -> `HazelcastVectorStoreService` is created -> `framework-core`'s `@ConditionalOnMissingBean` skips NoOp. If module absent -> NoOp fallback activates as before.

---

## Step 5: Update `framework-core`

### 5a. Delete `SimpleVectorStoreService.java`

Remove from `framework-core` entirely (preserved on the `feature/imap-vector-store` branch).

### 5b. Update `VectorStoreAutoConfiguration.java`

Update JavaDoc to reference `HazelcastVectorStoreService` (in `framework-enterprise`) instead of `SimpleVectorStoreService`. Logic unchanged — the `@ConditionalOnMissingBean` fallback already works correctly.

### 5c. Update `NoOpVectorStoreService.java`

Update JavaDoc `@see` reference from `SimpleVectorStoreService` to `HazelcastVectorStoreService`.

### 5d. Update `VectorStoreService.java` interface

Update JavaDoc listing the two implementations.

### 5e. Add new properties to `VectorStoreProperties.java`

- `metric` (String, default: `"COSINE"`) — distance metric (COSINE, DOT, EUCLIDEAN)
- `indexName` (String, default: `"default"`) — HNSW index name

Existing `maxConnections` and `efConstruction` are already present and reserved for HNSW use.

---

## Step 6: Update Service POMs (Profile-gated)

**File**: `inventory-service/pom.xml` (and any other services using vector store)

Add within a `<profiles><profile id="enterprise">` block:
```xml
<dependency>
    <groupId>com.theyawns</groupId>
    <artifactId>framework-enterprise</artifactId>
</dependency>
```

This ensures community builds are unaffected.

---

## Step 7: Tests

### 7a. Delete `SimpleVectorStoreServiceTest.java` from `framework-core`

Preserved on the `feature/imap-vector-store` branch.

### 7b. New `HazelcastVectorStoreServiceTest.java` in `framework-enterprise`

- `@EnabledIf("isVectorCollectionAvailable")` — skips if Enterprise classes aren't functional
- Real embedded Hazelcast Enterprise instance (`@TestInstance(PER_CLASS)`)
- Tests: store embedding, find similar, find similar by ID, metadata round-trip, isAvailable, getImplementationType

### 7c. Update `VectorStoreAutoConfigurationTest.java` in `framework-core`

- Remove/update any assertions that reference `SimpleVectorStoreService`
- Verify NoOp fallback still works (this is the primary path in community builds)

---

## Step 8: Update Documentation

### 8a. Blog Post: `docs/blog/06-vector-similarity-search-with-hazelcast.md`

Major rewrite:
- Replace IMap architecture description with VectorCollection / HNSW explanation
- Update code examples to show `VectorDocument`, `VectorValues`, `SearchOptions`
- Mention IMap approach preserved on branch for educational comparison
- Highlight HNSW O(log n) vs brute-force O(n)

### 8b. Demo Walkthrough: `docs/demo/demo-walkthrough.md`

Update Scenario 4 to reflect VectorCollection-based implementation.

---

## Files Unchanged

| File | Reason |
|------|--------|
| `VectorStoreService.java` (interface) | API contract unchanged (only JavaDoc update) |
| `TextEmbeddingGenerator.java` | Embedding generation unchanged |
| `SimilarityResult.java` | Result record unchanged |
| `NoOpVectorStoreServiceTest.java` | Community tests unchanged |
| `TextEmbeddingGeneratorTest.java` | Generator tests unchanged |
| `InventoryController.java` | Uses interface, no changes |
| `InventoryService.java` | Uses interface, no changes |
| `SimilarProductsResponse.java` | DTO unchanged |
| `EditionDetector.java` | Already checks VectorCollection via Class.forName |

---

## Verification

1. **Branch preserved**: `git log feature/imap-vector-store` confirms IMap code exists
2. **Community build**: `mvn clean install` (default, no profile) — builds all modules except `framework-enterprise`. All existing tests pass. `SimpleVectorStoreServiceTest` is gone from core but that's fine — it was the IMap test.
3. **Enterprise build**: `mvn clean install -Penterprise` — builds `framework-enterprise` too. `HazelcastVectorStoreServiceTest` runs (if Enterprise license available).
4. **Full test suite**: `mvn test` — all tests pass (minus the deleted IMap test, net gain of Enterprise tests)
5. **Runtime integration**: Start inventory-service with `-Penterprise` profile -> `GET /api/products/{id}/similar?limit=5` uses VectorCollection. Without profile -> NoOp fallback.
