package com.theyawns.ecommerce.common.domain;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.ecommerce.common.dto.CustomerDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Customer domain object.
 */
@DisplayName("Customer - Customer domain object")
class CustomerTest {

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = new Customer("cust-123", "john@example.com", "John Doe", "123 Main St");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set customer ID")
        void shouldSetCustomerId() {
            assertEquals("cust-123", customer.getCustomerId());
        }

        @Test
        @DisplayName("should set email")
        void shouldSetEmail() {
            assertEquals("john@example.com", customer.getEmail());
        }

        @Test
        @DisplayName("should set name")
        void shouldSetName() {
            assertEquals("John Doe", customer.getName());
        }

        @Test
        @DisplayName("should set address")
        void shouldSetAddress() {
            assertEquals("123 Main St", customer.getAddress());
        }

        @Test
        @DisplayName("should default to ACTIVE status")
        void shouldDefaultToActiveStatus() {
            assertEquals(Customer.Status.ACTIVE, customer.getStatus());
        }

        @Test
        @DisplayName("should set created timestamp")
        void shouldSetCreatedTimestamp() {
            assertNotNull(customer.getCreatedAt());
        }

        @Test
        @DisplayName("should set updated timestamp")
        void shouldSetUpdatedTimestamp() {
            assertNotNull(customer.getUpdatedAt());
        }

        @Test
        @DisplayName("default constructor creates empty customer")
        void defaultConstructorCreatesEmptyCustomer() {
            Customer empty = new Customer();
            assertNull(empty.getCustomerId());
            assertNull(empty.getEmail());
        }
    }

    @Nested
    @DisplayName("Key")
    class Key {

        @Test
        @DisplayName("should return customer ID as key")
        void shouldReturnCustomerIdAsKey() {
            assertEquals("cust-123", customer.getKey());
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            GenericRecord record = customer.toGenericRecord();

            assertNotNull(record);
            assertEquals("cust-123", record.getString("customerId"));
            assertEquals("john@example.com", record.getString("email"));
            assertEquals("John Doe", record.getString("name"));
            assertEquals("123 Main St", record.getString("address"));
            assertEquals(Customer.Status.ACTIVE.name(), record.getString("status"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = customer.toGenericRecord();
            Customer deserialized = Customer.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(customer.getCustomerId(), deserialized.getCustomerId());
            assertEquals(customer.getEmail(), deserialized.getEmail());
            assertEquals(customer.getName(), deserialized.getName());
            assertEquals(customer.getAddress(), deserialized.getAddress());
            assertEquals(customer.getStatus(), deserialized.getStatus());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(Customer.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should handle null status in deserialization")
        void shouldHandleNullStatus() {
            GenericRecord record = GenericRecordBuilder.compact(Customer.SCHEMA_NAME)
                    .setString("customerId", "cust-1")
                    .setString("email", "test@test.com")
                    .setString("name", "Test")
                    .setString("address", "Addr")
                    .setString("phone", null)
                    .setString("status", null)
                    .setInt64("createdAt", 0)
                    .setInt64("updatedAt", 0)
                    .build();

            Customer deserialized = Customer.fromGenericRecord(record);
            assertEquals(Customer.Status.ACTIVE, deserialized.getStatus());
        }
    }

    @Nested
    @DisplayName("DTO Conversion")
    class DTOConversion {

        @Test
        @DisplayName("should convert to DTO")
        void shouldConvertToDTO() {
            customer.setPhone("555-1234");
            CustomerDTO dto = customer.toDTO();

            assertNotNull(dto);
            assertEquals(customer.getCustomerId(), dto.getCustomerId());
            assertEquals(customer.getEmail(), dto.getEmail());
            assertEquals(customer.getName(), dto.getName());
            assertEquals(customer.getAddress(), dto.getAddress());
            assertEquals(customer.getPhone(), dto.getPhone());
            assertEquals(customer.getStatus().name(), dto.getStatus());
        }

        @Test
        @DisplayName("should handle null status in DTO")
        void shouldHandleNullStatusInDTO() {
            customer.setStatus(null);
            CustomerDTO dto = customer.toDTO();
            assertNull(dto.getStatus());
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should update customer ID via setter")
        void shouldUpdateCustomerId() {
            customer.setCustomerId("new-id");
            assertEquals("new-id", customer.getCustomerId());
        }

        @Test
        @DisplayName("should update email via setter")
        void shouldUpdateEmail() {
            customer.setEmail("new@example.com");
            assertEquals("new@example.com", customer.getEmail());
        }

        @Test
        @DisplayName("should update name via setter")
        void shouldUpdateName() {
            customer.setName("Jane Doe");
            assertEquals("Jane Doe", customer.getName());
        }

        @Test
        @DisplayName("should update address via setter")
        void shouldUpdateAddress() {
            customer.setAddress("456 Oak Ave");
            assertEquals("456 Oak Ave", customer.getAddress());
        }

        @Test
        @DisplayName("should update phone via setter")
        void shouldUpdatePhone() {
            customer.setPhone("555-1234");
            assertEquals("555-1234", customer.getPhone());
        }

        @Test
        @DisplayName("should update status via setter")
        void shouldUpdateStatus() {
            customer.setStatus(Customer.Status.SUSPENDED);
            assertEquals(Customer.Status.SUSPENDED, customer.getStatus());
        }

        @Test
        @DisplayName("should update createdAt via setter")
        void shouldUpdateCreatedAt() {
            Instant newTime = Instant.parse("2024-01-01T00:00:00Z");
            customer.setCreatedAt(newTime);
            assertEquals(newTime, customer.getCreatedAt());
        }

        @Test
        @DisplayName("should update updatedAt via setter")
        void shouldUpdateUpdatedAt() {
            Instant newTime = Instant.parse("2024-01-01T00:00:00Z");
            customer.setUpdatedAt(newTime);
            assertEquals(newTime, customer.getUpdatedAt());
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("should equal customer with same ID")
        void shouldEqualCustomerWithSameId() {
            Customer other = new Customer("cust-123", "different@example.com", "Different", "Other");
            assertEquals(customer, other);
        }

        @Test
        @DisplayName("should not equal customer with different ID")
        void shouldNotEqualCustomerWithDifferentId() {
            Customer other = new Customer("cust-456", "john@example.com", "John Doe", "123 Main St");
            assertNotEquals(customer, other);
        }

        @Test
        @DisplayName("should not equal null")
        void shouldNotEqualNull() {
            assertNotEquals(null, customer);
        }

        @Test
        @DisplayName("should not equal different type")
        void shouldNotEqualDifferentType() {
            assertNotEquals("cust-123", customer);
        }

        @Test
        @DisplayName("should equal itself")
        void shouldEqualItself() {
            assertEquals(customer, customer);
        }
    }

    @Nested
    @DisplayName("HashCode")
    class HashCode {

        @Test
        @DisplayName("should have same hash for equal customers")
        void shouldHaveSameHashForEqualCustomers() {
            Customer other = new Customer("cust-123", "different@example.com", "Different", "Other");
            assertEquals(customer.hashCode(), other.hashCode());
        }

        @Test
        @DisplayName("should have consistent hash code")
        void shouldHaveConsistentHashCode() {
            int hash1 = customer.hashCode();
            int hash2 = customer.hashCode();
            assertEquals(hash1, hash2);
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(Customer.SCHEMA_NAME, customer.getSchemaName());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = customer.toString();
            assertTrue(str.contains("cust-123"));
            assertTrue(str.contains("john@example.com"));
            assertTrue(str.contains("John Doe"));
            assertTrue(str.contains("ACTIVE"));
        }
    }
}
