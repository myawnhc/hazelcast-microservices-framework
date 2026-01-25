package com.theyawns.framework.pipeline;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.topic.ITopic;
import com.theyawns.framework.domain.DomainObject;
import com.theyawns.framework.event.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HazelcastEventBus.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HazelcastEventBus")
class HazelcastEventBusTest {

    @Mock
    private HazelcastInstance hazelcast;

    @Mock
    private ITopic<DomainEvent<TestDomainObject, String>> topic;

    private HazelcastEventBus<TestDomainObject, String> eventBus;
    private static final String DOMAIN_NAME = "TestDomain";
    private static final String EXPECTED_TOPIC_NAME = "TestDomain_EVENTS";

    @BeforeEach
    void setUp() {
        when(hazelcast.<DomainEvent<TestDomainObject, String>>getTopic(EXPECTED_TOPIC_NAME))
                .thenReturn(topic);
        eventBus = new HazelcastEventBus<>(hazelcast, DOMAIN_NAME);
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create event bus with correct topic name")
        void shouldCreateEventBusWithCorrectTopicName() {
            assertEquals(EXPECTED_TOPIC_NAME, eventBus.getTopicName());
            verify(hazelcast).getTopic(EXPECTED_TOPIC_NAME);
        }

        @Test
        @DisplayName("should throw on null hazelcast instance")
        void shouldThrowOnNullHazelcast() {
            assertThrows(NullPointerException.class,
                    () -> new HazelcastEventBus<>(null, DOMAIN_NAME));
        }

        @Test
        @DisplayName("should throw on null domain name")
        void shouldThrowOnNullDomainName() {
            assertThrows(NullPointerException.class,
                    () -> new HazelcastEventBus<>(hazelcast, null));
        }

        @Test
        @DisplayName("should create event bus with explicit topic name")
        void shouldCreateEventBusWithExplicitTopicName() {
            String explicitTopic = "CustomTopicName";
            when(hazelcast.<DomainEvent<TestDomainObject, String>>getTopic(explicitTopic))
                    .thenReturn(topic);

            HazelcastEventBus<TestDomainObject, String> customBus =
                    new HazelcastEventBus<>(hazelcast, explicitTopic, true);

            assertEquals(explicitTopic, customBus.getTopicName());
        }
    }

    @Nested
    @DisplayName("Publishing")
    class PublishingTests {

        @Test
        @DisplayName("should publish event asynchronously")
        void shouldPublishEventAsync() throws Exception {
            TestEvent event = new TestEvent("key-1");

            CompletableFuture<Void> future = eventBus.publish(event);

            future.get(); // Wait for completion
            verify(topic).publish(event);
        }

        @Test
        @DisplayName("should publish event synchronously")
        void shouldPublishEventSync() {
            TestEvent event = new TestEvent("key-1");

            eventBus.publishSync(event);

            verify(topic).publish(event);
        }

        @Test
        @DisplayName("should throw on null event for async publish")
        void shouldThrowOnNullEventAsync() {
            assertThrows(NullPointerException.class,
                    () -> eventBus.publish(null));
        }

        @Test
        @DisplayName("should throw on null event for sync publish")
        void shouldThrowOnNullEventSync() {
            assertThrows(NullPointerException.class,
                    () -> eventBus.publishSync(null));
        }

        @Test
        @DisplayName("should propagate exception from async publish")
        void shouldPropagateExceptionFromAsyncPublish() throws Exception {
            TestEvent event = new TestEvent("key-1");
            doThrow(new RuntimeException("Topic error")).when(topic).publish(event);

            CompletableFuture<Void> future = eventBus.publish(event);

            // Wait for async completion
            try {
                future.get();
                fail("Expected exception");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(e.getCause() instanceof RuntimeException);
            }
        }

        @Test
        @DisplayName("should throw exception from sync publish")
        void shouldThrowExceptionFromSyncPublish() {
            TestEvent event = new TestEvent("key-1");
            doThrow(new RuntimeException("Topic error")).when(topic).publish(event);

            assertThrows(RuntimeException.class, () -> eventBus.publishSync(event));
        }
    }

    @Nested
    @DisplayName("Subscribing")
    class SubscribingTests {

        @Test
        @DisplayName("should subscribe to all events")
        void shouldSubscribeToAllEvents() {
            UUID registrationId = UUID.randomUUID();
            when(topic.addMessageListener(any())).thenReturn(registrationId);

            AtomicReference<DomainEvent<TestDomainObject, String>> received = new AtomicReference<>();
            String subId = eventBus.subscribe(received::set);

            assertNotNull(subId);
            assertTrue(subId.startsWith("sub-"));
            assertEquals(1, eventBus.getSubscriptionCount());
            verify(topic).addMessageListener(any());
        }

        @Test
        @DisplayName("should subscribe to specific event type")
        void shouldSubscribeToSpecificEventType() {
            UUID registrationId = UUID.randomUUID();
            when(topic.addMessageListener(any())).thenReturn(registrationId);

            AtomicReference<DomainEvent<TestDomainObject, String>> received = new AtomicReference<>();
            String subId = eventBus.subscribe("TestCreated", received::set);

            assertNotNull(subId);
            assertEquals(1, eventBus.getSubscriptionCount());
        }

        @Test
        @DisplayName("should throw on null handler")
        void shouldThrowOnNullHandler() {
            assertThrows(NullPointerException.class,
                    () -> eventBus.subscribe((Consumer<DomainEvent<TestDomainObject, String>>) null));
        }

        @Test
        @DisplayName("should throw on null event type")
        void shouldThrowOnNullEventType() {
            assertThrows(NullPointerException.class,
                    () -> eventBus.subscribe(null, event -> {}));
        }

        @Test
        @DisplayName("should throw on null handler with event type")
        void shouldThrowOnNullHandlerWithEventType() {
            assertThrows(NullPointerException.class,
                    () -> eventBus.subscribe("TestCreated", null));
        }

        // Note: Tests that simulate message delivery are better suited for integration tests
        // because Hazelcast's Message is a class (not interface) that cannot be easily mocked.
    }

    @Nested
    @DisplayName("Unsubscribing")
    class UnsubscribingTests {

        @Test
        @DisplayName("should unsubscribe successfully")
        void shouldUnsubscribeSuccessfully() {
            UUID registrationId = UUID.randomUUID();
            when(topic.addMessageListener(any())).thenReturn(registrationId);
            when(topic.removeMessageListener(registrationId)).thenReturn(true);

            String subId = eventBus.subscribe(event -> {});
            boolean result = eventBus.unsubscribe(subId);

            assertTrue(result);
            assertEquals(0, eventBus.getSubscriptionCount());
            verify(topic).removeMessageListener(registrationId);
        }

        @Test
        @DisplayName("should return false for unknown subscription")
        void shouldReturnFalseForUnknownSubscription() {
            boolean result = eventBus.unsubscribe("unknown-sub-id");

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for null subscription")
        void shouldReturnFalseForNullSubscription() {
            boolean result = eventBus.unsubscribe(null);

            assertFalse(result);
        }

        @Test
        @DisplayName("should remove all subscriptions")
        void shouldRemoveAllSubscriptions() {
            UUID regId1 = UUID.randomUUID();
            UUID regId2 = UUID.randomUUID();
            when(topic.addMessageListener(any()))
                    .thenReturn(regId1)
                    .thenReturn(regId2);
            when(topic.removeMessageListener(any())).thenReturn(true);

            eventBus.subscribe(event -> {});
            eventBus.subscribe("TestCreated", event -> {});
            assertEquals(2, eventBus.getSubscriptionCount());

            eventBus.removeAllSubscriptions();

            assertEquals(0, eventBus.getSubscriptionCount());
            verify(topic, times(2)).removeMessageListener(any());
        }
    }

    @Nested
    @DisplayName("Accessors")
    class AccessorTests {

        @Test
        @DisplayName("should return underlying topic")
        void shouldReturnUnderlyingTopic() {
            assertSame(topic, eventBus.getTopic());
        }

        @Test
        @DisplayName("should return correct topic name")
        void shouldReturnCorrectTopicName() {
            assertEquals(EXPECTED_TOPIC_NAME, eventBus.getTopicName());
        }
    }

    // ==================== Test Helpers ====================

    /**
     * Test domain object implementation.
     */
    private static class TestDomainObject implements DomainObject<String> {
        private final String key;

        TestDomainObject(String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public GenericRecord toGenericRecord() {
            return null;
        }

        @Override
        public String getSchemaName() {
            return "TestDomainObject";
        }
    }

    /**
     * Test event implementation.
     */
    private static class TestEvent extends DomainEvent<TestDomainObject, String> {

        TestEvent(String key) {
            super(key);
            this.eventType = "TestEvent";
        }

        @Override
        public GenericRecord toGenericRecord() {
            return null;
        }

        @Override
        public GenericRecord apply(GenericRecord domainObjectRecord) {
            return domainObjectRecord;
        }

        @Override
        public String getSchemaName() {
            return "TestEvent";
        }
    }

}
