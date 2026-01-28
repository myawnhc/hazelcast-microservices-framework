package com.theyawns.ecommerce.common.domain;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.ecommerce.common.dto.ProductDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Product domain object.
 */
@DisplayName("Product - Product domain object")
class ProductTest {

    private Product product;

    @BeforeEach
    void setUp() {
        product = new Product("prod-123", "SKU-001", "Widget", new BigDecimal("25.00"), 100);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set product ID")
        void shouldSetProductId() {
            assertEquals("prod-123", product.getProductId());
        }

        @Test
        @DisplayName("should set SKU")
        void shouldSetSku() {
            assertEquals("SKU-001", product.getSku());
        }

        @Test
        @DisplayName("should set name")
        void shouldSetName() {
            assertEquals("Widget", product.getName());
        }

        @Test
        @DisplayName("should set price")
        void shouldSetPrice() {
            assertEquals(new BigDecimal("25.00"), product.getPrice());
        }

        @Test
        @DisplayName("should set quantity on hand")
        void shouldSetQuantityOnHand() {
            assertEquals(100, product.getQuantityOnHand());
        }

        @Test
        @DisplayName("should default quantity reserved to 0")
        void shouldDefaultQuantityReservedToZero() {
            assertEquals(0, product.getQuantityReserved());
        }

        @Test
        @DisplayName("should default to ACTIVE status")
        void shouldDefaultToActiveStatus() {
            assertEquals(Product.Status.ACTIVE, product.getStatus());
        }

        @Test
        @DisplayName("should set created timestamp")
        void shouldSetCreatedTimestamp() {
            assertNotNull(product.getCreatedAt());
        }

        @Test
        @DisplayName("default constructor creates empty product")
        void defaultConstructorCreatesEmptyProduct() {
            Product empty = new Product();
            assertNull(empty.getProductId());
            assertNull(empty.getName());
        }
    }

    @Nested
    @DisplayName("Key")
    class Key {

        @Test
        @DisplayName("should return product ID as key")
        void shouldReturnProductIdAsKey() {
            assertEquals("prod-123", product.getKey());
        }
    }

    @Nested
    @DisplayName("Available Quantity")
    class AvailableQuantity {

        @Test
        @DisplayName("should calculate available quantity")
        void shouldCalculateAvailableQuantity() {
            product.setQuantityReserved(30);
            assertEquals(70, product.getAvailableQuantity());
        }

        @Test
        @DisplayName("should be full quantity when nothing reserved")
        void shouldBeFullQuantityWhenNothingReserved() {
            assertEquals(100, product.getAvailableQuantity());
        }
    }

    @Nested
    @DisplayName("Can Reserve")
    class CanReserve {

        @Test
        @DisplayName("should allow reservation when quantity available")
        void shouldAllowReservationWhenQuantityAvailable() {
            assertTrue(product.canReserve(50));
        }

        @Test
        @DisplayName("should not allow reservation when quantity insufficient")
        void shouldNotAllowReservationWhenQuantityInsufficient() {
            assertFalse(product.canReserve(150));
        }

        @Test
        @DisplayName("should not allow reservation when status is not ACTIVE")
        void shouldNotAllowReservationWhenStatusIsNotActive() {
            product.setStatus(Product.Status.DISCONTINUED);
            assertFalse(product.canReserve(10));
        }

        @Test
        @DisplayName("should consider already reserved quantity")
        void shouldConsiderAlreadyReservedQuantity() {
            product.setQuantityReserved(80);
            assertFalse(product.canReserve(30)); // Only 20 available
            assertTrue(product.canReserve(20));
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            product.setDescription("A widget");
            product.setCategory("Electronics");

            GenericRecord record = product.toGenericRecord();

            assertNotNull(record);
            assertEquals("prod-123", record.getString("productId"));
            assertEquals("SKU-001", record.getString("sku"));
            assertEquals("Widget", record.getString("name"));
            assertEquals("A widget", record.getString("description"));
            assertEquals("25.00", record.getString("price"));
            assertEquals(100, record.getInt32("quantityOnHand"));
            assertEquals(0, record.getInt32("quantityReserved"));
            assertEquals("Electronics", record.getString("category"));
            assertEquals(Product.Status.ACTIVE.name(), record.getString("status"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            product.setDescription("A widget");
            product.setCategory("Electronics");

            GenericRecord record = product.toGenericRecord();
            Product deserialized = Product.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(product.getProductId(), deserialized.getProductId());
            assertEquals(product.getSku(), deserialized.getSku());
            assertEquals(product.getName(), deserialized.getName());
            assertEquals(product.getDescription(), deserialized.getDescription());
            assertEquals(product.getPrice(), deserialized.getPrice());
            assertEquals(product.getQuantityOnHand(), deserialized.getQuantityOnHand());
            assertEquals(product.getStatus(), deserialized.getStatus());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(Product.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should handle null status in deserialization")
        void shouldHandleNullStatus() {
            GenericRecord record = GenericRecordBuilder.compact(Product.SCHEMA_NAME)
                    .setString("productId", "prod-1")
                    .setString("sku", "SKU-1")
                    .setString("name", "Item")
                    .setString("description", null)
                    .setString("price", "10.00")
                    .setInt32("quantityOnHand", 50)
                    .setInt32("quantityReserved", 0)
                    .setString("category", null)
                    .setString("status", null)
                    .setInt64("createdAt", 0)
                    .setInt64("updatedAt", 0)
                    .build();

            Product deserialized = Product.fromGenericRecord(record);
            assertEquals(Product.Status.ACTIVE, deserialized.getStatus());
        }

        @Test
        @DisplayName("should handle null price in deserialization")
        void shouldHandleNullPrice() {
            GenericRecord record = GenericRecordBuilder.compact(Product.SCHEMA_NAME)
                    .setString("productId", "prod-1")
                    .setString("sku", "SKU-1")
                    .setString("name", "Item")
                    .setString("description", null)
                    .setString("price", null)
                    .setInt32("quantityOnHand", 50)
                    .setInt32("quantityReserved", 0)
                    .setString("category", null)
                    .setString("status", "ACTIVE")
                    .setInt64("createdAt", 0)
                    .setInt64("updatedAt", 0)
                    .build();

            Product deserialized = Product.fromGenericRecord(record);
            assertNull(deserialized.getPrice());
        }
    }

    @Nested
    @DisplayName("DTO Conversion")
    class DTOConversion {

        @Test
        @DisplayName("should convert to DTO")
        void shouldConvertToDTO() {
            product.setDescription("A widget");
            product.setCategory("Electronics");
            product.setQuantityReserved(10);

            ProductDTO dto = product.toDTO();

            assertNotNull(dto);
            assertEquals(product.getProductId(), dto.getProductId());
            assertEquals(product.getSku(), dto.getSku());
            assertEquals(product.getName(), dto.getName());
            assertEquals(product.getDescription(), dto.getDescription());
            assertEquals(product.getPrice(), dto.getPrice());
            assertEquals(product.getQuantityOnHand(), dto.getQuantityOnHand());
            assertEquals(product.getQuantityReserved(), dto.getQuantityReserved());
            assertEquals(product.getCategory(), dto.getCategory());
            assertEquals(product.getStatus().name(), dto.getStatus());
        }

        @Test
        @DisplayName("should handle null status in DTO")
        void shouldHandleNullStatusInDTO() {
            product.setStatus(null);
            ProductDTO dto = product.toDTO();
            assertNull(dto.getStatus());
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should update product ID")
        void shouldUpdateProductId() {
            product.setProductId("new-id");
            assertEquals("new-id", product.getProductId());
        }

        @Test
        @DisplayName("should update SKU")
        void shouldUpdateSku() {
            product.setSku("NEW-SKU");
            assertEquals("NEW-SKU", product.getSku());
        }

        @Test
        @DisplayName("should update name")
        void shouldUpdateName() {
            product.setName("New Widget");
            assertEquals("New Widget", product.getName());
        }

        @Test
        @DisplayName("should update description")
        void shouldUpdateDescription() {
            product.setDescription("A new widget");
            assertEquals("A new widget", product.getDescription());
        }

        @Test
        @DisplayName("should update price")
        void shouldUpdatePrice() {
            product.setPrice(new BigDecimal("30.00"));
            assertEquals(new BigDecimal("30.00"), product.getPrice());
        }

        @Test
        @DisplayName("should update quantity on hand")
        void shouldUpdateQuantityOnHand() {
            product.setQuantityOnHand(200);
            assertEquals(200, product.getQuantityOnHand());
        }

        @Test
        @DisplayName("should update quantity reserved")
        void shouldUpdateQuantityReserved() {
            product.setQuantityReserved(50);
            assertEquals(50, product.getQuantityReserved());
        }

        @Test
        @DisplayName("should update category")
        void shouldUpdateCategory() {
            product.setCategory("Gadgets");
            assertEquals("Gadgets", product.getCategory());
        }

        @Test
        @DisplayName("should update status")
        void shouldUpdateStatus() {
            product.setStatus(Product.Status.DISCONTINUED);
            assertEquals(Product.Status.DISCONTINUED, product.getStatus());
        }

        @Test
        @DisplayName("should update createdAt")
        void shouldUpdateCreatedAt() {
            Instant newTime = Instant.parse("2024-01-01T00:00:00Z");
            product.setCreatedAt(newTime);
            assertEquals(newTime, product.getCreatedAt());
        }

        @Test
        @DisplayName("should update updatedAt")
        void shouldUpdateUpdatedAt() {
            Instant newTime = Instant.parse("2024-01-01T00:00:00Z");
            product.setUpdatedAt(newTime);
            assertEquals(newTime, product.getUpdatedAt());
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("should equal product with same ID")
        void shouldEqualProductWithSameId() {
            Product other = new Product("prod-123", "DIFF-SKU", "Different", new BigDecimal("99.00"), 500);
            assertEquals(product, other);
        }

        @Test
        @DisplayName("should not equal product with different ID")
        void shouldNotEqualProductWithDifferentId() {
            Product other = new Product("prod-456", "SKU-001", "Widget", new BigDecimal("25.00"), 100);
            assertNotEquals(product, other);
        }

        @Test
        @DisplayName("should not equal null")
        void shouldNotEqualNull() {
            assertNotEquals(null, product);
        }

        @Test
        @DisplayName("should not equal different type")
        void shouldNotEqualDifferentType() {
            assertNotEquals("prod-123", product);
        }

        @Test
        @DisplayName("should equal itself")
        void shouldEqualItself() {
            assertEquals(product, product);
        }
    }

    @Nested
    @DisplayName("HashCode")
    class HashCode {

        @Test
        @DisplayName("should have same hash for equal products")
        void shouldHaveSameHashForEqualProducts() {
            Product other = new Product("prod-123", "DIFF-SKU", "Different", new BigDecimal("99.00"), 500);
            assertEquals(product.hashCode(), other.hashCode());
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(Product.SCHEMA_NAME, product.getSchemaName());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = product.toString();
            assertTrue(str.contains("prod-123"));
            assertTrue(str.contains("SKU-001"));
            assertTrue(str.contains("Widget"));
            assertTrue(str.contains("25.00"));
            assertTrue(str.contains("ACTIVE"));
        }
    }
}
