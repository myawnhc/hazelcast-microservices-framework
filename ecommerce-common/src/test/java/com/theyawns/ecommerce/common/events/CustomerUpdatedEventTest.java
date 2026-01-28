package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.ecommerce.common.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CustomerUpdatedEvent.
 */
@DisplayName("CustomerUpdatedEvent - Customer update event")
class CustomerUpdatedEventTest {

    private CustomerUpdatedEvent event;

    @BeforeEach
    void setUp() {
        event = new CustomerUpdatedEvent("cust-123", "Jane Doe", "456 Oak Ave", "555-9999");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(CustomerUpdatedEvent.EVENT_TYPE, event.getEventType());
        }

        @Test
        @DisplayName("should generate event ID")
        void shouldGenerateEventId() {
            assertNotNull(event.getEventId());
            assertFalse(event.getEventId().isEmpty());
        }

        @Test
        @DisplayName("should set customer ID as key")
        void shouldSetCustomerIdAsKey() {
            assertEquals("cust-123", event.getKey());
        }

        @Test
        @DisplayName("should store new name")
        void shouldStoreNewName() {
            assertEquals("Jane Doe", event.getName());
        }

        @Test
        @DisplayName("should store new address")
        void shouldStoreNewAddress() {
            assertEquals("456 Oak Ave", event.getAddress());
        }

        @Test
        @DisplayName("should store new phone")
        void shouldStoreNewPhone() {
            assertEquals("555-9999", event.getPhone());
        }

        @Test
        @DisplayName("default constructor should set event type")
        void defaultConstructorShouldSetEventType() {
            CustomerUpdatedEvent defaultEvent = new CustomerUpdatedEvent();
            assertEquals(CustomerUpdatedEvent.EVENT_TYPE, defaultEvent.getEventType());
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
            assertEquals(event.getEventId(), record.getString("eventId"));
            assertEquals(event.getEventType(), record.getString("eventType"));
            assertEquals(event.getKey(), record.getString("key"));
            assertEquals(event.getName(), record.getString("name"));
            assertEquals(event.getAddress(), record.getString("address"));
            assertEquals(event.getPhone(), record.getString("phone"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            CustomerUpdatedEvent deserialized = CustomerUpdatedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getEventId(), deserialized.getEventId());
            assertEquals(event.getEventType(), deserialized.getEventType());
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getName(), deserialized.getName());
            assertEquals(event.getAddress(), deserialized.getAddress());
            assertEquals(event.getPhone(), deserialized.getPhone());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(CustomerUpdatedEvent.fromGenericRecord(null));
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        private GenericRecord existingCustomerState;

        @BeforeEach
        void setUpExistingState() {
            existingCustomerState = GenericRecordBuilder.compact(Customer.SCHEMA_NAME)
                    .setString("customerId", "cust-123")
                    .setString("email", "john@example.com")
                    .setString("name", "John Doe")
                    .setString("address", "123 Main St")
                    .setString("phone", "555-1234")
                    .setString("status", Customer.Status.ACTIVE.name())
                    .setInt64("createdAt", Instant.now().toEpochMilli())
                    .setInt64("updatedAt", Instant.now().toEpochMilli())
                    .build();
        }

        @Test
        @DisplayName("should update name")
        void shouldUpdateName() {
            GenericRecord updated = event.apply(existingCustomerState);
            assertEquals("Jane Doe", updated.getString("name"));
        }

        @Test
        @DisplayName("should update address")
        void shouldUpdateAddress() {
            GenericRecord updated = event.apply(existingCustomerState);
            assertEquals("456 Oak Ave", updated.getString("address"));
        }

        @Test
        @DisplayName("should update phone")
        void shouldUpdatePhone() {
            GenericRecord updated = event.apply(existingCustomerState);
            assertEquals("555-9999", updated.getString("phone"));
        }

        @Test
        @DisplayName("should preserve email")
        void shouldPreserveEmail() {
            GenericRecord updated = event.apply(existingCustomerState);
            assertEquals("john@example.com", updated.getString("email"));
        }

        @Test
        @DisplayName("should preserve status")
        void shouldPreserveStatus() {
            GenericRecord updated = event.apply(existingCustomerState);
            assertEquals(Customer.Status.ACTIVE.name(), updated.getString("status"));
        }

        @Test
        @DisplayName("should preserve unchanged fields when null")
        void shouldPreserveUnchangedFieldsWhenNull() {
            CustomerUpdatedEvent partialUpdate = new CustomerUpdatedEvent("cust-123", "New Name", null, null);
            GenericRecord updated = partialUpdate.apply(existingCustomerState);

            assertEquals("New Name", updated.getString("name"));
            assertEquals("123 Main St", updated.getString("address")); // Preserved
            assertEquals("555-1234", updated.getString("phone")); // Preserved
        }

        @Test
        @DisplayName("should throw when applied to null state")
        void shouldThrowWhenAppliedToNullState() {
            assertThrows(IllegalStateException.class, () -> event.apply(null));
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should update name via setter")
        void shouldUpdateName() {
            event.setName("New Name");
            assertEquals("New Name", event.getName());
        }

        @Test
        @DisplayName("should update address via setter")
        void shouldUpdateAddress() {
            event.setAddress("New Address");
            assertEquals("New Address", event.getAddress());
        }

        @Test
        @DisplayName("should update phone via setter")
        void shouldUpdatePhone() {
            event.setPhone("555-0000");
            assertEquals("555-0000", event.getPhone());
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(CustomerUpdatedEvent.SCHEMA_NAME, event.getSchemaName());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = event.toString();
            assertTrue(str.contains("cust-123"));
            assertTrue(str.contains("Jane Doe"));
        }
    }
}
