# Vector Similarity Search with Hazelcast

*Part 6 of 6 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

In the previous articles, we built an event sourcing framework ([Part 1](01-event-sourcing-with-hazelcast-introduction.md)), a Jet pipeline ([Part 2](02-building-event-pipeline-with-hazelcast-jet.md)), materialized views ([Part 3](03-materialized-views-for-fast-queries.md)), an observability stack ([Part 4](04-observability-in-event-sourced-systems.md)), and a choreographed saga pattern ([Part 5](05-saga-pattern-for-distributed-transactions.md)).

Now we add a modern capability: **"Find me products similar to this one."**

Similarity search is everywhere — Netflix recommends shows, Spotify suggests playlists, Amazon shows "customers also bought." Under the hood, these features use **vector embeddings**: numerical representations of items where similar items are close together in vector space.

In this article, we'll implement vector similarity search using Hazelcast IMap, covering:

- How text becomes a vector embedding
- Cosine similarity for comparing vectors
- An IMap-based brute-force search implementation
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

## Cosine Similarity

To find similar products, we need a way to measure how close two vectors are. **Cosine similarity** measures the angle between vectors, returning a value from -1.0 (opposite) to 1.0 (identical):

```java
static float cosineSimilarity(final float[] a, final float[] b) {
    final int len = Math.min(a.length, b.length);
    float dot = 0.0f;
    float normA = 0.0f;
    float normB = 0.0f;

    for (int i = 0; i < len; i++) {
        dot += a[i] * b[i];
        normA += a[i] * a[i];
        normB += b[i] * b[i];
    }

    if (normA == 0.0f || normB == 0.0f) {
        return 0.0f;
    }

    return dot / (float) (Math.sqrt(normA) * Math.sqrt(normB));
}
```

Why cosine similarity instead of Euclidean distance?

| Measure | Compares | Best For |
|---------|----------|----------|
| **Cosine similarity** | Direction (angle between vectors) | Text, semantics, normalized data |
| **Euclidean distance** | Absolute position in space | Spatial data, unnormalized features |

Cosine similarity cares about *what* a product is (direction), not *how strongly* the signal is (magnitude). A short product description and a long one can still be highly similar if they describe the same type of product.

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

| Implementation | Edition | Behavior |
|---------------|---------|----------|
| `SimpleVectorStoreService` | Enterprise | IMap-based brute-force cosine similarity search |
| `NoOpVectorStoreService` | Community | Returns empty results silently |

---

## The Enterprise Implementation

`SimpleVectorStoreService` stores embeddings in two Hazelcast IMaps and performs brute-force similarity search:

```java
@Service
@ConditionalOnEnterpriseFeature(EnterpriseFeature.VECTOR_STORE)
public class SimpleVectorStoreService implements VectorStoreService {

    private final IMap<String, float[]> embeddingMap;
    private final IMap<String, Map<String, Object>> metadataMap;

    public SimpleVectorStoreService(HazelcastInstance hazelcast,
                                     VectorStoreProperties properties) {
        this.embeddingMap = hazelcast.getMap(properties.getCollectionName());
        this.metadataMap = hazelcast.getMap(properties.getCollectionName() + "-metadata");
    }

    @Override
    public void storeEmbedding(String id, float[] embedding,
                                Map<String, Object> metadata) {
        embeddingMap.put(id, embedding);
        if (metadata != null && !metadata.isEmpty()) {
            metadataMap.put(id, metadata);
        }
    }

    @Override
    public List<SimilarityResult> findSimilar(float[] queryVector, int limit) {
        // Get ALL embeddings — brute force
        Map<String, float[]> allEmbeddings = embeddingMap.getAll(embeddingMap.keySet());

        List<SimilarityResult> results = new ArrayList<>(allEmbeddings.size());

        for (Map.Entry<String, float[]> entry : allEmbeddings.entrySet()) {
            float score = cosineSimilarity(queryVector, entry.getValue());
            Map<String, Object> metadata = metadataMap.get(entry.getKey());
            results.add(new SimilarityResult(entry.getKey(), score,
                metadata != null ? metadata : Map.of()));
        }

        // Sort by similarity (highest first) and take top N
        results.sort(Comparator.comparingDouble(SimilarityResult::score).reversed());
        return results.subList(0, Math.min(limit, results.size()));
    }

    @Override
    public List<SimilarityResult> findSimilarById(String id, int limit) {
        float[] queryVector = embeddingMap.get(id);
        if (queryVector == null) return List.of();

        // Find limit+1 to exclude the query item itself
        List<SimilarityResult> candidates = findSimilar(queryVector, limit + 1);
        return candidates.stream()
                .filter(r -> !r.id().equals(id))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public String getImplementationType() {
        return "IMap-Based Cosine Similarity (Enterprise)";
    }
}
```

### How It Works

1. **Store**: Product embeddings go into an `IMap<String, float[]>` keyed by product ID. Metadata (name, category) goes into a separate IMap.
2. **Search**: Load all embeddings, compute cosine similarity against the query vector, sort by score, return the top N.
3. **Find by ID**: Look up the query product's embedding, then delegate to `findSimilar`, filtering out the query product itself.

### Complexity and Scaling

This is O(n) per query — every stored embedding is compared. For a product catalog of hundreds or thousands of items, this is fine. For millions, you'd need an approximate nearest-neighbor index.

Hazelcast Enterprise offers `VectorCollection` with HNSW (Hierarchical Navigable Small World) indexing for O(log n) approximate search. The `VectorStoreProperties` class already reserves configuration for this:

```java
@ConfigurationProperties(prefix = "framework.vectorstore")
public class VectorStoreProperties {
    private String collectionName = "product-vectors";
    private int dimension = 128;
    private int maxConnections = 16;    // HNSW parameter (Enterprise)
    private int efConstruction = 200;   // HNSW parameter (Enterprise)
}
```

The architecture is designed so swapping the brute-force implementation for HNSW is a single class change — the interface stays the same.

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

## Edition-Aware Bean Selection

The magic is in two custom annotations: `@ConditionalOnEnterpriseFeature` and `@ConditionalOnCommunityFallback`. These work with the framework's `EditionDetector` to choose the right implementation at startup:

```
Application starts
    │
    ▼
EditionDetector checks for Enterprise license
    │
    ├── License found → Enterprise features enabled
    │   └── @ConditionalOnEnterpriseFeature matches
    │   └── SimpleVectorStoreService created
    │
    └── No license → Community Edition
        └── @ConditionalOnCommunityFallback matches
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

If neither conditional annotation matches (edge case during testing or misconfiguration), the no-op implementation is used. The application never crashes due to a missing vector store bean.

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
  "implementation": "IMap-Based Cosine Similarity (Enterprise)",
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
2. Enterprise implementation  (@ConditionalOnEnterpriseFeature)
3. Community fallback         (@ConditionalOnCommunityFallback)
4. Auto-configuration         (@ConditionalOnMissingBean safety net)
5. Runtime check              (isAvailable() in controller)
```

This pattern ensures:

- **Community Edition always works**: No crashes, no missing beans, no exceptions
- **Enterprise features activate automatically**: Just add a license key
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

### HNSW Indexing

For catalogs with millions of items, the brute-force O(n) search won't scale. Hazelcast Enterprise's `VectorCollection` uses HNSW indexing for O(log n) approximate nearest-neighbor search. The interface is already designed for this swap — change the implementation class, keep the same API.

### Hybrid Search

Combine vector similarity with traditional filters:

```
"Find products similar to this laptop, but only in the Electronics category and under $1000"
```

This would combine a vector search for semantic similarity with IMap predicate queries for attribute filtering.

---

## Summary

Vector similarity search adds a powerful capability to the microservices framework:

- **Embeddings** represent products as points in high-dimensional space
- **Cosine similarity** measures how close two product vectors are
- **IMap storage** keeps embeddings distributed and fast
- **Edition-aware beans** activate the feature only when Enterprise is available
- **Graceful fallback** means Community Edition works normally with empty results

The implementation demonstrates a broader pattern: how to add optional Enterprise features to a framework that must work on Community Edition. Define the interface, implement both paths, and let Spring's conditional beans handle the selection.

---

*Previous: [Part 5 - The Saga Pattern for Distributed Transactions](05-saga-pattern-for-distributed-transactions.md)*

*[Back to Part 1 - Event Sourcing with Hazelcast Introduction](01-event-sourcing-with-hazelcast-introduction.md)*
