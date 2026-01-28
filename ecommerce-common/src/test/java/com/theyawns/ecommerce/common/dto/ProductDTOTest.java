package com.theyawns.ecommerce.common.dto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProductDTO.
 */
@DisplayName("ProductDTO - Product Data Transfer Object")
class ProductDTOTest {

    private ProductDTO dto;

    @BeforeEach
    void setUp() {
        dto = new ProductDTO(
                "prod-123", "SKU-001", "Widget", "A widget",
                new BigDecimal("25.00"), 100, 10, "Electronics", "ACTIVE"
        );
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("full constructor should set all fields")
        void fullConstructorShouldSetAllFields() {
            assertEquals("prod-123", dto.getProductId());
            assertEquals("SKU-001", dto.getSku());
            assertEquals("Widget", dto.getName());
            assertEquals("A widget", dto.getDescription());
            assertEquals(new BigDecimal("25.00"), dto.getPrice());
            assertEquals(100, dto.getQuantityOnHand());
            assertEquals(10, dto.getQuantityReserved());
            assertEquals("Electronics", dto.getCategory());
            assertEquals("ACTIVE", dto.getStatus());
        }

        @Test
        @DisplayName("required fields constructor should set basic fields")
        void requiredFieldsConstructorShouldSetBasicFields() {
            ProductDTO simple = new ProductDTO("SKU-002", "Gadget", new BigDecimal("50.00"), 200);
            assertEquals("SKU-002", simple.getSku());
            assertEquals("Gadget", simple.getName());
            assertEquals(new BigDecimal("50.00"), simple.getPrice());
            assertEquals(200, simple.getQuantityOnHand());
        }

        @Test
        @DisplayName("default constructor creates empty DTO")
        void defaultConstructorCreatesEmptyDTO() {
            ProductDTO empty = new ProductDTO();
            assertNull(empty.getProductId());
            assertNull(empty.getName());
        }
    }

    @Nested
    @DisplayName("Available Quantity")
    class AvailableQuantity {

        @Test
        @DisplayName("should calculate available quantity")
        void shouldCalculateAvailableQuantity() {
            assertEquals(90, dto.getAvailableQuantity());
        }

        @Test
        @DisplayName("should return 0 when fully reserved")
        void shouldReturnZeroWhenFullyReserved() {
            dto.setQuantityReserved(100);
            assertEquals(0, dto.getAvailableQuantity());
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should update product ID")
        void shouldUpdateProductId() {
            dto.setProductId("new-id");
            assertEquals("new-id", dto.getProductId());
        }

        @Test
        @DisplayName("should update SKU")
        void shouldUpdateSku() {
            dto.setSku("NEW-SKU");
            assertEquals("NEW-SKU", dto.getSku());
        }

        @Test
        @DisplayName("should update name")
        void shouldUpdateName() {
            dto.setName("New Widget");
            assertEquals("New Widget", dto.getName());
        }

        @Test
        @DisplayName("should update description")
        void shouldUpdateDescription() {
            dto.setDescription("New description");
            assertEquals("New description", dto.getDescription());
        }

        @Test
        @DisplayName("should update price")
        void shouldUpdatePrice() {
            dto.setPrice(new BigDecimal("30.00"));
            assertEquals(new BigDecimal("30.00"), dto.getPrice());
        }

        @Test
        @DisplayName("should update quantity on hand")
        void shouldUpdateQuantityOnHand() {
            dto.setQuantityOnHand(200);
            assertEquals(200, dto.getQuantityOnHand());
        }

        @Test
        @DisplayName("should update quantity reserved")
        void shouldUpdateQuantityReserved() {
            dto.setQuantityReserved(50);
            assertEquals(50, dto.getQuantityReserved());
        }

        @Test
        @DisplayName("should update category")
        void shouldUpdateCategory() {
            dto.setCategory("Gadgets");
            assertEquals("Gadgets", dto.getCategory());
        }

        @Test
        @DisplayName("should update status")
        void shouldUpdateStatus() {
            dto.setStatus("DISCONTINUED");
            assertEquals("DISCONTINUED", dto.getStatus());
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("should equal DTO with same ID and SKU")
        void shouldEqualDTOWithSameIdAndSku() {
            ProductDTO other = new ProductDTO(
                    "prod-123", "SKU-001", "Different", null,
                    new BigDecimal("99.00"), 999, 0, null, null
            );
            assertEquals(dto, other);
        }

        @Test
        @DisplayName("should not equal DTO with different ID")
        void shouldNotEqualDTOWithDifferentId() {
            ProductDTO other = new ProductDTO(
                    "prod-456", "SKU-001", "Widget", "A widget",
                    new BigDecimal("25.00"), 100, 10, "Electronics", "ACTIVE"
            );
            assertNotEquals(dto, other);
        }

        @Test
        @DisplayName("should not equal null")
        void shouldNotEqualNull() {
            assertNotEquals(null, dto);
        }

        @Test
        @DisplayName("should not equal different type")
        void shouldNotEqualDifferentType() {
            assertNotEquals("prod-123", dto);
        }

        @Test
        @DisplayName("should equal itself")
        void shouldEqualItself() {
            assertEquals(dto, dto);
        }
    }

    @Nested
    @DisplayName("HashCode")
    class HashCode {

        @Test
        @DisplayName("should have same hash for equal DTOs")
        void shouldHaveSameHashForEqualDTOs() {
            ProductDTO other = new ProductDTO(
                    "prod-123", "SKU-001", "Different", null,
                    new BigDecimal("99.00"), 999, 0, null, null
            );
            assertEquals(dto.hashCode(), other.hashCode());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = dto.toString();
            assertTrue(str.contains("prod-123"));
            assertTrue(str.contains("SKU-001"));
            assertTrue(str.contains("Widget"));
        }
    }
}
