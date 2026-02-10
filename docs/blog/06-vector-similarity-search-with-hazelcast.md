# Vector Similarity Search with Hazelcast

*Part 6 of 6 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

In the previous articles, we built an event sourcing framework ([Part 1](01-event-sourcing-with-hazelcast-introduction.md)), a Jet pipeline ([Part 2](02-building-event-pipeline-with-hazelcast-jet.md)), materialized views ([Part 3](03-materialized-views-for-fast-queries.md)), an observability stack ([Part 4](04-observability-in-event-sourced-systems.md)), and a choreographed saga pattern ([Part 5](05-saga-pattern-for-distributed-transactions.md)).

Now we add a modern capability: **"Find me products similar to this one."**

Similarity search is everywhere — Netflix recommends shows, Spotify suggests playlists, Amazon shows "customers also bought." Under the hood, these features use **vector embeddings**: numerical representations of items where similar items are close together in vector space.

In this article, we'll implement vector similarity search using Hazelcast Enterprise's native `VectorCollection` with HNSW indexing, covering:

- How text becomes a vector embedding
- HNSW indexing for O(log n) approximate nearest-neighbor search
- Hazelcast's `VectorCollection` API for storing and searching vectors
- The Community/Enterprise fallback pattern that lets the feature degrade gracefully

---

## What Are Vector Embeddings?

A vector embedding is a fixed-size array of floating-point numbers that represents an item's characteristics. Items that are semantically similar have vectors that point in similar directions.

```
"Gaming Laptop"   → [0.82, -0.15, 0.44, 0.21, ...]   128 dimensions
"Gaming Desktop"  → [0.79, -0.12, 0.41, 0.25, ...]   ← similar to laptop
"Running Shoes"   → [-0.33, 0.67, -0.21, 0.55, ...]   ← very different
```

The "Gaming Laptop" and "Gaming Desktop" vectors are close because they share semantic meaning. "Running Shoes" points in a completely different direction.

### How We Generate Embeddings

In production, you'd use a proper embedding model — OpenAI's text-embedding-ada-002, Sentence-Transformers, or similar. For our demo, we use a deterministic hash-based generator that needs no external dependencies:

```java
public final class TextEmbeddingGenerator {

    public static float[] generateEmbedding(final String text, final int dimension) {
        final float[] vector = new float[dimension];
        final String normalized = text.toLowerCase().trim();
        final String[] tokens = normalized.split("\\s+");

        for (final String token : tokens) {
            final byte[] hash = sha256(token);
            for (int i = 0; i < dimension; i++) {
                // Distribute hash bytes across dimensions
                final int byteIndex = i % hash.length;
                vector[i] += (hash[byteIndex] & 0xFF) / 127.5f - 1.0f;
            }
        }

        normalize(vector);  // L2-normalize for cosine similarity
        return vector;
    }
}
```

The algorithm:

1. **Tokenize**: Split text into words
2. **Hash**: SHA-256 each token for deterministic, well-distributed bytes
3. **Accumulate**: Map hash bytes to vector dimensions
4. **Normalize**: L2-normalize so cosine similarity equals the dot product

This generator is deterministic — the same text always produces the same vector. "Gaming Laptop" always maps to the same point in 128-dimensional space. Products with shared words ("Gaming") will have overlapping contributions, making their vectors more similar.

---

## HNSW: Fast Approximate Nearest-Neighbor Search

A brute-force approach to similarity search compares the query vector against every stored vector — O(n) per query. That's fine for hundreds of items, but at scale you need something faster.

**HNSW** (Hierarchical Navigable Small World) is an indexing algorithm that builds a multi-layer graph over the vector space. Each layer is a "skip list" of proximity connections:

- **Top layers**: Sparse, long-range connections for coarse navigation
- **Bottom layers**: Dense, short-range connections for precise neighbors

A search starts at the top layer, greedily navigating toward the query vector, then descends to finer layers. The result is **O(log n)** approximate nearest-neighbor search with high recall.

### HNSW Parameters

| Parameter | What It Controls | Default |
|-----------|-----------------|---------|
| `maxDegree` (M) | Max edges per node in the graph | 16 |
| `efConstruction` | Beam width during index build (higher = better recall, slower build) | 200 |
| `metric` | Distance function: COSINE, DOT, or EUCLIDEAN | COSINE |

Higher `maxDegree` and `efConstruction` improve recall at the cost of memory and build time. For a product catalog, the defaults work well.

---

## The VectorStoreService Interface

The vector store is exposed through a clean interface:

```java
public interface VectorStoreService {

    void storeEmbedding(String id, float[] embedding, Map<String, Object> metadata);

    List<SimilarityResult> findSimilar(float[] queryVector, int limit);

    List<SimilarityResult> findSimilarById(String id, int limit);

    boolean isAvailable();

    String getImplementationType();
}
```

The `SimilarityResult` is a simple record:

```java
public record SimilarityResult(String id, float score, Map<String, Object> metadata) {}
```

The interface has two implementations:

| Implementation | Edition | Module | Behavior |
|---------------|---------|--------|----------|
| `HazelcastVectorStoreService` | Enterprise | `framework-enterprise` | VectorCollection with HNSW indexing |
| `NoOpVectorStoreService` | Community | `framework-core` | Returns empty results silently |

---

## The Enterprise Implementation

`HazelcastVectorStoreService` uses Hazelcast Enterprise's native `VectorCollection` data structure, which provides built-in HNSW indexing:

```java
public class HazelcastVectorStoreService implements VectorStoreService {

    private final VectorCollection<String, String> collection;
    private final String indexName;

    public HazelcastVectorStoreService(HazelcastInstance hazelcast,
                                       VectorStoreProperties properties) {
        this.indexName = properties.getIndexName();

        Metric metric = Metric.valueOf(properties.getMetric().toUpperCase());

        VectorIndexConfig indexConfig = new VectorIndexConfig()
                .setName(indexName)
                .setDimension(properties.getDimension())
                .setMetric(metric)
                .setMaxDegree(properties.getMaxConnections())
                .setEfConstruction(properties.getEfConstruction());

        VectorCollectionConfig collectionConfig =
                new VectorCollectionConfig(properties.getCollectionName())
                        .addVectorIndexConfig(indexConfig);

        hazelcast.getConfig().addVectorCollectionConfig(collectionConfig);

        this.collection = VectorCollection.getCollection(
                hazelcast, properties.getCollectionName());
    }
}
```

### Storing Embeddings

Documents are stored using Hazelcast's `VectorDocument` — a value (JSON metadata string) paired with a vector:

```java
@Override
public void storeEmbedding(String id, float[] embedding,
                           Map<String, Object> metadata) {
    String jsonMetadata = metadataToJson(metadata);
    VectorDocument<String> document = VectorDocument.of(
            jsonMetadata,
            VectorValues.of(indexName, embedding)
    );
    collection.putAsync(id, document).toCompletableFuture().join();
}
```

### Searching for Similar Items

Search is a single async call to the HNSW index:

```java
@Override
public List<SimilarityResult> findSimilar(float[] queryVector, int limit) {
    SearchResults<String, String> searchResults = collection.searchAsync(
            VectorValues.of(indexName, queryVector),
            SearchOptions.builder()
                    .limit(limit)
                    .includeValue()
                    .build()
    ).toCompletableFuture().join();

    List<SimilarityResult> results = new ArrayList<>();
    for (SearchResult<String, String> hit : searchResults) {
        Map<String, Object> metadata = jsonToMetadata(hit.getValue());
        results.add(new SimilarityResult(hit.getKey(), hit.getScore(), metadata));
    }
    return results;
}
```

### Complexity Comparison

| Approach | Search Complexity | Index Build | Memory |
|----------|-------------------|-------------|--------|
| Brute-force (IMap) | O(n) per query | O(1) | O(n) |
| HNSW (VectorCollection) | O(log n) per query | O(n log n) | O(n * M) |

For a catalog of 1,000 products, the difference is negligible. For 1,000,000 products, HNSW is orders of magnitude faster.

> **Historical note:** An earlier version of this implementation used `IMap<String, float[]>` with brute-force cosine similarity. That approach is preserved on the `feature/imap-vector-store` branch for educational comparison.

### Configuration

All HNSW parameters are configurable via Spring properties:

```yaml
framework:
  vectorstore:
    collection-name: product-vectors
    dimension: 128
    max-connections: 16     # HNSW maxDegree (M)
    ef-construction: 200    # HNSW build beam width
    metric: COSINE          # COSINE, DOT, or EUCLIDEAN
    index-name: default     # HNSW index name
```

---

## The Community Fallback

Not everyone has Hazelcast Enterprise. The Community Edition fallback does nothing — gracefully:

```java
@Service
@ConditionalOnCommunityFallback(EnterpriseFeature.VECTOR_STORE)
public class NoOpVectorStoreService implements VectorStoreService {

    public NoOpVectorStoreService() {
        logger.info("NoOpVectorStoreService initialized (Community Edition fallback). " +
                "Vector similarity search is not available.");
    }

    @Override
    public void storeEmbedding(String id, float[] embedding,
                                Map<String, Object> metadata) {
        // No-op: embeddings are silently discarded
    }

    @Override
    public List<SimilarityResult> findSimilar(float[] queryVector, int limit) {
        return List.of();
    }

    @Override
    public List<SimilarityResult> findSimilarById(String id, int limit) {
        return List.of();
    }

    @Override
    public boolean isAvailable() { return false; }

    @Override
    public String getImplementationType() {
        return "No-Op (Community Edition)";
    }
}
```

Embeddings are silently discarded. Searches return empty lists. The application runs normally — the similarity feature simply isn't available.

---

## Module Architecture

The Enterprise vector store lives in its own Maven module, `framework-enterprise`, which is only built when the `-Penterprise` profile is active:

```
framework-core/                          (always built)
  └── VectorStoreService (interface)
  └── NoOpVectorStoreService (Community fallback)
  └── VectorStoreAutoConfiguration (@ConditionalOnMissingBean safety net)

framework-enterprise/                    (only with -Penterprise)
  └── HazelcastVectorStoreService (Enterprise VectorCollection)
  └── EnterpriseVectorStoreAutoConfiguration (@AutoConfigureBefore)
```

The auto-configuration ordering ensures the Enterprise bean is registered first when present:

```
Application starts
    │
    ├── framework-enterprise on classpath?
    │   │
    │   ├── YES → EnterpriseVectorStoreAutoConfiguration runs first
    │   │         └── @ConditionalOnEnterpriseFeature matches?
    │   │             ├── YES → HazelcastVectorStoreService created
    │   │             └── NO  → fall through to core
    │   │
    │   └── NO  → skip to core
    │
    └── VectorStoreAutoConfiguration in framework-core
        └── @ConditionalOnMissingBean(VectorStoreService.class)
            ├── Bean exists → skip (Enterprise is active)
            └── No bean → create NoOpVectorStoreService
```

### Building

```bash
# Community build (default) — framework-enterprise is NOT built
mvn clean install

# Enterprise build — includes framework-enterprise module
mvn clean install -Penterprise
```

---

## Edition-Aware Bean Selection

The magic is in two custom annotations: `@ConditionalOnEnterpriseFeature` and `@ConditionalOnCommunityFallback`. These work with the framework's `EditionDetector` to choose the right implementation at startup:

```
EditionDetector checks for Enterprise license
    │
    ├── License found + VectorCollection class on classpath
    │   └── @ConditionalOnEnterpriseFeature matches
    │   └── HazelcastVectorStoreService created
    │
    └── No license OR no Enterprise classes
        └── @ConditionalOnMissingBean fallback
        └── NoOpVectorStoreService created
```

As a safety net, `VectorStoreAutoConfiguration` provides a `@ConditionalOnMissingBean` fallback:

```java
@Configuration
@EnableConfigurationProperties(VectorStoreProperties.class)
@ConditionalOnBean(HazelcastInstance.class)
public class VectorStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VectorStoreService.class)
    public VectorStoreService vectorStoreService() {
        return new NoOpVectorStoreService();  // Safety net
    }
}
```

If the enterprise module isn't on the classpath (default community build), the no-op implementation is used. The application never crashes due to a missing vector store bean.

---

## The REST Endpoint

The Inventory Service exposes a "find similar products" endpoint:

```java
@GetMapping("/{productId}/similar")
public ResponseEntity<SimilarProductsResponse> findSimilarProducts(
        @PathVariable String productId,
        @RequestParam(defaultValue = "5") int limit) {

    // Verify product exists
    if (!productService.productExists(productId)) {
        return ResponseEntity.notFound().build();
    }

    // Check if vector store is available
    if (!vectorStoreService.isAvailable()) {
        return ResponseEntity.ok(new SimilarProductsResponse(
            productId, false,
            vectorStoreService.getImplementationType(),
            "Vector similarity search requires Enterprise Edition. " +
                "Running on Community Edition — no similar products available.",
            List.of()
        ));
    }

    // Perform similarity search
    List<SimilarityResult> results = vectorStoreService.findSimilarById(productId, limit);

    // Convert to product DTOs
    List<ProductDTO> similarProducts = new ArrayList<>();
    for (SimilarityResult result : results) {
        productService.getProduct(result.id())
            .ifPresent(product -> similarProducts.add(product.toDTO()));
    }

    return ResponseEntity.ok(new SimilarProductsResponse(
        productId, true,
        vectorStoreService.getImplementationType(),
        "Found " + similarProducts.size() + " similar products",
        similarProducts
    ));
}
```

The response includes metadata about the vector store implementation, so the client knows whether it's getting real similarity results or empty Community Edition results:

```json
{
  "productId": "prod-123",
  "vectorStoreAvailable": true,
  "implementation": "Hazelcast VectorCollection (Enterprise)",
  "message": "Found 3 similar products",
  "similarProducts": [
    {"productId": "prod-456", "name": "Gaming Desktop", "sku": "DESKTOP-001", ...},
    {"productId": "prod-789", "name": "Gaming Monitor", "sku": "MONITOR-001", ...},
    {"productId": "prod-012", "name": "Gaming Keyboard", "sku": "KEYBOARD-001", ...}
  ]
}
```

On Community Edition:

```json
{
  "productId": "prod-123",
  "vectorStoreAvailable": false,
  "implementation": "No-Op (Community Edition)",
  "message": "Vector similarity search requires Enterprise Edition. Running on Community Edition — no similar products available.",
  "similarProducts": []
}
```

---

## The Edition Fallback Pattern

The vector store demonstrates a pattern we use throughout the framework for Enterprise-only features:

```
1. Define an interface        (VectorStoreService)
2. Enterprise implementation  (HazelcastVectorStoreService in framework-enterprise)
3. Community fallback         (NoOpVectorStoreService in framework-core)
4. Auto-configuration         (@AutoConfigureBefore + @ConditionalOnMissingBean)
5. Runtime check              (isAvailable() in controller)
```

This pattern ensures:

- **Community Edition always works**: No crashes, no missing beans, no exceptions
- **Enterprise features activate automatically**: Add the module + license key
- **The API is consistent**: Same endpoint, same response shape, different content
- **Callers don't need to know**: The service layer calls `vectorStoreService.findSimilarById()` regardless of edition. The interface handles the rest.

This is the same pattern you'd use for any Enterprise-only feature — CP Subsystem for stronger consistency, HD Memory for large datasets, or TLS for encryption. Define the interface, implement both paths, let the edition detector choose.

---

## Trying It Out

With the Docker stack running:

```bash
# Load sample products (creates embeddings automatically)
./scripts/load-sample-data.sh

# Find products similar to a known product
curl http://localhost:8082/api/products/<product-id>/similar?limit=5

# Or run the demo scenario
./scripts/demo-scenarios.sh 4
```

Demo scenario 4 automatically detects the edition, looks up a product, calls the similarity endpoint, and displays the results with appropriate messaging for either edition.

---

## Where to Go From Here

### Production Embedding Models

Replace `TextEmbeddingGenerator` with a real embedding model for production use:

- **OpenAI text-embedding-ada-002**: General-purpose, 1536 dimensions
- **Sentence-Transformers**: Open-source, various sizes (384-1024 dimensions)
- **Cohere Embed**: Multi-language support

The `VectorStoreService` interface doesn't care how embeddings are generated — just pass the `float[]` to `storeEmbedding()`.

### Hybrid Search

Combine vector similarity with traditional filters:

```
"Find products similar to this laptop, but only in the Electronics category and under $1000"
```

This would combine a VectorCollection search for semantic similarity with IMap predicate queries for attribute filtering.

### Multi-Index Collections

Hazelcast's `VectorCollection` supports multiple named indexes on a single collection — for example, one index for text embeddings and another for image embeddings. This enables multi-modal similarity search from a single data structure.

---

## Summary

Vector similarity search adds a powerful capability to the microservices framework:

- **Embeddings** represent products as points in high-dimensional space
- **HNSW indexing** provides O(log n) approximate nearest-neighbor search
- **VectorCollection** is Hazelcast Enterprise's native vector data structure
- **Edition-aware modules** activate the feature only when Enterprise is available
- **Graceful fallback** means Community Edition works normally with empty results

The implementation demonstrates a broader pattern: how to add optional Enterprise features to a framework that must work on Community Edition. Define the interface in `framework-core`, implement the Enterprise path in `framework-enterprise`, and let Spring's auto-configuration ordering handle the selection.

---

*Previous: [Part 5 - The Saga Pattern for Distributed Transactions](05-saga-pattern-for-distributed-transactions.md)*

*[Back to Part 1 - Event Sourcing with Hazelcast Introduction](01-event-sourcing-with-hazelcast-introduction.md)*
