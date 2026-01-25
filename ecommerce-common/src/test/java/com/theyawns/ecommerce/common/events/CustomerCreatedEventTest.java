package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.ecommerce.common.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CustomerCreatedEvent.
 */
@DisplayName("CustomerCreatedEvent - Customer creation event")
class CustomerCreatedEventTest {

    private CustomerCreatedEvent event;

    @BeforeEach
    void setUp() {
        event = new CustomerCreatedEvent(
                "cust-123",
                "john@example.com",
                "John Doe",
                "123 Main St"
        );
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(CustomerCreatedEvent.EVENT_TYPE, event.getEventType());
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
        @DisplayName("should store email")
        void shouldStoreEmail() {
            assertEquals("john@example.com", event.getEmail());
        }

        @Test
        @DisplayName("should store name")
        void shouldStoreName() {
            assertEquals("John Doe", event.getName());
        }

        @Test
        @DisplayName("should store address")
        void shouldStoreAddress() {
            assertEquals("123 Main St", event.getAddress());
        }

        @Test
        @DisplayName("should set timestamp")
        void shouldSetTimestamp() {
            assertNotNull(event.getTimestamp());
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
            assertEquals(event.getEmail(), record.getString("email"));
            assertEquals(event.getName(), record.getString("name"));
            assertEquals(event.getAddress(), record.getString("address"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            CustomerCreatedEvent deserialized = CustomerCreatedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getEventId(), deserialized.getEventId());
            assertEquals(event.getEventType(), deserialized.getEventType());
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getEmail(), deserialized.getEmail());
            assertEquals(event.getName(), deserialized.getName());
            assertEquals(event.getAddress(), deserialized.getAddress());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(CustomerCreatedEvent.fromGenericRecord(null));
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        @Test
        @DisplayName("should create new customer record")
        void shouldCreateNewCustomerRecord() {
            GenericRecord customerRecord = event.apply(null);

            assertNotNull(customerRecord);
            assertEquals("cust-123", customerRecord.getString("customerId"));
            assertEquals("john@example.com", customerRecord.getString("email"));
            assertEquals("John Doe", customerRecord.getString("name"));
            assertEquals("123 Main St", customerRecord.getString("address"));
            assertEquals(Customer.Status.ACTIVE.name(), customerRecord.getString("status"));
        }

        @Test
        @DisplayName("should set timestamps on creation")
        void shouldSetTimestampsOnCreation() {
            GenericRecord customerRecord = event.apply(null);

            assertTrue(customerRecord.getInt64("createdAt") > 0);
            assertTrue(customerRecord.getInt64("updatedAt") > 0);
        }

        @Test
        @DisplayName("should ignore existing state (creation event)")
        void shouldIgnoreExistingState() {
            // Create some existing state
            CustomerCreatedEvent existingEvent = new CustomerCreatedEvent(
                    "cust-123", "old@example.com", "Old Name", "Old Address");
            GenericRecord existingState = existingEvent.apply(null);

            // Apply new creation event
            GenericRecord newState = event.apply(existingState);

            // New event data should override
            assertEquals("john@example.com", newState.getString("email"));
            assertEquals("John Doe", newState.getString("name"));
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(CustomerCreatedEvent.SCHEMA_NAME, event.getSchemaName());
        }
    }
}
