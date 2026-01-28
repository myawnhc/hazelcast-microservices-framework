package com.theyawns.ecommerce.common.dto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CustomerDTO.
 */
@DisplayName("CustomerDTO - Customer Data Transfer Object")
class CustomerDTOTest {

    private CustomerDTO dto;

    @BeforeEach
    void setUp() {
        dto = new CustomerDTO("cust-123", "john@example.com", "John Doe", "123 Main St", "555-1234", "ACTIVE");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("full constructor should set all fields")
        void fullConstructorShouldSetAllFields() {
            assertEquals("cust-123", dto.getCustomerId());
            assertEquals("john@example.com", dto.getEmail());
            assertEquals("John Doe", dto.getName());
            assertEquals("123 Main St", dto.getAddress());
            assertEquals("555-1234", dto.getPhone());
            assertEquals("ACTIVE", dto.getStatus());
        }

        @Test
        @DisplayName("partial constructor should set required fields")
        void partialConstructorShouldSetRequiredFields() {
            CustomerDTO partial = new CustomerDTO("test@example.com", "Test User", "456 Oak Ave");
            assertEquals("test@example.com", partial.getEmail());
            assertEquals("Test User", partial.getName());
            assertEquals("456 Oak Ave", partial.getAddress());
            assertNull(partial.getCustomerId());
            assertNull(partial.getPhone());
            assertNull(partial.getStatus());
        }

        @Test
        @DisplayName("default constructor creates empty DTO")
        void defaultConstructorCreatesEmptyDTO() {
            CustomerDTO empty = new CustomerDTO();
            assertNull(empty.getCustomerId());
            assertNull(empty.getEmail());
            assertNull(empty.getName());
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should update customer ID")
        void shouldUpdateCustomerId() {
            dto.setCustomerId("new-id");
            assertEquals("new-id", dto.getCustomerId());
        }

        @Test
        @DisplayName("should update email")
        void shouldUpdateEmail() {
            dto.setEmail("new@example.com");
            assertEquals("new@example.com", dto.getEmail());
        }

        @Test
        @DisplayName("should update name")
        void shouldUpdateName() {
            dto.setName("Jane Doe");
            assertEquals("Jane Doe", dto.getName());
        }

        @Test
        @DisplayName("should update address")
        void shouldUpdateAddress() {
            dto.setAddress("789 Pine St");
            assertEquals("789 Pine St", dto.getAddress());
        }

        @Test
        @DisplayName("should update phone")
        void shouldUpdatePhone() {
            dto.setPhone("555-9876");
            assertEquals("555-9876", dto.getPhone());
        }

        @Test
        @DisplayName("should update status")
        void shouldUpdateStatus() {
            dto.setStatus("SUSPENDED");
            assertEquals("SUSPENDED", dto.getStatus());
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("should equal DTO with same ID and email")
        void shouldEqualDTOWithSameIdAndEmail() {
            CustomerDTO other = new CustomerDTO("cust-123", "john@example.com", "Different", "Other", null, null);
            assertEquals(dto, other);
        }

        @Test
        @DisplayName("should not equal DTO with different ID")
        void shouldNotEqualDTOWithDifferentId() {
            CustomerDTO other = new CustomerDTO("cust-456", "john@example.com", "John Doe", "123 Main St", "555-1234", "ACTIVE");
            assertNotEquals(dto, other);
        }

        @Test
        @DisplayName("should not equal DTO with different email")
        void shouldNotEqualDTOWithDifferentEmail() {
            CustomerDTO other = new CustomerDTO("cust-123", "different@example.com", "John Doe", "123 Main St", "555-1234", "ACTIVE");
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
            assertNotEquals("cust-123", dto);
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
            CustomerDTO other = new CustomerDTO("cust-123", "john@example.com", "Different", "Other", null, null);
            assertEquals(dto.hashCode(), other.hashCode());
        }

        @Test
        @DisplayName("should have consistent hash code")
        void shouldHaveConsistentHashCode() {
            int hash1 = dto.hashCode();
            int hash2 = dto.hashCode();
            assertEquals(hash1, hash2);
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = dto.toString();
            assertTrue(str.contains("cust-123"));
            assertTrue(str.contains("john@example.com"));
            assertTrue(str.contains("John Doe"));
            assertTrue(str.contains("ACTIVE"));
        }
    }
}
