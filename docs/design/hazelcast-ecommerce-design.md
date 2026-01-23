# Hazelcast Microservices Demonstration Project
## eCommerce Implementation - Design Document

---

## 1. Executive Summary

### 1.1 Project Overview
A demonstration project showcasing microservices architecture using Hazelcast for maintaining materialized views of domain objects to provide fast, in-memory access to current state across distributed services. The demo uses a simplified eCommerce domain with three core services.

### 1.2 Key Objectives
- [x] Demonstrate microservices architecture with clear bounded contexts
- [x] Showcase Hazelcast for real-time materialized views
- [x] Illustrate event-driven communication between services
- [x] Provide a domain-agnostic framework that can be adapted to other use cases
- [x] Keep implementation accessible and understandable

### 1.3 Target Audience
- Developers learning microservices patterns
- Architects evaluating Hazelcast for distributed state management
- Teams considering event-driven architectures
- Anyone wanting a practical, working example of materialized views

### 1.4 Demo Scope
**Three Services**: Account, Inventory, Order
**Domain**: Simplified eCommerce (order fulfillment flow)
**Focus**: Materialized views for cross-service queries without service-to-service calls

---

## 2. System Architecture

### 2.1 High-Level Architecture
```
┌─────────────────────────────────────────────────────────────────┐
│                         API Gateway / Client                     │
└─────────────────────────────────────────────────────────────────┘
                                 │
                 ┌───────────────┼───────────────┐
                 │               │               │
         ┌───────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐
         │   Account     │ │ Inventory  │ │   Order    │
         │   Service     │ │  Service   │ │  Service   │
         └───────┬──────┘ └─────┬──────┘ └─────┬──────┘
                 │               │               │
         ┌───────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐
         │  Account DB  │ │Inventory DB│ │  Order DB  │
         │  (Postgres)  │ │ (Postgres) │ │ (Postgres) │
         └──────────────┘ └────────────┘ └────────────┘
                 │               │               │
                 └───────────────┼───────────────┘
                                 │
                     ┌───────────▼───────────┐
                     │   Event Bus/Topics    │
                     │  (Hazelcast Topics)   │
                     └───────────┬───────────┘
                                 │
                     ┌───────────▼───────────┐
                     │  Hazelcast Cluster    │
                     │  (Materialized Views) │
                     │                       │
                     │  - Customer Views     │
                     │  - Product Views      │
                     │  - Order Views        │
                     └───────────────────────┘
```

### 2.2 Technology Stack
- **Language/Framework**: Java 17+ with Spring Boot 3.x
- **Hazelcast Version**: 5.x (latest stable)
- **Message Bus**: Hazelcast Topics (built-in pub/sub)
- **Data Storage**: PostgreSQL (one DB per service)
- **Container Orchestration**: Docker Compose (local dev), Kubernetes-ready
- **Build Tool**: Maven or Gradle

### 2.3 Architectural Principles
1. **Service Autonomy**: Each service owns its data and publishes events
2. **Event-Driven Communication**: No direct service-to-service REST calls for data
3. **Materialized Views**: Pre-computed, denormalized data in Hazelcast for fast reads
4. **Eventual Consistency**: Services converge to consistent state via events
5. **Framework Separation**: Core framework is domain-agnostic, demo is pluggable

---

## 3. Domain Model

### 3.1 Business Domain
Simplified eCommerce order fulfillment focusing on:
- Customer account management
- Product inventory tracking
- Order creation and fulfillment

### 3.2 Domain Entities

#### Entity: Customer
- **Purpose**: Represents a customer account
- **Attributes**: 
  - `customerId`: UUID - Unique identifier
  - `email`: String - Customer email (unique)
  - `name`: String - Customer full name
  - `address`: String - Shipping address
  - `createdAt`: Timestamp
  - `status`: Enum (ACTIVE, SUSPENDED, CLOSED)
- **Owned by Service**: Account Service
- **Events Published**:
  - `CustomerCreated` - when new customer registers
  - `CustomerUpdated` - when profile changes
  - `CustomerStatusChanged` - when status changes

#### Entity: Product
- **Purpose**: Represents a product available for purchase
- **Attributes**: 
  - `productId`: UUID - Unique identifier
  - `sku`: String - Stock keeping unit (unique)
  - `name`: String - Product name
  - `description`: String - Product description
  - `price`: BigDecimal - Current price
  - `stockQuantity`: Integer - Available quantity
  - `reservedQuantity`: Integer - Reserved but not yet sold
  - `status`: Enum (AVAILABLE, LOW_STOCK, OUT_OF_STOCK, DISCONTINUED)
- **Owned by Service**: Inventory Service
- **Events Published**:
  - `ProductCreated` - when new product added
  - `ProductUpdated` - when details change
  - `StockAdjusted` - when quantity changes (restock, manual adjustment)
  - `StockReserved` - when inventory reserved for order
  - `StockReleased` - when reservation cancelled
  - `StockCommitted` - when sale confirmed

#### Entity: Order
- **Purpose**: Represents a customer's purchase order
- **Attributes**: 
  - `orderId`: UUID - Unique identifier
  - `customerId`: UUID - Reference to customer
  - `items`: List<OrderItem>
    - `productId`: UUID
    - `quantity`: Integer
    - `priceAtOrder`: BigDecimal
  - `totalAmount`: BigDecimal
  - `status`: Enum (PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED)
  - `createdAt`: Timestamp
  - `updatedAt`: Timestamp
- **Owned by Service**: Order Service
- **Events Published**:
  - `OrderCreated` - when order placed
  - `OrderConfirmed` - when inventory reserved and payment accepted
  - `OrderShipped` - when order shipped
  - `OrderDelivered` - when order delivered
  - `OrderCancelled` - when order cancelled

### 3.3 Entity Relationships
- **Customer** ←(1:N)→ **Order**: One customer can have many orders
- **Order** ←(N:M)→ **Product**: Orders contain multiple products, products in multiple orders
- Services maintain their own data, relationships are logical, not foreign keys

---

## 4. Microservices Design

### 4.1 Service: Account Service

#### Responsibility
Manages customer accounts, profiles, and authentication/authorization context. Source of truth for customer data.

#### Data Ownership
- **Primary Entities**: Customer
- **Database**: PostgreSQL - `account_db`
  - Table: `customers`

#### APIs Exposed

##### REST Endpoints
- `POST /api/customers` - Create new customer account
- `GET /api/customers/{customerId}` - Get customer details
- `PUT /api/customers/{customerId}` - Update customer profile
- `PATCH /api/customers/{customerId}/status` - Change customer status

##### Events Published
- **Event**: `CustomerCreated`
  - **Trigger**: New customer registration
  - **Payload**: 
    ```json
    {
      "customerId": "uuid",
      "email": "customer@example.com",
      "name": "John Doe",
      "address": "123 Main St",
      "status": "ACTIVE",
      "createdAt": "2024-01-15T10:30:00Z"
    }
    ```

- **Event**: `CustomerUpdated`
  - **Trigger**: Profile information changed
  - **Payload**: 
    ```json
    {
      "customerId": "uuid",
      "email": "customer@example.com",
      "name": "John Doe",
      "address": "456 New St",
      "updatedAt": "2024-01-15T11:00:00Z"
    }
    ```

- **Event**: `CustomerStatusChanged`
  - **Trigger**: Customer status changes (ACTIVE → SUSPENDED, etc.)
  - **Payload**: 
    ```json
    {
      "customerId": "uuid",
      "oldStatus": "ACTIVE",
      "newStatus": "SUSPENDED",
      "reason": "Fraud detection",
      "changedAt": "2024-01-15T12:00:00Z"
    }
    ```

##### Events Consumed
- None (Account Service doesn't depend on other services)

#### Hazelcast Integration
- **Materialized Views Maintained**: None
- **Reason**: Account Service is the source of truth, others maintain views of customer data

---

### 4.2 Service: Inventory Service

#### Responsibility
Manages product catalog, inventory levels, and stock reservations. Source of truth for product data and availability.

#### Data Ownership
- **Primary Entities**: Product
- **Database**: PostgreSQL - `inventory_db`
  - Table: `products`
  - Table: `stock_movements` (audit log)

#### APIs Exposed

##### REST Endpoints
- `POST /api/products` - Add new product
- `GET /api/products/{productId}` - Get product details
- `GET /api/products` - List products (with filters)
- `PUT /api/products/{productId}` - Update product details
- `POST /api/products/{productId}/stock/adjust` - Adjust stock quantity
- `POST /api/products/{productId}/stock/reserve` - Reserve inventory (internal)
- `POST /api/products/{productId}/stock/release` - Release reservation (internal)
- `POST /api/products/{productId}/stock/commit` - Commit sale (internal)

##### Events Published
- **Event**: `ProductCreated`
  - **Trigger**: New product added to catalog
  - **Payload**: 
    ```json
    {
      "productId": "uuid",
      "sku": "WIDGET-001",
      "name": "Premium Widget",
      "description": "High-quality widget",
      "price": 29.99,
      "stockQuantity": 100,
      "status": "AVAILABLE",
      "createdAt": "2024-01-15T10:00:00Z"
    }
    ```

- **Event**: `ProductUpdated`
  - **Trigger**: Product details changed (name, price, description)
  - **Payload**: 
    ```json
    {
      "productId": "uuid",
      "name": "Premium Widget v2",
      "price": 34.99,
      "updatedAt": "2024-01-15T11:00:00Z"
    }
    ```

- **Event**: `StockAdjusted`
  - **Trigger**: Stock quantity manually adjusted (restock, correction)
  - **Payload**: 
    ```json
    {
      "productId": "uuid",
      "oldQuantity": 100,
      "newQuantity": 150,
      "adjustmentType": "RESTOCK",
      "reason": "New shipment received",
      "adjustedAt": "2024-01-15T10:30:00Z"
    }
    ```

- **Event**: `StockReserved`
  - **Trigger**: Inventory reserved for pending order
  - **Payload**: 
    ```json
    {
      "productId": "uuid",
      "orderId": "uuid",
      "quantity": 2,
      "reservedAt": "2024-01-15T11:15:00Z"
    }
    ```

- **Event**: `StockReleased`
  - **Trigger**: Reservation cancelled (order cancelled/timeout)
  - **Payload**: 
    ```json
    {
      "productId": "uuid",
      "orderId": "uuid",
      "quantity": 2,
      "releasedAt": "2024-01-15T11:45:00Z"
    }
    ```

- **Event**: `StockCommitted`
  - **Trigger**: Sale confirmed, inventory permanently reduced
  - **Payload**: 
    ```json
    {
      "productId": "uuid",
      "orderId": "uuid",
      "quantity": 2,
      "committedAt": "2024-01-15T12:00:00Z"
    }
    ```

##### Events Consumed
- **Event**: `OrderCreated` from Order Service
  - **Purpose**: Reserve inventory when order placed
  - **Action**: Validate stock availability, reserve quantity, publish `StockReserved`

- **Event**: `OrderConfirmed` from Order Service
  - **Purpose**: Commit inventory when order confirmed
  - **Action**: Commit reserved stock, publish `StockCommitted`

- **Event**: `OrderCancelled` from Order Service
  - **Purpose**: Release inventory when order cancelled
  - **Action**: Release reserved stock, publish `StockReleased`

#### Hazelcast Integration
- **Materialized Views Maintained**:
  - **View Name**: `product-availability-view`
    - **Purpose**: Real-time product availability for Order Service
    - **Source Events**: `ProductCreated`, `ProductUpdated`, `StockAdjusted`, `StockReserved`, `StockReleased`, `StockCommitted`
    - **Key**: `productId` (UUID as String)
    - **Structure**: 
      ```json
      {
        "productId": "uuid",
        "sku": "WIDGET-001",
        "name": "Premium Widget",
        "price": 29.99,
        "availableQuantity": 48,
        "reservedQuantity": 2,
        "status": "AVAILABLE",
        "lastUpdated": "2024-01-15T11:15:00Z"
      }
      ```
    - **Update Strategy**: Event-driven updates on every stock change
    - **TTL/Eviction**: No TTL (persists until product discontinued)

---

### 4.3 Service: Order Service

#### Responsibility
Manages order lifecycle from creation through fulfillment. Orchestrates order process by consuming events from Account and Inventory services. Maintains rich materialized views for order queries.

#### Data Ownership
- **Primary Entities**: Order, OrderItem
- **Database**: PostgreSQL - `order_db`
  - Table: `orders`
  - Table: `order_items`

#### APIs Exposed

##### REST Endpoints
- `POST /api/orders` - Create new order
- `GET /api/orders/{orderId}` - Get order details (from Hazelcast view)
- `GET /api/orders/customer/{customerId}` - Get customer's orders (from Hazelcast view)
- `PATCH /api/orders/{orderId}/confirm` - Confirm order
- `PATCH /api/orders/{orderId}/ship` - Mark order as shipped
- `PATCH /api/orders/{orderId}/cancel` - Cancel order

##### Events Published
- **Event**: `OrderCreated`
  - **Trigger**: Customer places order
  - **Payload**: 
    ```json
    {
      "orderId": "uuid",
      "customerId": "uuid",
      "items": [
        {
          "productId": "uuid",
          "quantity": 2,
          "priceAtOrder": 29.99
        }
      ],
      "totalAmount": 59.98,
      "status": "PENDING",
      "createdAt": "2024-01-15T11:15:00Z"
    }
    ```

- **Event**: `OrderConfirmed`
  - **Trigger**: Inventory confirmed, payment successful
  - **Payload**: 
    ```json
    {
      "orderId": "uuid",
      "confirmedAt": "2024-01-15T11:16:00Z"
    }
    ```

- **Event**: `OrderShipped`
  - **Trigger**: Order shipped to customer
  - **Payload**: 
    ```json
    {
      "orderId": "uuid",
      "trackingNumber": "1Z999AA1234567890",
      "shippedAt": "2024-01-16T09:00:00Z"
    }
    ```

- **Event**: `OrderDelivered`
  - **Trigger**: Order delivered to customer
  - **Payload**: 
    ```json
    {
      "orderId": "uuid",
      "deliveredAt": "2024-01-18T14:30:00Z"
    }
    ```

- **Event**: `OrderCancelled`
  - **Trigger**: Order cancelled by customer or system
  - **Payload**: 
    ```json
    {
      "orderId": "uuid",
      "reason": "Customer request",
      "cancelledAt": "2024-01-15T11:30:00Z"
    }
    ```

##### Events Consumed
- **Event**: `CustomerCreated` from Account Service
  - **Purpose**: Build customer view for order validation
  - **Action**: Add customer to `customer-view` in Hazelcast

- **Event**: `CustomerUpdated` from Account Service
  - **Purpose**: Keep customer information current in views
  - **Action**: Update customer in `customer-view`

- **Event**: `CustomerStatusChanged` from Account Service
  - **Purpose**: Prevent orders from suspended customers
  - **Action**: Update status in `customer-view`, potentially cancel pending orders

- **Event**: `ProductCreated` from Inventory Service
  - **Purpose**: Build product catalog view for order creation
  - **Action**: Add product to `product-availability-view`

- **Event**: `ProductUpdated` from Inventory Service
  - **Purpose**: Keep product details current (price changes)
  - **Action**: Update product in `product-availability-view`

- **Event**: `StockReserved` from Inventory Service
  - **Purpose**: Track reservation success
  - **Action**: Update order status, may transition to CONFIRMED

- **Event**: `StockReleased` from Inventory Service
  - **Purpose**: Handle reservation failures
  - **Action**: May cancel order if stock unavailable

#### Hazelcast Integration
Order Service is the PRIMARY CONSUMER of materialized views and maintains the most complex views.

- **Materialized Views Maintained**:

  - **View Name**: `customer-view`
    - **Purpose**: Fast customer lookup for order validation without calling Account Service
    - **Source Events**: `CustomerCreated`, `CustomerUpdated`, `CustomerStatusChanged`
    - **Key**: `customerId` (UUID as String)
    - **Structure**: 
      ```json
      {
        "customerId": "uuid",
        "email": "customer@example.com",
        "name": "John Doe",
        "address": "123 Main St",
        "status": "ACTIVE",
        "lastUpdated": "2024-01-15T10:30:00Z"
      }
      ```
    - **Update Strategy**: Event-driven, real-time updates
    - **TTL/Eviction**: No TTL

  - **View Name**: `enriched-order-view`
    - **Purpose**: Denormalized order view with customer and product details for fast queries
    - **Source Events**: All order events + customer events + product events
    - **Key**: `orderId` (UUID as String)
    - **Structure**: 
      ```json
      {
        "orderId": "uuid",
        "orderStatus": "CONFIRMED",
        "customer": {
          "customerId": "uuid",
          "name": "John Doe",
          "email": "customer@example.com"
        },
        "items": [
          {
            "productId": "uuid",
            "productName": "Premium Widget",
            "sku": "WIDGET-001",
            "quantity": 2,
            "priceAtOrder": 29.99,
            "subtotal": 59.98
          }
        ],
        "totalAmount": 59.98,
        "createdAt": "2024-01-15T11:15:00Z",
        "confirmedAt": "2024-01-15T11:16:00Z",
        "lastUpdated": "2024-01-15T11:16:00Z"
      }
      ```
    - **Update Strategy**: Composite updates from multiple event streams
    - **TTL/Eviction**: Optional TTL for old delivered orders (e.g., 90 days)

  - **View Name**: `customer-order-summary-view`
    - **Purpose**: Aggregated customer order history and statistics
    - **Source Events**: Order events
    - **Key**: `customerId` (UUID as String)
    - **Structure**: 
      ```json
      {
        "customerId": "uuid",
        "totalOrders": 15,
        "totalSpent": 1247.85,
        "averageOrderValue": 83.19,
        "orderIds": ["uuid1", "uuid2", "..."],
        "lastOrderDate": "2024-01-15T11:15:00Z",
        "lastUpdated": "2024-01-15T11:15:00Z"
      }
      ```
    - **Update Strategy**: Incremental aggregation on order events
    - **TTL/Eviction**: No TTL

---

## 5. Hazelcast Materialized Views Strategy

### 5.1 Core Concept
Materialized views in Hazelcast eliminate the need for synchronous service-to-service calls by maintaining pre-computed, denormalized data in memory. Each service subscribes to relevant events and updates its local views, enabling:
- **Fast Queries**: Sub-millisecond reads from memory
- **Service Independence**: No runtime dependencies between services
- **Scalability**: Each service scales independently
- **Resilience**: Continues working even if other services are down

### 5.2 View Ownership Model
- **Account Service**: Publishes customer events, maintains no views
- **Inventory Service**: Publishes product events, maintains `product-availability-view` for self-queries
- **Order Service**: Consumes all events, maintains rich views (`customer-view`, `enriched-order-view`, `customer-order-summary-view`)

### 5.3 Event Processing Flow
```
1. User places order via Order Service API
2. Order Service:
   - Validates customer (reads from `customer-view` in Hazelcast)
   - Validates products (reads from `product-availability-view` in Hazelcast)
   - Creates order in order_db
   - Publishes `OrderCreated` event
3. Inventory Service:
   - Receives `OrderCreated` event
   - Reserves stock in inventory_db
   - Publishes `StockReserved` event
   - Updates its `product-availability-view` in Hazelcast
4. Order Service:
   - Receives `StockReserved` event
   - Updates order status to CONFIRMED
   - Updates `enriched-order-view` in Hazelcast
   - Updates `customer-order-summary-view` in Hazelcast
5. User queries order status
   - Order Service reads from `enriched-order-view` in Hazelcast (no DB query, no service calls)
   - Returns complete order with customer and product details
```

### 5.4 Hazelcast Cluster Configuration

#### Cluster Setup
- **Cluster Size**: 3 nodes (minimum for production-like resilience)
- **Replication Factor**: 1 backup per partition (backup-count=1)
- **Network Configuration**: Multicast for discovery (local dev), Kubernetes discovery (production)

#### Distributed Map Configuration
```yaml
hazelcast:
  cluster-name: ecommerce-demo
  network:
    join:
      multicast:
        enabled: true
  map:
    customer-view:
      backup-count: 1
      read-backup-data: true
      in-memory-format: OBJECT
      eviction:
        eviction-policy: NONE
    product-availability-view:
      backup-count: 1
      read-backup-data: true
      in-memory-format: OBJECT
      eviction:
        eviction-policy: NONE
    enriched-order-view:
      backup-count: 1
      read-backup-data: true
      in-memory-format: OBJECT
      eviction:
        eviction-policy: LRU
        max-size-policy: PER_NODE
        size: 10000
      time-to-live-seconds: 7776000  # 90 days
    customer-order-summary-view:
      backup-count: 1
      read-backup-data: true
      in-memory-format: OBJECT
      eviction:
        eviction-policy: NONE
```

### 5.5 Consistency and Recovery

#### Eventual Consistency
- Views are **eventually consistent** with source data
- Typical propagation delay: <100ms
- Acceptable for eCommerce use case (users understand slight delays)

#### View Rebuild Strategy
- **Cold Start**: Services can rebuild views from event log or database snapshots
- **Incremental Updates**: Services checkpoint last processed event
- **Idempotency**: Event handlers are idempotent (handle duplicate events)

---

## 6. Event-Driven Communication

### 6.1 Event Schema Standard
All events follow this structure:
```json
{
  "eventId": "uuid",
  "eventType": "CustomerCreated",
  "eventVersion": "1.0",
  "timestamp": "2024-01-15T10:30:00Z",
  "source": "account-service",
  "correlationId": "uuid",
  "payload": {
    // event-specific data
  }
}
```

### 6.2 Event Catalog

| Event Name | Publisher | Consumers | Purpose |
|------------|-----------|-----------|---------|
| CustomerCreated | Account | Order | New customer registration |
| CustomerUpdated | Account | Order | Customer profile changed |
| CustomerStatusChanged | Account | Order | Customer status changed |
| ProductCreated | Inventory | Order | New product added |
| ProductUpdated | Inventory | Order | Product details changed |
| StockAdjusted | Inventory | - | Stock quantity adjusted |
| StockReserved | Inventory | Order | Inventory reserved for order |
| StockReleased | Inventory | Order | Reservation cancelled |
| StockCommitted | Inventory | - | Sale confirmed |
| OrderCreated | Order | Inventory | New order placed |
| OrderConfirmed | Order | Inventory | Order confirmed |
| OrderShipped | Order | - | Order shipped |
| OrderDelivered | Order | - | Order delivered |
| OrderCancelled | Order | Inventory | Order cancelled |

### 6.3 Event Bus Implementation
- **Technology**: Hazelcast Reliable Topics
- **Topic Naming**: `events.{domain}.{event-type}` (e.g., `events.account.customer-created`)
- **Guarantees**: 
  - At-least-once delivery
  - Order preserved per partition key (entity ID)
  - Reliable Topic Ring Buffer (configurable retention)
- **Dead Letter Queue**: Failed events moved to `events.dlq.{service-name}`

---

## 7. Data Flow Examples

### 7.1 Scenario: Create New Order

**Goal**: Demonstrate how materialized views enable fast order creation without synchronous service calls

**Steps**:
1. **User Action**: POST to `/api/orders` with customerId and product list
2. **Order Service**: 
   - Reads `customer-view` from Hazelcast to validate customer exists and is ACTIVE
   - Reads `product-availability-view` from Hazelcast to validate products exist and have stock
   - Creates order in `order_db` with status PENDING
   - Publishes `OrderCreated` event to Hazelcast Topic
3. **Event Propagation**: Event delivered to Inventory Service
4. **Inventory Service**: 
   - Receives `OrderCreated` event
   - Checks stock availability in `inventory_db`
   - Reserves stock (updates reserved_quantity)
   - Publishes `StockReserved` event
   - Updates `product-availability-view` in Hazelcast (decrements available quantity)
5. **Order Service**: 
   - Receives `StockReserved` event
   - Updates order status to CONFIRMED in `order_db`
   - Updates `enriched-order-view` in Hazelcast
   - Updates `customer-order-summary-view` in Hazelcast (increments totalOrders, totalSpent)
6. **User Query**: GET to `/api/orders/{orderId}`
   - Order Service reads from `enriched-order-view` in Hazelcast
   - Returns complete order with customer name, product details, current status
   - **No database query, no service calls** - pure in-memory read

**Sequence Diagram**:
```
User -> OrderService: POST /api/orders
OrderService -> Hazelcast: Read customer-view
OrderService -> Hazelcast: Read product-availability-view
OrderService -> OrderDB: Insert order (PENDING)
OrderService -> HazelcastTopic: Publish OrderCreated
HazelcastTopic -> InventoryService: Deliver OrderCreated
InventoryService -> InventoryDB: Reserve stock
InventoryService -> HazelcastTopic: Publish StockReserved
InventoryService -> Hazelcast: Update product-availability-view
HazelcastTopic -> OrderService: Deliver StockReserved
OrderService -> OrderDB: Update order (CONFIRMED)
OrderService -> Hazelcast: Update enriched-order-view
OrderService -> Hazelcast: Update customer-order-summary-view
OrderService -> User: Return order response

[Later...]
User -> OrderService: GET /api/orders/{orderId}
OrderService -> Hazelcast: Read enriched-order-view
Hazelcast -> OrderService: Return enriched order
OrderService -> User: Complete order details (no DB/service calls!)
```

**Key Demonstration Points**:
- ✅ Order creation validates customer/products from Hazelcast (no service calls)
- ✅ Async event processing between services
- ✅ Multiple materialized views updated independently
- ✅ Order query is pure in-memory read with denormalized data

---

### 7.2 Scenario: Cancel Order (Stock Release)

**Goal**: Demonstrate compensating actions and view consistency

**Steps**:
1. **User Action**: PATCH to `/api/orders/{orderId}/cancel`
2. **Order Service**: 
   - Updates order status to CANCELLED in `order_db`
   - Publishes `OrderCancelled` event
   - Updates `enriched-order-view` in Hazelcast
   - Updates `customer-order-summary-view` (decrements counters if needed)
3. **Inventory Service**: 
   - Receives `OrderCancelled` event
   - Releases reserved stock (decrements reserved_quantity)
   - Publishes `StockReleased` event
   - Updates `product-availability-view` in Hazelcast (increments available quantity)
4. **Result**: Stock is back in available pool, order shows CANCELLED, all views consistent

**Key Demonstration Points**:
- ✅ Compensating transactions via events
- ✅ Eventual consistency across services
- ✅ Materialized views reflect current state

---

### 7.3 Scenario: Customer Order History Query

**Goal**: Demonstrate aggregated view queries

**Steps**:
1. **User Action**: GET to `/api/orders/customer/{customerId}`
2. **Order Service**: 
   - Reads `customer-order-summary-view` from Hazelcast
   - Gets list of orderIds
   - Reads each order from `enriched-order-view` in Hazelcast (or batch read)
   - Returns complete order history with customer stats
3. **Result**: Fast query with pre-aggregated statistics, no joins across services

**Key Demonstration Points**:
- ✅ Aggregated materialized view (totalOrders, totalSpent)
- ✅ Fast customer history without database joins
- ✅ Denormalized data ready for display

---

## 8. Implementation Specifications

### 8.1 Project Structure
```
hazelcast-microservices-framework/
├── framework-core/                      # Domain-agnostic framework
│   ├── event-bus/                      # Event publishing/subscription abstractions
│   │   ├── EventPublisher.java
│   │   ├── EventListener.java
│   │   └── HazelcastEventBus.java
│   ├── materialized-view/              # View management abstractions
│   │   ├── MaterializedView.java
│   │   ├── ViewUpdater.java
│   │   └── HazelcastViewManager.java
│   ├── service-base/                   # Base service classes
│   │   ├── BaseService.java
│   │   └── ServiceConfig.java
│   └── common/
│       ├── DomainEvent.java
│       └── EventMetadata.java
│
├── demo-ecommerce/                      # eCommerce implementation
│   ├── account-service/
│   │   ├── src/main/java/
│   │   │   └── com/demo/account/
│   │   │       ├── AccountServiceApplication.java
│   │   │       ├── domain/
│   │   │       │   └── Customer.java
│   │   │       ├── repository/
│   │   │       │   └── CustomerRepository.java
│   │   │       ├── service/
│   │   │       │   └── CustomerService.java
│   │   │       ├── controller/
│   │   │       │   └── CustomerController.java
│   │   │       └── events/
│   │   │           ├── CustomerCreatedEvent.java
│   │   │           ├── CustomerUpdatedEvent.java
│   │   │           └── CustomerStatusChangedEvent.java
│   │   ├── src/main/resources/
│   │   │   ├── application.yml
│   │   │   └── schema.sql
│   │   ├── Dockerfile
│   │   └── pom.xml
│   │
│   ├── inventory-service/
│   │   ├── src/main/java/
│   │   │   └── com/demo/inventory/
│   │   │       ├── InventoryServiceApplication.java
│   │   │       ├── domain/
│   │   │       │   └── Product.java
│   │   │       ├── repository/
│   │   │       │   └── ProductRepository.java
│   │   │       ├── service/
│   │   │       │   ├── InventoryService.java
│   │   │       │   └── StockManagementService.java
│   │   │       ├── controller/
│   │   │       │   └── ProductController.java
│   │   │       ├── events/
│   │   │       │   ├── ProductCreatedEvent.java
│   │   │       │   ├── StockReservedEvent.java
│   │   │       │   └── ... (other events)
│   │   │       ├── listeners/
│   │   │       │   └── OrderEventListener.java
│   │   │       └── views/
│   │   │           └── ProductAvailabilityView.java
│   │   ├── src/main/resources/
│   │   │   ├── application.yml
│   │   │   └── schema.sql
│   │   ├── Dockerfile
│   │   └── pom.xml
│   │
│   ├── order-service/
│   │   ├── src/main/java/
│   │   │   └── com/demo/order/
│   │   │       ├── OrderServiceApplication.java
│   │   │       ├── domain/
│   │   │       │   ├── Order.java
│   │   │       │   └── OrderItem.java
│   │   │       ├── repository/
│   │   │       │   └── OrderRepository.java
│   │   │       ├── service/
│   │   │       │   └── OrderService.java
│   │   │       ├── controller/
│   │   │       │   └── OrderController.java
│   │   │       ├── events/
│   │   │       │   ├── OrderCreatedEvent.java
│   │   │       │   └── ... (other events)
│   │   │       ├── listeners/
│   │   │       │   ├── CustomerEventListener.java
│   │   │       │   ├── ProductEventListener.java
│   │   │       │   └── StockEventListener.java
│   │   │       └── views/
│   │   │           ├── CustomerView.java
│   │   │           ├── EnrichedOrderView.java
│   │   │           └── CustomerOrderSummaryView.java
│   │   ├── src/main/resources/
│   │   │   ├── application.yml
│   │   │   └── schema.sql
│   │   ├── Dockerfile
│   │   └── pom.xml
│   │
│   └── common/                          # Shared eCommerce domain
│       ├── src/main/java/
│       │   └── com/demo/common/
│       │       ├── events/              # Event schemas
│       │       └── dto/                 # Shared DTOs
│       └── pom.xml
│
├── hazelcast-config/
│   └── hazelcast.yaml                   # Hazelcast cluster configuration
│
├── docker-compose.yml                   # Local development setup
├── docker-compose.prod.yml              # Production-like setup (3 Hazelcast nodes)
└── README.md
```

### 8.2 Configuration Management

#### Environment Variables (per service)
```bash
# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=account_db
DB_USER=postgres
DB_PASSWORD=secret

# Hazelcast
HAZELCAST_CLUSTER_NAME=ecommerce-demo
HAZELCAST_MEMBERS=hazelcast-1:5701,hazelcast-2:5701,hazelcast-3:5701

# Service
SERVICE_NAME=account-service
SERVICE_PORT=8081
```

#### Hazelcast Configuration (hazelcast.yaml)
```yaml
hazelcast:
  cluster-name: ${HAZELCAST_CLUSTER_NAME:ecommerce-demo}
  network:
    port:
      auto-increment: true
      port: 5701
    join:
      multicast:
        enabled: false
      tcp-ip:
        enabled: true
        member-list: ${HAZELCAST_MEMBERS}
  map:
    default:
      backup-count: 1
      read-backup-data: true
      in-memory-format: OBJECT
```

### 8.3 Development Setup

#### Prerequisites
- Java 17+
- Maven 3.8+
- Docker & Docker Compose
- PostgreSQL (via Docker)

#### Quick Start
```bash
# Clone repository
git clone <repo-url>
cd hazelcast-microservices-framework

# Build framework and services
mvn clean install

# Start infrastructure (databases, Hazelcast)
docker-compose up -d postgres hazelcast

# Start services
cd demo-ecommerce/account-service && mvn spring-boot:run &
cd demo-ecommerce/inventory-service && mvn spring-boot:run &
cd demo-ecommerce/order-service && mvn spring-boot:run &

# Load sample data
./scripts/load-sample-data.sh

# Access services
# Account Service: http://localhost:8081
# Inventory Service: http://localhost:8082
# Order Service: http://localhost:8083
# Hazelcast Management Center: http://localhost:8080
```

---

## 9. Demonstration Scenarios

### 9.1 Demo Script 1: Basic Order Flow (Happy Path)

**Goal**: Show complete order lifecycle with materialized view updates

**Setup**:
- Start all services
- Verify Hazelcast Management Center shows empty views
- Have Postman/curl commands ready

**Steps**:
1. **Create Customer**
   ```bash
   POST /api/customers
   {
     "email": "john@example.com",
     "name": "John Doe",
     "address": "123 Main St"
   }
   ```
   - **Show**: Customer appears in `customer-view` in Hazelcast Management Center
   
2. **Create Product**
   ```bash
   POST /api/products
   {
     "sku": "WIDGET-001",
     "name": "Premium Widget",
     "description": "High-quality widget",
     "price": 29.99,
     "stockQuantity": 100
   }
   ```
   - **Show**: Product appears in `product-availability-view`

3. **Place Order**
   ```bash
   POST /api/orders
   {
     "customerId": "{customerId}",
     "items": [
       {
         "productId": "{productId}",
         "quantity": 2
       }
     ]
   }
   ```
   - **Show**: Watch logs as events flow between services
   - **Show**: `enriched-order-view` appears with denormalized data
   - **Show**: `customer-order-summary-view` shows totalOrders=1, totalSpent=59.98
   - **Show**: `product-availability-view` shows availableQuantity=98, reservedQuantity=2

4. **Query Order**
   ```bash
   GET /api/orders/{orderId}
   ```
   - **Show**: Response includes customer name, product details WITHOUT database joins
   - **Show**: Log statement: "Read from Hazelcast view, no database query"

**Expected Outcome**: 
- Audience sees events flow asynchronously
- Materialized views update in real-time
- Order query is instantaneous from memory

---

### 9.2 Demo Script 2: Order Cancellation (Compensating Transaction)

**Goal**: Demonstrate event-driven compensation and consistency

**Steps**:
1. Place order (from Demo Script 1)
2. **Show**: `product-availability-view` has reduced stock
3. **Cancel Order**
   ```bash
   PATCH /api/orders/{orderId}/cancel
   ```
4. **Show**: `OrderCancelled` event published
5. **Show**: Inventory Service receives event, releases stock
6. **Show**: `product-availability-view` stock restored
7. **Show**: `enriched-order-view` shows status=CANCELLED

**Expected Outcome**:
- Audience understands compensating transactions
- Views remain consistent across services

---

### 9.3 Demo Script 3: Customer Order History (Aggregated View)

**Goal**: Show value of aggregated materialized views

**Steps**:
1. Place multiple orders for same customer
2. **Query Order History**
   ```bash
   GET /api/orders/customer/{customerId}
   ```
3. **Show**: Response includes pre-calculated statistics (totalOrders, totalSpent, averageOrderValue)
4. **Show**: Log statement: "Read from aggregated view, no aggregation query"

**Expected Outcome**:
- Audience sees benefit of pre-computed aggregations
- Fast queries without expensive database aggregations

---

### 9.4 Demo Script 4: Service Resilience (Optional Advanced)

**Goal**: Show system continues working even when services are down

**Steps**:
1. Place order (normal flow)
2. **Stop Account Service** (docker-compose stop account-service)
3. **Place Another Order**
   - Order Service still reads from `customer-view` in Hazelcast
   - Order completes successfully even though Account Service is down
4. **Query Orders** - Still works, reading from Hazelcast views

**Expected Outcome**:
- Audience sees decoupling and resilience
- Materialized views enable operation without source services

---

## 10. Non-Functional Requirements

### 10.1 Performance Targets
- **Hazelcast View Read Latency**: <5ms (p99)
- **Event Processing Latency**: <100ms (end-to-end)
- **Order Creation Time**: <200ms (including event publication)
- **Order Query Time**: <10ms (reading from enriched view)

### 10.2 Scalability Considerations
- **Services**: Each service can scale independently (stateless except for Hazelcast client)
- **Hazelcast Cluster**: Can add nodes dynamically, data rebalances automatically
- **Database**: Each service has dedicated database, can shard if needed
- **Bottlenecks**: 
  - Event topic throughput (mitigated by Hazelcast's high throughput)
  - View update processing (can be parallelized)

### 10.3 Observability

#### Logging
- Structured logging (JSON format)
- Log levels: INFO for events, DEBUG for view updates
- Correlation IDs for request tracing

#### Metrics (Micrometer/Prometheus)
- Event publication rate per service
- Event consumption rate per service
- View update latency per view
- Hazelcast map size per view
- API endpoint latency

#### Monitoring View Freshness
- Timestamp in each view entry (`lastUpdated`)
- Metrics: `view.staleness.seconds` = current_time - lastUpdated
- Alerts if staleness exceeds threshold (e.g., >5 seconds)

---

## 11. Open Questions & Decisions Needed

### 11.1 Architecture Decisions
- [x] Use Hazelcast Topics vs external message bus (Kafka) → **Decision: Hazelcast Topics for simplicity**
- [ ] Should we implement CQRS fully or just materialized views?
- [ ] Event versioning strategy (if event schemas change)?
- [ ] Cold start: rebuild views from database vs event replay?

### 11.2 Implementation Details
- [ ] Use Spring Boot 3.x with Spring Data Hazelcast or plain Hazelcast client?
- [ ] JPA/Hibernate or JDBC Template for database access?
- [ ] Event serialization: JSON vs Avro vs Protobuf?
- [ ] Testing strategy: integration tests with embedded Hazelcast?

### 11.3 Demo Scope
- [x] 3 services (Account, Inventory, Order) → **Confirmed**
- [ ] Include API Gateway for routing? (probably not needed for demo)
- [ ] Include monitoring stack (Prometheus + Grafana)? (nice-to-have)
- [ ] Kubernetes deployment example? (future enhancement)

---

## 12. Next Steps

### 12.1 Design Phase ✓
- [x] Define domain model
- [x] Design service boundaries
- [x] Define events and event catalog
- [x] Specify materialized views
- [x] Create demonstration scenarios
- [ ] **Review this document with stakeholders**
- [ ] **Finalize open decisions**

### 12.2 Implementation Phase (Ready for Claude Code)
1. [ ] Setup project structure (framework-core + demo-ecommerce)
2. [ ] Implement framework-core abstractions
3. [ ] Implement Account Service
4. [ ] Implement Inventory Service  
5. [ ] Implement Order Service
6. [ ] Configure Hazelcast cluster
7. [ ] Implement event handlers and view updaters
8. [ ] Create Docker Compose setup
9. [ ] Load sample data scripts
10. [ ] Test end-to-end flows

### 12.3 Documentation Phase
- [ ] README for framework-core
- [ ] README for each service
- [ ] Setup and installation guide
- [ ] Demo walkthrough guide (step-by-step with screenshots)
- [ ] Architecture decision records (ADRs)

### 12.4 Enhancement Ideas (Future)
- [ ] Add payment processing domain as alternative demo
- [ ] Add Saga pattern for complex transactions
- [ ] Add outbox pattern for reliable event publishing
- [ ] Add GraphQL API layer
- [ ] Add real-time UI dashboard showing views updating
- [ ] Add Kubernetes deployment manifests

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| Materialized View | A denormalized, pre-computed view of data maintained in Hazelcast for fast reads without joins or service calls |
| Event Sourcing | Pattern where state changes are captured as a sequence of events (not fully implemented here, just event-driven) |
| Bounded Context | The scope of a microservice's domain responsibility (Account, Inventory, Order are separate contexts) |
| Eventual Consistency | Guarantee that all services will reach the same state eventually (typically <100ms in this system) |
| CQRS | Command Query Responsibility Segregation - separating write models from read models (materialized views are the read models) |
| Event-Driven Architecture | Architecture where services communicate via asynchronous events rather than synchronous API calls |
| Compensating Transaction | A transaction that undoes the effects of a previous transaction (e.g., releasing stock when order cancelled) |
| Denormalization | Storing redundant data to optimize read performance (e.g., storing customer name in order view) |

## Appendix B: References

- **Hazelcast Documentation**: https://docs.hazelcast.com/
- **Hazelcast IMDG Reference**: https://docs.hazelcast.com/imdg/latest/index.html
- **Microservices Patterns** (Chris Richardson): https://microservices.io/patterns/
- **Event-Driven Architecture**: https://martinfowler.com/articles/201701-event-driven.html
- **Materialized Views Pattern**: https://docs.microsoft.com/en-us/azure/architecture/patterns/materialized-view

---

## Document Control

- **Version**: 1.0
- **Last Updated**: 2024-01-15
- **Author**: [Your Name]
- **Status**: Draft / Ready for Review / Approved

