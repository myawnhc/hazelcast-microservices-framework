package com.theyawns.framework.store;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.domain.DomainObject;
import com.theyawns.framework.event.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HydrationFactory - Event and domain object deserialization")
class HydrationFactoryTest {

    private HydrationFactory<TestDomainObject, String> factory;

    @BeforeEach
    void setUp() {
        factory = new HydrationFactory<>();
    }

    @Test
    @DisplayName("should register and use event hydrator")
    void shouldRegisterAndUseEventHydrator() {
        // Arrange
        factory.registerEventHydrator("TestCreated", TestCreatedEvent::fromGenericRecord);
        GenericRecord record = createEventRecord("TestCreated", "entity-1", "Test data");

        // Act
        DomainEvent<TestDomainObject, String> event = factory.hydrateEvent(record);

        // Assert
        assertNotNull(event);
        assertTrue(event instanceof TestCreatedEvent);
        assertEquals("entity-1", event.getKey());
    }

    @Test
    @DisplayName("should register multiple event hydrators")
    void shouldRegisterMultipleEventHydrators() {
        // Arrange
        factory.registerEventHydrator("TestCreated", TestCreatedEvent::fromGenericRecord)
               .registerEventHydrator("TestUpdated", TestUpdatedEvent::fromGenericRecord);

        GenericRecord createdRecord = createEventRecord("TestCreated", "e1", "created");
        GenericRecord updatedRecord = createEventRecord("TestUpdated", "e2", "updated");

        // Act
        DomainEvent<TestDomainObject, String> created = factory.hydrateEvent(createdRecord);
        DomainEvent<TestDomainObject, String> updated = factory.hydrateEvent(updatedRecord);

        // Assert
        assertTrue(created instanceof TestCreatedEvent);
        assertTrue(updated instanceof TestUpdatedEvent);
    }

    @Test
    @DisplayName("should throw exception for unregistered event type")
    void shouldThrowExceptionForUnregisteredEventType() {
        // Arrange
        GenericRecord record = createEventRecord("UnknownEvent", "e1", "data");

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> factory.hydrateEvent(record));
    }

    @Test
    @DisplayName("should throw exception when record has no eventType")
    void shouldThrowExceptionWhenRecordHasNoEventType() {
        // Arrange
        GenericRecord record = GenericRecordBuilder.compact("NoTypeRecord")
                .setString("key", "e1")
                .build();

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.hydrateEvent(record));
    }

    @Test
    @DisplayName("should try hydrate event and return Optional")
    void shouldTryHydrateEventAndReturnOptional() {
        // Arrange
        factory.registerEventHydrator("TestCreated", TestCreatedEvent::fromGenericRecord);
        GenericRecord knownRecord = createEventRecord("TestCreated", "e1", "data");
        GenericRecord unknownRecord = createEventRecord("Unknown", "e2", "data");

        // Act
        Optional<DomainEvent<TestDomainObject, String>> known = factory.tryHydrateEvent(knownRecord);
        Optional<DomainEvent<TestDomainObject, String>> unknown = factory.tryHydrateEvent(unknownRecord);

        // Assert
        assertTrue(known.isPresent());
        assertTrue(unknown.isEmpty());
    }

    @Test
    @DisplayName("should register and use domain object hydrator")
    void shouldRegisterAndUseDomainObjectHydrator() {
        // Arrange
        factory.registerDomainObjectHydrator(TestDomainObject::fromGenericRecord);
        GenericRecord record = GenericRecordBuilder.compact("TestDomainObject")
                .setString("id", "domain-123")
                .setString("name", "Test Entity")
                .build();

        // Act
        TestDomainObject domainObject = factory.hydrateDomainObject(record);

        // Assert
        assertNotNull(domainObject);
        assertEquals("domain-123", domainObject.getKey());
        assertEquals("Test Entity", domainObject.getName());
    }

    @Test
    @DisplayName("should throw exception when no domain object hydrator registered")
    void shouldThrowExceptionWhenNoDomainObjectHydrator() {
        // Arrange
        GenericRecord record = GenericRecordBuilder.compact("Test")
                .setString("id", "test")
                .build();

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.hydrateDomainObject(record));
    }

    @Test
    @DisplayName("should try hydrate domain object and return Optional")
    void shouldTryHydrateDomainObjectAndReturnOptional() {
        // Arrange
        GenericRecord record = GenericRecordBuilder.compact("TestDomainObject")
                .setString("id", "test-id")
                .setString("name", "Test")
                .build();

        // Act - without hydrator
        Optional<TestDomainObject> withoutHydrator = factory.tryHydrateDomainObject(record);

        // Register hydrator
        factory.registerDomainObjectHydrator(TestDomainObject::fromGenericRecord);

        // Act - with hydrator
        Optional<TestDomainObject> withHydrator = factory.tryHydrateDomainObject(record);

        // Assert
        assertTrue(withoutHydrator.isEmpty());
        assertTrue(withHydrator.isPresent());
    }

    @Test
    @DisplayName("should check if event hydrator exists")
    void shouldCheckIfEventHydratorExists() {
        // Arrange
        factory.registerEventHydrator("TestCreated", TestCreatedEvent::fromGenericRecord);

        // Assert
        assertTrue(factory.hasEventHydrator("TestCreated"));
        assertFalse(factory.hasEventHydrator("TestUpdated"));
    }

    @Test
    @DisplayName("should check if domain object hydrator exists")
    void shouldCheckIfDomainObjectHydratorExists() {
        // Assert - before registration
        assertFalse(factory.hasDomainObjectHydrator());

        // Act
        factory.registerDomainObjectHydrator(TestDomainObject::fromGenericRecord);

        // Assert - after registration
        assertTrue(factory.hasDomainObjectHydrator());
    }

    @Test
    @DisplayName("should return registered event types")
    void shouldReturnRegisteredEventTypes() {
        // Arrange
        factory.registerEventHydrator("TypeA", TestCreatedEvent::fromGenericRecord)
               .registerEventHydrator("TypeB", TestCreatedEvent::fromGenericRecord)
               .registerEventHydrator("TypeC", TestCreatedEvent::fromGenericRecord);

        // Act
        Set<String> types = factory.getRegisteredEventTypes();

        // Assert
        assertEquals(3, types.size());
        assertTrue(types.contains("TypeA"));
        assertTrue(types.contains("TypeB"));
        assertTrue(types.contains("TypeC"));
    }

    @Test
    @DisplayName("should clear all hydrators")
    void shouldClearAllHydrators() {
        // Arrange
        factory.registerEventHydrator("TestCreated", TestCreatedEvent::fromGenericRecord)
               .registerDomainObjectHydrator(TestDomainObject::fromGenericRecord);

        // Act
        factory.clear();

        // Assert
        assertFalse(factory.hasEventHydrator("TestCreated"));
        assertFalse(factory.hasDomainObjectHydrator());
        assertTrue(factory.getRegisteredEventTypes().isEmpty());
    }

    @Test
    @DisplayName("should throw exception for null event type")
    void shouldThrowExceptionForNullEventType() {
        // Act & Assert
        assertThrows(NullPointerException.class, () ->
                factory.registerEventHydrator(null, TestCreatedEvent::fromGenericRecord)
        );
    }

    @Test
    @DisplayName("should throw exception for null hydrator function")
    void shouldThrowExceptionForNullHydratorFunction() {
        // Act & Assert
        assertThrows(NullPointerException.class, () ->
                factory.registerEventHydrator("Test", null)
        );
    }

    @Test
    @DisplayName("should support fluent registration")
    void shouldSupportFluentRegistration() {
        // Act
        HydrationFactory<TestDomainObject, String> result = factory
                .registerEventHydrator("A", TestCreatedEvent::fromGenericRecord)
                .registerEventHydrator("B", TestUpdatedEvent::fromGenericRecord)
                .registerDomainObjectHydrator(TestDomainObject::fromGenericRecord);

        // Assert
        assertSame(factory, result);
        assertEquals(2, factory.getRegisteredEventTypes().size());
        assertTrue(factory.hasDomainObjectHydrator());
    }

    // Helper methods

    private GenericRecord createEventRecord(String eventType, String key, String data) {
        return GenericRecordBuilder.compact("TestEvent")
                .setString("eventId", java.util.UUID.randomUUID().toString())
                .setString("eventType", eventType)
                .setString("key", key)
                .setString("data", data)
                .build();
    }

    // Test domain classes

    static class TestDomainObject implements DomainObject<String> {
        private final String id;
        private final String name;

        TestDomainObject(String id, String name) {
            this.id = id;
            this.name = name;
        }

        static TestDomainObject fromGenericRecord(GenericRecord record) {
            return new TestDomainObject(
                    record.getString("id"),
                    record.getString("name")
            );
        }

        @Override
        public String getKey() {
            return id;
        }

        public String getName() {
            return name;
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact("TestDomainObject")
                    .setString("id", id)
                    .setString("name", name)
                    .build();
        }

        @Override
        public String getSchemaName() {
            return "TestDomainObject";
        }
    }

    static class TestCreatedEvent extends DomainEvent<TestDomainObject, String> {
        private final String data;

        TestCreatedEvent(String key, String data) {
            super(key);
            this.eventType = "TestCreated";
            this.data = data;
        }

        static TestCreatedEvent fromGenericRecord(GenericRecord record) {
            return new TestCreatedEvent(
                    record.getString("key"),
                    record.getString("data")
            );
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact("TestCreatedEvent")
                    .setString("eventId", eventId)
                    .setString("eventType", eventType)
                    .setString("key", key)
                    .setString("data", data)
                    .build();
        }

        @Override
        public GenericRecord apply(GenericRecord domainObjectRecord) {
            return domainObjectRecord;
        }

        @Override
        public String getSchemaName() {
            return "TestCreatedEvent";
        }
    }

    static class TestUpdatedEvent extends DomainEvent<TestDomainObject, String> {
        private final String data;

        TestUpdatedEvent(String key, String data) {
            super(key);
            this.eventType = "TestUpdated";
            this.data = data;
        }

        static TestUpdatedEvent fromGenericRecord(GenericRecord record) {
            return new TestUpdatedEvent(
                    record.getString("key"),
                    record.getString("data")
            );
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact("TestUpdatedEvent")
                    .setString("eventId", eventId)
                    .setString("eventType", eventType)
                    .setString("key", key)
                    .setString("data", data)
                    .build();
        }

        @Override
        public GenericRecord apply(GenericRecord domainObjectRecord) {
            return domainObjectRecord;
        }

        @Override
        public String getSchemaName() {
            return "TestUpdatedEvent";
        }
    }
}
