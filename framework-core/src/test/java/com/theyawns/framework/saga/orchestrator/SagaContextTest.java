package com.theyawns.framework.saga.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaContext.
 */
@DisplayName("SagaContext - Mutable key-value context for saga steps")
class SagaContextTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("create() should return empty context")
        void createShouldReturnEmptyContext() {
            final SagaContext context = SagaContext.create();

            assertEquals(0, context.size());
        }

        @Test
        @DisplayName("of() should pre-populate context with given data")
        void ofShouldPrePopulateContext() {
            final SagaContext context = SagaContext.of(Map.of("key1", "value1", "key2", 42));

            assertEquals(2, context.size());
            assertEquals("value1", context.get("key1"));
            assertEquals(42, context.get("key2"));
        }

        @Test
        @DisplayName("of() should reject null map")
        void ofShouldRejectNullMap() {
            assertThrows(NullPointerException.class, () -> SagaContext.of(null));
        }

        @Test
        @DisplayName("of() should create independent copy of input map")
        void ofShouldCreateIndependentCopy() {
            final java.util.HashMap<String, Object> mutableMap = new java.util.HashMap<>();
            mutableMap.put("key", "original");
            final SagaContext context = SagaContext.of(mutableMap);

            mutableMap.put("key", "modified");

            assertEquals("original", context.get("key"));
        }
    }

    @Nested
    @DisplayName("get and put")
    class GetAndPut {

        @Test
        @DisplayName("put and get should store and retrieve values")
        void putAndGetShouldWork() {
            final SagaContext context = SagaContext.create();
            context.put("orderId", "order-123");

            assertEquals("order-123", context.get("orderId"));
        }

        @Test
        @DisplayName("get should return null for missing key")
        void getShouldReturnNullForMissingKey() {
            final SagaContext context = SagaContext.create();

            assertNull(context.get("nonexistent"));
        }

        @Test
        @DisplayName("put should reject null key")
        void putShouldRejectNullKey() {
            final SagaContext context = SagaContext.create();

            assertThrows(NullPointerException.class, () -> context.put(null, "value"));
        }

        @Test
        @DisplayName("put should reject null value")
        void putShouldRejectNullValue() {
            final SagaContext context = SagaContext.create();

            assertThrows(NullPointerException.class, () -> context.put("key", null));
        }

        @Test
        @DisplayName("get should reject null key")
        void getShouldRejectNullKey() {
            final SagaContext context = SagaContext.create();

            assertThrows(NullPointerException.class, () -> context.get(null));
        }
    }

    @Nested
    @DisplayName("Typed access")
    class TypedAccess {

        @Test
        @DisplayName("get(key, Class) should cast to specified type")
        void typedGetShouldCast() {
            final SagaContext context = SagaContext.create();
            context.put("amount", 99.99);

            final Double amount = context.get("amount", Double.class);

            assertEquals(99.99, amount);
        }

        @Test
        @DisplayName("get(key, Class) should return null for missing key")
        void typedGetShouldReturnNullForMissingKey() {
            final SagaContext context = SagaContext.create();

            assertNull(context.get("missing", String.class));
        }

        @Test
        @DisplayName("get(key, Class) should throw ClassCastException for wrong type")
        void typedGetShouldThrowForWrongType() {
            final SagaContext context = SagaContext.create();
            context.put("count", 42);

            assertThrows(ClassCastException.class, () -> context.get("count", String.class));
        }

        @Test
        @DisplayName("get(key, Class) should reject null type")
        void typedGetShouldRejectNullType() {
            final SagaContext context = SagaContext.create();

            assertThrows(NullPointerException.class, () -> context.get("key", null));
        }
    }

    @Nested
    @DisplayName("getOrDefault")
    class GetOrDefault {

        @Test
        @DisplayName("should return value when key exists")
        void shouldReturnValueWhenPresent() {
            final SagaContext context = SagaContext.create();
            context.put("key", "value");

            assertEquals("value", context.getOrDefault("key", "default"));
        }

        @Test
        @DisplayName("should return default when key is missing")
        void shouldReturnDefaultWhenMissing() {
            final SagaContext context = SagaContext.create();

            assertEquals("default", context.getOrDefault("missing", "default"));
        }
    }

    @Nested
    @DisplayName("containsKey and size")
    class ContainsKeyAndSize {

        @Test
        @DisplayName("containsKey should return true for existing key")
        void containsKeyShouldReturnTrueForExistingKey() {
            final SagaContext context = SagaContext.create();
            context.put("key", "value");

            assertTrue(context.containsKey("key"));
        }

        @Test
        @DisplayName("containsKey should return false for missing key")
        void containsKeyShouldReturnFalseForMissingKey() {
            final SagaContext context = SagaContext.create();

            assertFalse(context.containsKey("missing"));
        }

        @Test
        @DisplayName("size should reflect number of entries")
        void sizeShouldReflectEntries() {
            final SagaContext context = SagaContext.create();
            assertEquals(0, context.size());

            context.put("a", 1);
            context.put("b", 2);
            assertEquals(2, context.size());
        }
    }

    @Nested
    @DisplayName("asUnmodifiableMap")
    class AsUnmodifiableMap {

        @Test
        @DisplayName("should return unmodifiable view")
        void shouldReturnUnmodifiableView() {
            final SagaContext context = SagaContext.create();
            context.put("key", "value");

            final Map<String, Object> map = context.asUnmodifiableMap();

            assertThrows(UnsupportedOperationException.class, () -> map.put("new", "val"));
        }

        @Test
        @DisplayName("should reflect current context state")
        void shouldReflectCurrentState() {
            final SagaContext context = SagaContext.create();
            context.put("key", "value");

            final Map<String, Object> map = context.asUnmodifiableMap();

            assertEquals(1, map.size());
            assertEquals("value", map.get("key"));
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent puts should not lose data")
        void concurrentPutsShouldNotLoseData() throws InterruptedException {
            final SagaContext context = SagaContext.create();
            final int threadCount = 10;
            final int opsPerThread = 100;
            final CountDownLatch latch = new CountDownLatch(threadCount);
            final ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            context.put("t" + threadId + "-k" + i, i);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(threadCount * opsPerThread, context.size());
        }
    }

    @Test
    @DisplayName("toString should include data")
    void toStringShouldIncludeData() {
        final SagaContext context = SagaContext.create();
        context.put("orderId", "123");

        final String str = context.toString();

        assertTrue(str.contains("SagaContext"));
        assertTrue(str.contains("orderId"));
    }
}
