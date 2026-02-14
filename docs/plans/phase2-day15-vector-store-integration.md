# Vector Store Integration Plan (Phase 2 - Day 15)

## Context

Phase 2 Day 0 (Edition Configuration) is complete. The edition detection system (`EditionDetector`, `EditionProperties`, `@ConditionalOnEnterpriseFeature`/`@ConditionalOnCommunityFallback`) is fully operational. Day 15 adds the first Enterprise-optional feature: product similarity search via a Vector Store abstraction.

**Goal**: Product recommendations via `GET /api/products/{id}/similar`. Enterprise Edition gets IMap-based vector similarity; Community Edition returns empty results gracefully.

**Key constraint**: The project uses `hazelcast:5.6.0` (Community jar). We cannot compile against Enterprise-only APIs like `VectorCollection`. The Enterprise implementation uses `IMap<String, float[]>` with brute-force cosine similarity. This is appropriate for a demo/educational project and exercises the full feature-flag plumbing. JavaDoc documents that production would use Hazelcast Enterprise `VectorCollection` with HNSW indexing.

---

## Files to Create

### framework-core (7 source + 3 test files)

All source under `framework-core/src/main/java/com/theyawns/framework/vectorstore/`:

#### 1. `SimilarityResult.java` — Record
- `record SimilarityResult(String id, float score, Map<String,Object> metadata)`

#### 2. `VectorStoreService.java` — Interface
- Methods: `storeEmbedding(String id, float[] embedding, Map<String,Object> metadata)`, `findSimilar(float[] queryVector, int limit)`, `findSimilarById(String id, int limit)`, `isAvailable()`, `getImplementationType()`

#### 3. `VectorStoreProperties.java`
- `@ConfigurationProperties(prefix = "framework.vectorstore")`
- Fields: `collectionName` (default: `"product-vectors"`), `dimension` (default: `128`), `maxConnections` (default: `16`), `efConstruction` (default: `200`)

#### 4. `TextEmbeddingGenerator.java` — Static utility
- `static float[] generateEmbedding(String text, int dimension)` — deterministic hash-based tokenization + normalization
- No Spring bean, no ML dependencies

#### 5. `SimpleVectorStoreService.java` — Enterprise path
- `@ConditionalOnEnterpriseFeature(EnterpriseFeature.VECTOR_STORE)`
- Uses `IMap<String, float[]>` for embeddings + `IMap<String, Map<String,Object>>` for metadata
- Brute-force cosine similarity search
- JavaDoc documents that production would use `VectorCollection` with HNSW

#### 6. `NoOpVectorStoreService.java` — Community fallback
- `@ConditionalOnCommunityFallback(EnterpriseFeature.VECTOR_STORE)`
- `isAvailable()` -> `false`, `findSimilar*` -> empty list, `storeEmbedding` -> no-op

#### 7. `VectorStoreAutoConfiguration.java`
- `@Configuration`, `@EnableConfigurationProperties(VectorStoreProperties.class)`, `@ConditionalOnBean(HazelcastInstance.class)`
- Creates `SimpleVectorStoreService` or `NoOpVectorStoreService` with `@ConditionalOnMissingBean`

Tests under `framework-core/src/test/java/com/theyawns/framework/vectorstore/`:

#### 8. `TextEmbeddingGeneratorTest.java`
- Deterministic output, different text -> different vectors, normalization, dimension correctness

#### 9. `SimpleVectorStoreServiceTest.java`
- Store + retrieve, cosine similarity correctness, find by ID, empty store

#### 10. `NoOpVectorStoreServiceTest.java`
- isAvailable=false, findSimilar returns empty, storeEmbedding no-op

### inventory-service (1 new + 2 modified files)

#### 11. `SimilarProductsResponse.java` — New DTO
- Path: `inventory-service/src/main/java/com/theyawns/ecommerce/inventory/dto/SimilarProductsResponse.java`
- Fields: `productId`, `vectorStoreAvailable`, `implementation`, `message`, `similarProducts` (List<ProductDTO>)

#### 12. `InventoryController.java` — Add endpoint
- Add `VectorStoreService` constructor param
- Add `GET /{productId}/similar?limit=5` endpoint returning `SimilarProductsResponse`
- OpenAPI annotations documenting Enterprise requirement

#### 13. `InventoryService.java` — Store embeddings on product creation
- Add `VectorStoreService` + `VectorStoreProperties` constructor params
- In `createProduct()`: after event completes, generate embedding via `TextEmbeddingGenerator` and store

### inventory-service test:

#### 14. `InventoryControllerSimilarProductsTest.java`
- Tests with available and unavailable vector store, invalid product, custom limit

---

## Files to Modify

#### 15. `AutoConfiguration.imports`
- Add: `com.theyawns.framework.vectorstore.VectorStoreAutoConfiguration`

#### 16. `inventory-service/src/main/resources/application.yml`
- Add:
  ```yaml
  framework:
    vectorstore:
      dimension: 128
      collection-name: product-vectors
  ```

---

## Implementation Order

1. `SimilarityResult` record (no deps)
2. `VectorStoreService` interface
3. `VectorStoreProperties`
4. `TextEmbeddingGenerator`
5. `SimpleVectorStoreService`
6. `NoOpVectorStoreService`
7. `VectorStoreAutoConfiguration` + register in imports
8. `SimilarProductsResponse` DTO
9. Modify `InventoryController` (add endpoint)
10. Modify `InventoryService` (embed on create)
11. Update `inventory-service/application.yml`
12. All tests

---

## Critical Files Reference

| Existing File | Reuse |
|---------------|-------|
| `framework-core/.../edition/ConditionalOnEnterpriseFeature.java` | Annotate Enterprise bean |
| `framework-core/.../edition/ConditionalOnCommunityFallback.java` | Annotate fallback bean |
| `framework-core/.../edition/EditionDetector.EnterpriseFeature.VECTOR_STORE` | Feature enum value |
| `framework-core/.../edition/EditionAutoConfiguration.java` | Pattern for auto-config |
| `inventory-service/.../controller/InventoryController.java` | Extend with `/similar` |
| `inventory-service/.../service/InventoryService.java` | Add embedding on create |

---

## Verification

1. `mvn clean test -pl framework-core` — all existing + new tests pass
2. `mvn clean test -pl inventory-service` — all existing + new tests pass
3. `mvn clean package -DskipTests` — full project builds
4. Start inventory-service -> logs show vector store init (Enterprise or Community)
5. `POST /api/products` -> product created, embedding stored (Enterprise path)
6. `GET /api/products/{id}/similar` -> similar products (Enterprise) or empty list + message (Community)
7. `/actuator/info` -> shows vector-store feature status
