# eCommerce Common

Shared domain objects, events, and DTOs for eCommerce microservices.

## Overview

This module contains the common domain model shared across all eCommerce services. It defines the domain entities, events, and data transfer objects (DTOs) that enable communication between services.

## Package Structure

```
com.theyawns.ecommerce.common
├── domain/               # Domain entities
│   ├── Customer.java
│   ├── Product.java
│   ├── Order.java
│   └── OrderLineItem.java
│
├── events/               # Domain events
│   ├── CustomerCreatedEvent.java
│   ├── CustomerUpdatedEvent.java
│   ├── ProductCreatedEvent.java
│   ├── StockReservedEvent.java
│   ├── StockReleasedEvent.java
│   ├── OrderCreatedEvent.java
│   ├── OrderConfirmedEvent.java
│   └── OrderCancelledEvent.java
│
└── dto/                  # Data Transfer Objects
    ├── CustomerDTO.java
    ├── ProductDTO.java
    └── OrderDTO.java
```

## Domain Objects

### Customer

Represents a customer account.

```java
Customer customer = new Customer(
    "cust-123",
    "john@example.com",
    "John Doe",
    "123 Main St",
    "555-1234",
    "ACTIVE"
);

// Convert to DTO
CustomerDTO dto = customer.toDTO();

// Convert to GenericRecord
GenericRecord record = customer.toGenericRecord();

// Create from GenericRecord
Customer fromRecord = Customer.fromGenericRecord(record);
```

### Product

Represents a product in the catalog.

```java
Product product = new Product(
    "prod-123",
    "SKU-001",
    "Gaming Laptop",
    "High-performance laptop",
    new BigDecimal("1999.99"),
    100,  // quantityOnHand
    0,    // quantityReserved
    "Electronics",
    "ACTIVE"
);

// Check availability
boolean canOrder = product.canReserve(5);
int available = product.getAvailableQuantity();
```

### Order

Represents a customer order.

```java
List<OrderLineItem> items = List.of(
    new OrderLineItem("prod-1", "Laptop", "SKU-001", 1, new BigDecimal("999.99")),
    new OrderLineItem("prod-2", "Mouse", "SKU-002", 2, new BigDecimal("29.99"))
);

Order order = new Order(
    "order-123",
    "cust-456",
    items,
    "123 Main St"
);

// Auto-calculated fields
BigDecimal subtotal = order.getSubtotal();  // 1059.97
BigDecimal tax = order.getTax();            // 105.997 (10%)
BigDecimal total = order.getTotal();        // 1165.967
```

### OrderLineItem

Represents a line item in an order.

```java
OrderLineItem item = new OrderLineItem(
    "prod-123",
    "Gaming Laptop",
    "SKU-001",
    2,
    new BigDecimal("999.99")
);

BigDecimal lineTotal = item.getLineTotal();  // 1999.98
```

## Events

All events extend `DomainEvent` and support:

- Serialization to/from `GenericRecord`
- Application to domain object state
- Saga metadata for distributed transactions

### Customer Events

```java
// Create customer
CustomerCreatedEvent created = new CustomerCreatedEvent(
    "cust-123", "john@example.com", "John Doe", "123 Main St", "555-1234"
);

// Update customer
CustomerUpdatedEvent updated = new CustomerUpdatedEvent(
    "cust-123", "john@example.com", "John Doe", "456 Oak Ave", "555-9876"
);
```

### Product Events

```java
// Create product
ProductCreatedEvent created = new ProductCreatedEvent(
    "prod-123", "SKU-001", "Laptop", "Gaming laptop",
    new BigDecimal("999.99"), 100, "Electronics"
);

// Reserve stock
StockReservedEvent reserved = new StockReservedEvent(
    "prod-123", 5, "order-456"
);

// Release stock
StockReleasedEvent released = new StockReleasedEvent(
    "prod-123", 5, "order-456", "Order cancelled"
);
```

### Order Events

```java
// Create order
OrderCreatedEvent created = new OrderCreatedEvent(
    "order-123", "cust-456", lineItems, "123 Main St"
);

// Confirm order
OrderConfirmedEvent confirmed = new OrderConfirmedEvent("order-123");

// Cancel order
OrderCancelledEvent cancelled = new OrderCancelledEvent(
    "order-123", "Out of stock", "system"
);
```

## DTOs

DTOs are used for REST API request/response bodies.

### CustomerDTO

```java
CustomerDTO dto = new CustomerDTO();
dto.setEmail("john@example.com");
dto.setName("John Doe");
dto.setAddress("123 Main St");
dto.setPhone("555-1234");

// Validation annotations
@NotBlank(message = "Email is required")
private String email;

@NotBlank(message = "Name is required")
private String name;
```

### ProductDTO

```java
ProductDTO dto = new ProductDTO(
    "SKU-001",
    "Gaming Laptop",
    new BigDecimal("999.99"),
    100
);

// Computed field
int available = dto.getAvailableQuantity();
```

### OrderDTO

```java
OrderDTO dto = new OrderDTO();
dto.setCustomerId("cust-123");
dto.setLineItems(List.of(lineItemDTO1, lineItemDTO2));
dto.setShippingAddress("123 Main St");
```

## GenericRecord Serialization

All domain objects and events support Hazelcast GenericRecord serialization:

```java
// Domain object to GenericRecord
GenericRecord customerRecord = customer.toGenericRecord();

// GenericRecord to domain object
Customer customer = Customer.fromGenericRecord(customerRecord);

// Event to GenericRecord
GenericRecord eventRecord = event.toGenericRecord();

// Schema name for events
String schema = event.getSchemaName();  // "CustomerCreatedEvent"
```

## Testing

```bash
# Run tests
mvn test -pl ecommerce-common

# With coverage
mvn test -pl ecommerce-common jacoco:report
```

### Test Coverage

The module includes comprehensive tests for:

- Domain object construction and methods
- Event serialization/deserialization
- Event application to domain state
- DTO validation and equality
- Edge cases (null handling, boundary conditions)

## Dependencies

```xml
<dependency>
    <groupId>com.theyawns</groupId>
    <artifactId>ecommerce-common</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

This module depends on:

- framework-core (for DomainEvent, DomainObject)
- Hazelcast (for GenericRecord)
- Jakarta Validation (for DTO validation)

## License

Apache License 2.0
