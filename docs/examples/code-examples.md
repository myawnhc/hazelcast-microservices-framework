# Code Examples for Event Sourcing Framework

This document contains standalone code examples that accompany the blog post series. Each example can be adapted for your own use cases.

---

## Table of Contents

1. [Creating Domain Events](#1-creating-domain-events)
2. [Implementing a ViewUpdater](#2-implementing-a-viewupdater)
3. [Using the EventSourcingController](#3-using-the-eventsourcingcontroller)
4. [Building REST APIs](#4-building-rest-apis)
5. [Cross-Service Materialized Views](#5-cross-service-materialized-views)
6. [Error Handling Patterns](#6-error-handling-patterns)
7. [Testing Event Sourcing](#7-testing-event-sourcing)
8. [Performance Optimization](#8-performance-optimization)

---

## 1. Creating Domain Events

### Basic Event Structure

Every domain event extends `DomainEvent` and implements three key methods:

```java
package com.example.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.event.DomainEvent;
import com.example.domain.Product;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event representing the creation of a new product.
 */
public class ProductCreatedEvent extends DomainEvent<Product, String> {

    private static final long serialVersionUID = 1L;
    public static final String SCHEMA_NAME = "ProductCreatedEvent";
    public static final String EVENT_TYPE = "ProductCreated";

    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private int initialStock;
    private String category;

    /**
     * Default constructor for serialization.
     */
    public ProductCreatedEvent() {
        super();
        this.eventType = EVENT_TYPE;
    }

    /**
     * Creates a new ProductCreatedEvent.
     */
    public ProductCreatedEvent(String productId, String sku, String name,
                                String description, BigDecimal price,
                                int initialStock, String category) {
        super(productId);  // Sets the key (productId)
        this.eventType = EVENT_TYPE;
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.initialStock = initialStock;
        this.category = category;
    }

    /**
     * Serializes this event to a GenericRecord for storage.
     */
    @Override
    public GenericRecord toGenericRecord() {
        return GenericRecordBuilder.compact(SCHEMA_NAME)
            // Base event fields
            .setString("eventId", eventId)
            .setString("eventType", eventType)
            .setString("eventVersion", eventVersion)
            .setString("source", source)
            .setInt64("timestamp", timestamp != null ? timestamp.toEpochMilli() : 0)
            .setString("key", key)
            .setString("correlationId", correlationId)
            // Saga fields (optional)
            .setString("sagaId", sagaId)
            .setString("sagaType", sagaType)
            .setInt32("stepNumber", stepNumber != null ? stepNumber : 0)
            .setBoolean("isCompensating", isCompensating != null && isCompensating)
            // Event-specific fields
            .setString("sku", sku)
            .setString("name", name)
            .setString("description", description)
            .setString("price", price.toString())  // Store as string for precision
            .setInt32("initialStock", initialStock)
            .setString("category", category)
            .build();
    }

    /**
     * Applies this event to create the initial product state.
     */
    @Override
    public GenericRecord apply(GenericRecord currentState) {
        // For creation events, ignore current state and create new
        Instant now = Instant.now();
        return GenericRecordBuilder.compact("Product")
            .setString("productId", key)
            .setString("sku", sku)
            .setString("name", name)
            .setString("description", description)
            .setString("price", price.toString())
            .setInt32("quantityOnHand", initialStock)
            .setInt32("quantityReserved", 0)
            .setString("category", category)
            .setString("status", "ACTIVE")
            .setInt64("createdAt", now.toEpochMilli())
            .setInt64("updatedAt", now.toEpochMilli())
            .build();
    }

    @Override
    public String getSchemaName() {
        return SCHEMA_NAME;
    }

    // Getters
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public int getInitialStock() { return initialStock; }
    public String getCategory() { return category; }
}
```

### Update Events

Update events modify existing state:

```java
public class ProductPriceChangedEvent extends DomainEvent<Product, String> {

    public static final String EVENT_TYPE = "ProductPriceChanged";

    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private String reason;

    public ProductPriceChangedEvent(String productId, BigDecimal newPrice, String reason) {
        super(productId);
        this.eventType = EVENT_TYPE;
        this.newPrice = newPrice;
        this.reason = reason;
    }

    @Override
    public GenericRecord apply(GenericRecord currentState) {
        if (currentState == null) {
            throw new IllegalStateException("Cannot change price of non-existent product");
        }

        // Capture old price for the event record
        this.oldPrice = new BigDecimal(currentState.getString("price"));

        return GenericRecordBuilder.compact("Product")
            // Copy unchanged fields
            .setString("productId", currentState.getString("productId"))
            .setString("sku", currentState.getString("sku"))
            .setString("name", currentState.getString("name"))
            .setString("description", currentState.getString("description"))
            .setInt32("quantityOnHand", currentState.getInt32("quantityOnHand"))
            .setInt32("quantityReserved", currentState.getInt32("quantityReserved"))
            .setString("category", currentState.getString("category"))
            .setString("status", currentState.getString("status"))
            .setInt64("createdAt", currentState.getInt64("createdAt"))
            // Updated fields
            .setString("price", newPrice.toString())
            .setInt64("updatedAt", Instant.now().toEpochMilli())
            .build();
    }

    @Override
    public GenericRecord toGenericRecord() {
        return GenericRecordBuilder.compact("ProductPriceChangedEvent")
            .setString("eventId", eventId)
            .setString("eventType", eventType)
            .setString("key", key)
            .setString("correlationId", correlationId)
            .setInt64("timestamp", timestamp.toEpochMilli())
            .setString("oldPrice", oldPrice != null ? oldPrice.toString() : null)
            .setString("newPrice", newPrice.toString())
            .setString("reason", reason)
            .build();
    }

    @Override
    public String getSchemaName() {
        return "ProductPriceChangedEvent";
    }
}
```

---

## 2. Implementing a ViewUpdater

### Basic ViewUpdater

```java
package com.example.view;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.view.HazelcastViewStore;
import com.theyawns.framework.view.ViewUpdater;

import java.time.Instant;

/**
 * Updates the Product materialized view from product events.
 */
public class ProductViewUpdater extends ViewUpdater<String> {

    public static final String VIEW_NAME = "Product";

    public ProductViewUpdater(HazelcastViewStore<String> viewStore) {
        super(viewStore);
    }

    @Override
    protected String extractKey(GenericRecord eventRecord) {
        return eventRecord.getString("key");
    }

    @Override
    protected GenericRecord applyEvent(GenericRecord event, GenericRecord current) {
        String eventType = getEventType(event);

        return switch (eventType) {
            case "ProductCreated" -> handleProductCreated(event);
            case "ProductUpdated" -> handleProductUpdated(event, current);
            case "ProductPriceChanged" -> handlePriceChanged(event, current);
            case "StockReserved" -> handleStockReserved(event, current);
            case "StockReleased" -> handleStockReleased(event, current);
            case "ProductDeleted" -> null;  // Delete the view entry
            default -> {
                logger.debug("Unknown event type: {}", eventType);
                yield current;
            }
        };
    }

    private GenericRecord handleProductCreated(GenericRecord event) {
        Instant now = Instant.now();
        return GenericRecordBuilder.compact(VIEW_NAME)
            .setString("productId", event.getString("key"))
            .setString("sku", event.getString("sku"))
            .setString("name", event.getString("name"))
            .setString("description", event.getString("description"))
            .setString("price", event.getString("price"))
            .setInt32("quantityOnHand", event.getInt32("initialStock"))
            .setInt32("quantityReserved", 0)
            .setString("category", event.getString("category"))
            .setString("status", "ACTIVE")
            .setInt64("createdAt", now.toEpochMilli())
            .setInt64("updatedAt", now.toEpochMilli())
            .build();
    }

    private GenericRecord handleStockReserved(GenericRecord event, GenericRecord current) {
        if (current == null) {
            logger.warn("StockReserved for non-existent product: {}", event.getString("key"));
            return null;
        }

        int quantity = event.getInt32("quantity");
        int currentReserved = current.getInt32("quantityReserved");

        return GenericRecordBuilder.compact(VIEW_NAME)
            .setString("productId", current.getString("productId"))
            .setString("sku", current.getString("sku"))
            .setString("name", current.getString("name"))
            .setString("description", current.getString("description"))
            .setString("price", current.getString("price"))
            .setInt32("quantityOnHand", current.getInt32("quantityOnHand"))
            .setInt32("quantityReserved", currentReserved + quantity)
            .setString("category", current.getString("category"))
            .setString("status", current.getString("status"))
            .setInt64("createdAt", current.getInt64("createdAt"))
            .setInt64("updatedAt", Instant.now().toEpochMilli())
            .build();
    }

    private GenericRecord handleStockReleased(GenericRecord event, GenericRecord current) {
        if (current == null) return null;

        int quantity = event.getInt32("quantity");
        int currentReserved = current.getInt32("quantityReserved");
        int newReserved = Math.max(0, currentReserved - quantity);

        return GenericRecordBuilder.compact(VIEW_NAME)
            .setString("productId", current.getString("productId"))
            .setString("sku", current.getString("sku"))
            .setString("name", current.getString("name"))
            .setString("description", current.getString("description"))
            .setString("price", current.getString("price"))
            .setInt32("quantityOnHand", current.getInt32("quantityOnHand"))
            .setInt32("quantityReserved", newReserved)
            .setString("category", current.getString("category"))
            .setString("status", current.getString("status"))
            .setInt64("createdAt", current.getInt64("createdAt"))
            .setInt64("updatedAt", Instant.now().toEpochMilli())
            .build();
    }

    // ... other handlers
}
```

---

## 3. Using the EventSourcingController

### Service Layer Pattern

```java
package com.example.service;

import com.theyawns.framework.controller.EventSourcingController;
import com.theyawns.framework.event.DomainEvent;
import com.example.domain.Product;
import com.example.dto.ProductDTO;
import com.example.events.ProductCreatedEvent;
import com.example.events.StockReservedEvent;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class InventoryService {

    private final EventSourcingController<Product, String, DomainEvent<Product, String>> controller;

    public InventoryService(
            EventSourcingController<Product, String, DomainEvent<Product, String>> controller) {
        this.controller = controller;
    }

    /**
     * Creates a new product.
     */
    public CompletableFuture<Product> createProduct(ProductDTO dto) {
        // 1. Generate unique ID
        String productId = UUID.randomUUID().toString();

        // 2. Create the event
        ProductCreatedEvent event = new ProductCreatedEvent(
            productId,
            dto.getSku(),
            dto.getName(),
            dto.getDescription(),
            dto.getPrice(),
            dto.getInitialStock(),
            dto.getCategory()
        );

        // 3. Handle the event (triggers pipeline)
        UUID correlationId = UUID.randomUUID();
        return controller.handleEvent(event, correlationId)
            .thenApply(completion -> {
                // 4. Return the created product from view
                return getProduct(productId)
                    .orElseThrow(() -> new RuntimeException("Product not found after creation"));
            });
    }

    /**
     * Reserves stock for an order.
     */
    public CompletableFuture<Product> reserveStock(String productId, int quantity, String orderId) {
        // Validate product exists and has sufficient stock
        Product product = getProduct(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        int available = product.getQuantityOnHand() - product.getQuantityReserved();
        if (available < quantity) {
            throw new InsufficientStockException(productId, quantity, available);
        }

        // Create and process event
        StockReservedEvent event = new StockReservedEvent(productId, quantity, orderId);

        return controller.handleEvent(event, UUID.randomUUID())
            .thenApply(completion -> getProduct(productId).orElseThrow());
    }

    /**
     * Gets a product by ID from the materialized view.
     */
    public Optional<Product> getProduct(String productId) {
        GenericRecord gr = controller.getViewMap().get(productId);
        if (gr == null) {
            return Optional.empty();
        }
        return Optional.of(Product.fromGenericRecord(gr));
    }

    /**
     * Gets all products (use with caution for large datasets).
     */
    public List<Product> getAllProducts() {
        return controller.getViewMap().values().stream()
            .map(Product::fromGenericRecord)
            .collect(Collectors.toList());
    }
}
```

---

## 4. Building REST APIs

### REST Controller

```java
package com.example.controller;

import com.example.dto.ProductDTO;
import com.example.dto.StockReservationRequest;
import com.example.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Inventory Management", description = "Product and stock operations")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping
    @Operation(summary = "Create a new product")
    public CompletableFuture<ResponseEntity<ProductDTO>> createProduct(
            @Valid @RequestBody ProductDTO dto) {
        return inventoryService.createProduct(dto)
            .thenApply(product -> ResponseEntity
                .status(HttpStatus.CREATED)
                .body(product.toDTO()));
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get product by ID")
    public ResponseEntity<ProductDTO> getProduct(@PathVariable String productId) {
        return inventoryService.getProduct(productId)
            .map(product -> ResponseEntity.ok(product.toDTO()))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "Get all products")
    public ResponseEntity<List<ProductDTO>> getAllProducts() {
        List<ProductDTO> products = inventoryService.getAllProducts().stream()
            .map(Product::toDTO)
            .toList();
        return ResponseEntity.ok(products);
    }

    @PostMapping("/{productId}/stock/reserve")
    @Operation(summary = "Reserve stock for an order")
    public CompletableFuture<ResponseEntity<ProductDTO>> reserveStock(
            @PathVariable String productId,
            @Valid @RequestBody StockReservationRequest request) {
        return inventoryService.reserveStock(productId, request.getQuantity(), request.getOrderId())
            .thenApply(product -> ResponseEntity.ok(product.toDTO()));
    }

    @PostMapping("/{productId}/stock/release")
    @Operation(summary = "Release reserved stock")
    public CompletableFuture<ResponseEntity<ProductDTO>> releaseStock(
            @PathVariable String productId,
            @Valid @RequestBody StockReservationRequest request) {
        return inventoryService.releaseStock(productId, request.getQuantity(), request.getOrderId())
            .thenApply(product -> ResponseEntity.ok(product.toDTO()));
    }
}
```

### Exception Handler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex) {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(ex.getMessage(), "PRODUCT_NOT_FOUND"));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException ex) {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(new ErrorResponse(ex.getMessage(), "INSUFFICIENT_STOCK"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        logger.error("Unexpected error", ex);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("Internal server error", "INTERNAL_ERROR"));
    }
}
```

---

## 5. Cross-Service Materialized Views

### Enriched Order View

```java
public class EnrichedOrderViewUpdater extends ViewUpdater<String> {

    private final IMap<String, GenericRecord> customerView;
    private final IMap<String, GenericRecord> productView;

    public EnrichedOrderViewUpdater(HazelcastViewStore<String> viewStore,
                                     HazelcastInstance hazelcast) {
        super(viewStore);
        this.customerView = hazelcast.getMap("Customer_VIEW");
        this.productView = hazelcast.getMap("Product_VIEW");
    }

    @Override
    protected String extractKey(GenericRecord eventRecord) {
        return eventRecord.getString("key");
    }

    @Override
    protected GenericRecord applyEvent(GenericRecord event, GenericRecord current) {
        String eventType = getEventType(event);

        return switch (eventType) {
            case "OrderCreated" -> createEnrichedOrder(event);
            case "OrderConfirmed" -> updateStatus(event, current, "CONFIRMED");
            case "OrderShipped" -> updateStatus(event, current, "SHIPPED");
            case "OrderDelivered" -> updateStatus(event, current, "DELIVERED");
            case "OrderCancelled" -> updateStatus(event, current, "CANCELLED");
            default -> current;
        };
    }

    private GenericRecord createEnrichedOrder(GenericRecord event) {
        String customerId = event.getString("customerId");

        // Lookup customer data from local view (NOT a service call!)
        GenericRecord customer = customerView.get(customerId);
        String customerName = customer != null ? customer.getString("name") : "Unknown";
        String customerEmail = customer != null ? customer.getString("email") : "";

        // Parse and enrich line items
        GenericRecord[] rawItems = event.getArrayOfGenericRecord("lineItems");
        GenericRecord[] enrichedItems = new GenericRecord[rawItems.length];

        for (int i = 0; i < rawItems.length; i++) {
            GenericRecord item = rawItems[i];
            String productId = item.getString("productId");

            // Lookup product data from local view
            GenericRecord product = productView.get(productId);
            String productName = product != null ? product.getString("name") : "Unknown Product";
            String sku = product != null ? product.getString("sku") : "";

            enrichedItems[i] = GenericRecordBuilder.compact("EnrichedLineItem")
                .setString("productId", productId)
                .setString("productName", productName)
                .setString("sku", sku)
                .setInt32("quantity", item.getInt32("quantity"))
                .setString("unitPrice", item.getString("unitPrice"))
                .build();
        }

        return GenericRecordBuilder.compact("EnrichedOrder")
            .setString("orderId", event.getString("key"))
            .setString("customerId", customerId)
            .setString("customerName", customerName)
            .setString("customerEmail", customerEmail)
            .setArrayOfGenericRecord("lineItems", enrichedItems)
            .setString("subtotal", event.getString("subtotal"))
            .setString("tax", event.getString("tax"))
            .setString("total", event.getString("total"))
            .setString("shippingAddress", event.getString("shippingAddress"))
            .setString("status", "PENDING")
            .setInt64("createdAt", Instant.now().toEpochMilli())
            .build();
    }

    private GenericRecord updateStatus(GenericRecord event, GenericRecord current, String newStatus) {
        if (current == null) return null;

        return GenericRecordBuilder.compact("EnrichedOrder")
            .setString("orderId", current.getString("orderId"))
            .setString("customerId", current.getString("customerId"))
            .setString("customerName", current.getString("customerName"))
            .setString("customerEmail", current.getString("customerEmail"))
            .setArrayOfGenericRecord("lineItems", current.getArrayOfGenericRecord("lineItems"))
            .setString("subtotal", current.getString("subtotal"))
            .setString("tax", current.getString("tax"))
            .setString("total", current.getString("total"))
            .setString("shippingAddress", current.getString("shippingAddress"))
            .setString("status", newStatus)
            .setInt64("createdAt", current.getInt64("createdAt"))
            .setInt64("updatedAt", Instant.now().toEpochMilli())
            .build();
    }
}
```

---

## 6. Error Handling Patterns

### Custom Exceptions

```java
public class EventProcessingException extends RuntimeException {

    private final String eventId;
    private final String eventType;
    private final String stage;

    public EventProcessingException(String message, String eventId,
                                     String eventType, String stage) {
        super(message);
        this.eventId = eventId;
        this.eventType = eventType;
        this.stage = stage;
    }

    // Getters...
}

public class InsufficientStockException extends RuntimeException {

    private final String productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(String productId, int requested, int available) {
        super(String.format("Insufficient stock for product %s: requested %d, available %d",
            productId, requested, available));
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }
}
```

### Validation Pattern

```java
public CompletableFuture<Order> createOrder(OrderDTO dto) {
    // Validate customer exists
    Customer customer = accountService.getCustomer(dto.getCustomerId())
        .orElseThrow(() -> new CustomerNotFoundException(dto.getCustomerId()));

    // Validate all products exist and have sufficient stock
    for (LineItemDTO item : dto.getLineItems()) {
        Product product = inventoryService.getProduct(item.getProductId())
            .orElseThrow(() -> new ProductNotFoundException(item.getProductId()));

        int available = product.getQuantityOnHand() - product.getQuantityReserved();
        if (available < item.getQuantity()) {
            throw new InsufficientStockException(
                item.getProductId(), item.getQuantity(), available);
        }
    }

    // All validations passed - create the event
    OrderCreatedEvent event = new OrderCreatedEvent(...);
    return controller.handleEvent(event, UUID.randomUUID())
        .thenApply(completion -> getOrder(event.getKey()).orElseThrow());
}
```

---

## 7. Testing Event Sourcing

### Unit Testing Events

```java
@DisplayName("ProductCreatedEvent")
class ProductCreatedEventTest {

    @Test
    @DisplayName("should create product state from event")
    void shouldCreateProductState() {
        // Arrange
        ProductCreatedEvent event = new ProductCreatedEvent(
            "prod-123", "SKU-001", "Test Product",
            "Description", new BigDecimal("29.99"), 100, "Electronics"
        );

        // Act
        GenericRecord result = event.apply(null);

        // Assert
        assertThat(result.getString("productId")).isEqualTo("prod-123");
        assertThat(result.getString("sku")).isEqualTo("SKU-001");
        assertThat(result.getString("name")).isEqualTo("Test Product");
        assertThat(result.getInt32("quantityOnHand")).isEqualTo(100);
        assertThat(result.getInt32("quantityReserved")).isEqualTo(0);
        assertThat(result.getString("status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("should serialize to GenericRecord correctly")
    void shouldSerializeToGenericRecord() {
        // Arrange
        ProductCreatedEvent event = new ProductCreatedEvent(
            "prod-123", "SKU-001", "Test Product",
            "Description", new BigDecimal("29.99"), 100, "Electronics"
        );

        // Act
        GenericRecord record = event.toGenericRecord();

        // Assert
        assertThat(record.getString("eventType")).isEqualTo("ProductCreated");
        assertThat(record.getString("key")).isEqualTo("prod-123");
        assertThat(record.getString("sku")).isEqualTo("SKU-001");
    }
}
```

### Integration Testing

```java
@SpringBootTest
@Testcontainers
class InventoryServiceIntegrationTest {

    @Container
    static GenericContainer<?> hazelcast = new GenericContainer<>("hazelcast/hazelcast:5.6.0")
        .withExposedPorts(5701)
        .waitingFor(Wait.forHttp("/hazelcast/health").forStatusCode(200));

    @Autowired
    private InventoryService inventoryService;

    @Test
    @DisplayName("should create and retrieve product")
    void shouldCreateAndRetrieveProduct() {
        // Arrange
        ProductDTO dto = new ProductDTO();
        dto.setSku("TEST-SKU");
        dto.setName("Test Product");
        dto.setPrice(new BigDecimal("99.99"));
        dto.setInitialStock(50);
        dto.setCategory("Test");

        // Act
        Product created = inventoryService.createProduct(dto).join();

        // Assert
        assertThat(created.getSku()).isEqualTo("TEST-SKU");
        assertThat(created.getQuantityOnHand()).isEqualTo(50);

        // Verify retrieval
        Optional<Product> retrieved = inventoryService.getProduct(created.getProductId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getSku()).isEqualTo("TEST-SKU");
    }

    @Test
    @DisplayName("should reserve and release stock")
    void shouldReserveAndReleaseStock() {
        // Create product
        Product product = createTestProduct(100);

        // Reserve stock
        Product afterReserve = inventoryService
            .reserveStock(product.getProductId(), 30, "order-123")
            .join();
        assertThat(afterReserve.getQuantityReserved()).isEqualTo(30);

        // Release stock
        Product afterRelease = inventoryService
            .releaseStock(product.getProductId(), 20, "order-123")
            .join();
        assertThat(afterRelease.getQuantityReserved()).isEqualTo(10);
    }
}
```

---

## 8. Performance Optimization

### Batch Operations

```java
// Efficient: Batch read
public Map<String, Product> getProducts(Set<String> productIds) {
    Map<String, GenericRecord> records = controller.getViewMap().getAll(productIds);
    return records.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> Product.fromGenericRecord(e.getValue())
        ));
}

// Inefficient: Individual reads
public Map<String, Product> getProductsSlow(Set<String> productIds) {
    Map<String, Product> result = new HashMap<>();
    for (String id : productIds) {
        getProduct(id).ifPresent(p -> result.put(id, p));
    }
    return result;  // N network calls!
}
```

### Near Cache Configuration

```yaml
# application.yml
hazelcast:
  client:
    near-cache:
      Product_VIEW:
        time-to-live-seconds: 60
        max-idle-seconds: 30
        eviction:
          eviction-policy: LRU
          max-size-policy: ENTRY_COUNT
          size: 5000
```

### Parallel Event Processing

```java
// Process multiple events in parallel
public CompletableFuture<List<Product>> createProducts(List<ProductDTO> dtos) {
    List<CompletableFuture<Product>> futures = dtos.stream()
        .map(this::createProduct)
        .toList();

    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(v -> futures.stream()
            .map(CompletableFuture::join)
            .toList());
}
```

---

## Summary

These code examples demonstrate the key patterns for building event-sourced applications with Hazelcast:

1. **Domain Events**: Immutable records of state changes with `toGenericRecord()` and `apply()` methods
2. **ViewUpdaters**: Transform events into materialized view state
3. **Service Layer**: Use `EventSourcingController.handleEvent()` for writes, views for reads
4. **REST APIs**: Async endpoints with `CompletableFuture`
5. **Cross-Service Views**: Denormalize data from multiple domains
6. **Error Handling**: Validation before event creation, custom exceptions
7. **Testing**: Unit tests for events, integration tests with Testcontainers
8. **Performance**: Batch operations, near cache, parallel processing

For complete working code, see the `ecommerce-common`, `account-service`, `inventory-service`, and `order-service` modules.
