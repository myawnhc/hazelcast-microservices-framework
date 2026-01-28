package com.theyawns.ecommerce.common.domain;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.ecommerce.common.dto.OrderLineItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OrderLineItem domain object.
 */
@DisplayName("OrderLineItem - Order line item domain object")
class OrderLineItemTest {

    private OrderLineItem lineItem;

    @BeforeEach
    void setUp() {
        lineItem = new OrderLineItem("prod-123", "Widget", "SKU-001", 3, new BigDecimal("25.00"));
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set product ID")
        void shouldSetProductId() {
            assertEquals("prod-123", lineItem.getProductId());
        }

        @Test
        @DisplayName("should set product name")
        void shouldSetProductName() {
            assertEquals("Widget", lineItem.getProductName());
        }

        @Test
        @DisplayName("should set SKU")
        void shouldSetSku() {
            assertEquals("SKU-001", lineItem.getSku());
        }

        @Test
        @DisplayName("should set quantity")
        void shouldSetQuantity() {
            assertEquals(3, lineItem.getQuantity());
        }

        @Test
        @DisplayName("should set unit price")
        void shouldSetUnitPrice() {
            assertEquals(new BigDecimal("25.00"), lineItem.getUnitPrice());
        }

        @Test
        @DisplayName("should calculate line total")
        void shouldCalculateLineTotal() {
            // 3 * 25.00 = 75.00
            assertEquals(new BigDecimal("75.00"), lineItem.getLineTotal());
        }

        @Test
        @DisplayName("default constructor creates empty item")
        void defaultConstructorCreatesEmptyItem() {
            OrderLineItem empty = new OrderLineItem();
            assertNull(empty.getProductId());
            assertNull(empty.getProductName());
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            GenericRecord record = lineItem.toGenericRecord();

            assertNotNull(record);
            assertEquals("prod-123", record.getString("productId"));
            assertEquals("Widget", record.getString("productName"));
            assertEquals("SKU-001", record.getString("sku"));
            assertEquals(3, record.getInt32("quantity"));
            assertEquals("25.00", record.getString("unitPrice"));
            assertEquals("75.00", record.getString("lineTotal"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = lineItem.toGenericRecord();
            OrderLineItem deserialized = OrderLineItem.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(lineItem.getProductId(), deserialized.getProductId());
            assertEquals(lineItem.getProductName(), deserialized.getProductName());
            assertEquals(lineItem.getSku(), deserialized.getSku());
            assertEquals(lineItem.getQuantity(), deserialized.getQuantity());
            assertEquals(lineItem.getUnitPrice(), deserialized.getUnitPrice());
            assertEquals(lineItem.getLineTotal(), deserialized.getLineTotal());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(OrderLineItem.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should handle null prices in deserialization")
        void shouldHandleNullPrices() {
            GenericRecord record = GenericRecordBuilder.compact(OrderLineItem.SCHEMA_NAME)
                    .setString("productId", "prod-1")
                    .setString("productName", "Item")
                    .setString("sku", "SKU-1")
                    .setInt32("quantity", 1)
                    .setString("unitPrice", null)
                    .setString("lineTotal", null)
                    .build();

            OrderLineItem deserialized = OrderLineItem.fromGenericRecord(record);
            assertNull(deserialized.getUnitPrice());
            assertNull(deserialized.getLineTotal());
        }
    }

    @Nested
    @DisplayName("DTO Conversion")
    class DTOConversion {

        @Test
        @DisplayName("should convert to DTO")
        void shouldConvertToDTO() {
            OrderLineItemDTO dto = lineItem.toDTO();

            assertNotNull(dto);
            assertEquals(lineItem.getProductId(), dto.getProductId());
            assertEquals(lineItem.getProductName(), dto.getProductName());
            assertEquals(lineItem.getSku(), dto.getSku());
            assertEquals(lineItem.getQuantity(), dto.getQuantity());
            assertEquals(lineItem.getUnitPrice(), dto.getUnitPrice());
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should update product ID via setter")
        void shouldUpdateProductId() {
            lineItem.setProductId("new-prod");
            assertEquals("new-prod", lineItem.getProductId());
        }

        @Test
        @DisplayName("should update product name via setter")
        void shouldUpdateProductName() {
            lineItem.setProductName("New Widget");
            assertEquals("New Widget", lineItem.getProductName());
        }

        @Test
        @DisplayName("should update SKU via setter")
        void shouldUpdateSku() {
            lineItem.setSku("SKU-NEW");
            assertEquals("SKU-NEW", lineItem.getSku());
        }

        @Test
        @DisplayName("should recalculate total when quantity changes")
        void shouldRecalculateTotalWhenQuantityChanges() {
            lineItem.setQuantity(5);
            // 5 * 25.00 = 125.00
            assertEquals(new BigDecimal("125.00"), lineItem.getLineTotal());
        }

        @Test
        @DisplayName("should recalculate total when price changes")
        void shouldRecalculateTotalWhenPriceChanges() {
            lineItem.setUnitPrice(new BigDecimal("30.00"));
            // 3 * 30.00 = 90.00
            assertEquals(new BigDecimal("90.00"), lineItem.getLineTotal());
        }

        @Test
        @DisplayName("should handle null unit price when setting quantity")
        void shouldHandleNullUnitPriceWhenSettingQuantity() {
            OrderLineItem item = new OrderLineItem();
            item.setUnitPrice(null);
            item.setQuantity(5);
            // Should not throw, lineTotal should remain null
            assertNull(item.getLineTotal());
        }

        @Test
        @DisplayName("should allow direct line total setter")
        void shouldAllowDirectLineTotalSetter() {
            lineItem.setLineTotal(new BigDecimal("100.00"));
            assertEquals(new BigDecimal("100.00"), lineItem.getLineTotal());
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("should equal item with same product ID")
        void shouldEqualItemWithSameProductId() {
            OrderLineItem other = new OrderLineItem("prod-123", "Different", "SKU-2", 10, new BigDecimal("99.00"));
            assertEquals(lineItem, other);
        }

        @Test
        @DisplayName("should not equal item with different product ID")
        void shouldNotEqualItemWithDifferentProductId() {
            OrderLineItem other = new OrderLineItem("prod-456", "Widget", "SKU-001", 3, new BigDecimal("25.00"));
            assertNotEquals(lineItem, other);
        }

        @Test
        @DisplayName("should not equal null")
        void shouldNotEqualNull() {
            assertNotEquals(null, lineItem);
        }

        @Test
        @DisplayName("should not equal different type")
        void shouldNotEqualDifferentType() {
            assertNotEquals("prod-123", lineItem);
        }

        @Test
        @DisplayName("should equal itself")
        void shouldEqualItself() {
            assertEquals(lineItem, lineItem);
        }
    }

    @Nested
    @DisplayName("HashCode")
    class HashCode {

        @Test
        @DisplayName("should have same hash for equal items")
        void shouldHaveSameHashForEqualItems() {
            OrderLineItem other = new OrderLineItem("prod-123", "Different", "SKU-2", 10, new BigDecimal("99.00"));
            assertEquals(lineItem.hashCode(), other.hashCode());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = lineItem.toString();
            assertTrue(str.contains("prod-123"));
            assertTrue(str.contains("Widget"));
            assertTrue(str.contains("3"));
            assertTrue(str.contains("25.00"));
            assertTrue(str.contains("75.00"));
        }
    }
}
