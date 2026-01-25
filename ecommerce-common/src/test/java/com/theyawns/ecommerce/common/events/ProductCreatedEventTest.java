package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.ecommerce.common.domain.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProductCreatedEvent.
 */
@DisplayName("ProductCreatedEvent - Product creation event")
class ProductCreatedEventTest {

    private ProductCreatedEvent event;

    @BeforeEach
    void setUp() {
        event = new ProductCreatedEvent(
                "prod-123",
                "SKU-001",
                "Test Product",
                new BigDecimal("29.99"),
                100
        );
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(ProductCreatedEvent.EVENT_TYPE, event.getEventType());
        }

        @Test
        @DisplayName("should set product ID as key")
        void shouldSetProductIdAsKey() {
            assertEquals("prod-123", event.getKey());
        }

        @Test
        @DisplayName("should store SKU")
        void shouldStoreSku() {
            assertEquals("SKU-001", event.getSku());
        }

        @Test
        @DisplayName("should store name")
        void shouldStoreName() {
            assertEquals("Test Product", event.getName());
        }

        @Test
        @DisplayName("should store price")
        void shouldStorePrice() {
            assertEquals(new BigDecimal("29.99"), event.getPrice());
        }

        @Test
        @DisplayName("should store initial quantity")
        void shouldStoreInitialQuantity() {
            assertEquals(100, event.getInitialQuantity());
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            GenericRecord record = event.toGenericRecord();

            assertNotNull(record);
            assertEquals(event.getKey(), record.getString("key"));
            assertEquals(event.getSku(), record.getString("sku"));
            assertEquals(event.getName(), record.getString("name"));
            assertEquals(event.getPrice().toString(), record.getString("price"));
            assertEquals(event.getInitialQuantity(), record.getInt32("initialQuantity"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            ProductCreatedEvent deserialized = ProductCreatedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getSku(), deserialized.getSku());
            assertEquals(event.getName(), deserialized.getName());
            assertEquals(0, event.getPrice().compareTo(deserialized.getPrice()));
            assertEquals(event.getInitialQuantity(), deserialized.getInitialQuantity());
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        @Test
        @DisplayName("should create new product record")
        void shouldCreateNewProductRecord() {
            GenericRecord productRecord = event.apply(null);

            assertNotNull(productRecord);
            assertEquals("prod-123", productRecord.getString("productId"));
            assertEquals("SKU-001", productRecord.getString("sku"));
            assertEquals("Test Product", productRecord.getString("name"));
            assertEquals("29.99", productRecord.getString("price"));
            assertEquals(100, productRecord.getInt32("quantityOnHand"));
            assertEquals(0, productRecord.getInt32("quantityReserved"));
            assertEquals(Product.Status.ACTIVE.name(), productRecord.getString("status"));
        }
    }
}
