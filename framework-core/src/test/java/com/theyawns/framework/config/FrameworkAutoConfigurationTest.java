package com.theyawns.framework.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FrameworkAutoConfiguration.
 */
@DisplayName("FrameworkAutoConfiguration - Spring Boot auto-configuration")
class FrameworkAutoConfigurationTest {

    private FrameworkAutoConfiguration autoConfig;

    @BeforeEach
    void setUp() {
        autoConfig = new FrameworkAutoConfiguration();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should construct without errors")
        void shouldConstructWithoutErrors() {
            // Simply verify the constructor doesn't throw
            FrameworkAutoConfiguration config = new FrameworkAutoConfiguration();
            assertNotNull(config);
        }
    }

    @Nested
    @DisplayName("Fallback meter registry")
    class FallbackMeterRegistry {

        @Test
        @DisplayName("should create SimpleMeterRegistry as fallback")
        void shouldCreateSimpleMeterRegistryAsFallback() {
            MeterRegistry registry = autoConfig.meterRegistry();

            assertNotNull(registry);
            assertTrue(registry instanceof SimpleMeterRegistry);
        }

        @Test
        @DisplayName("fallback registry should be functional")
        void fallbackRegistryShouldBeFunctional() {
            MeterRegistry registry = autoConfig.meterRegistry();

            // Test that we can create and use meters
            registry.counter("test.counter").increment();
            registry.timer("test.timer").record(() -> { /* do nothing */ });

            // Verify meters exist
            assertNotNull(registry.find("test.counter").counter());
            assertNotNull(registry.find("test.timer").timer());
        }

        @Test
        @DisplayName("fallback registry should allow metrics operations")
        void fallbackRegistryShouldAllowMetricsOperations() {
            MeterRegistry registry = autoConfig.meterRegistry();

            // Create multiple types of meters
            registry.counter("events.total", "type", "create").increment(5);
            registry.gauge("cache.size", java.util.Collections.emptyList(), 100);

            assertEquals(5.0, registry.counter("events.total", "type", "create").count());
        }
    }

    @Nested
    @DisplayName("Annotations and configuration")
    class AnnotationsAndConfiguration {

        @Test
        @DisplayName("should be annotated with AutoConfiguration")
        void shouldBeAnnotatedWithAutoConfiguration() {
            assertTrue(FrameworkAutoConfiguration.class
                    .isAnnotationPresent(org.springframework.boot.autoconfigure.AutoConfiguration.class));
        }

        @Test
        @DisplayName("should be annotated with ConditionalOnClass")
        void shouldBeAnnotatedWithConditionalOnClass() {
            assertTrue(FrameworkAutoConfiguration.class
                    .isAnnotationPresent(org.springframework.boot.autoconfigure.condition.ConditionalOnClass.class));

            org.springframework.boot.autoconfigure.condition.ConditionalOnClass annotation =
                    FrameworkAutoConfiguration.class.getAnnotation(
                            org.springframework.boot.autoconfigure.condition.ConditionalOnClass.class);

            Class<?>[] requiredClasses = annotation.value();
            assertEquals(2, requiredClasses.length);

            // Verify HazelcastInstance and MeterRegistry are required
            boolean hasHazelcast = false;
            boolean hasMeterRegistry = false;
            for (Class<?> clazz : requiredClasses) {
                if (clazz == com.hazelcast.core.HazelcastInstance.class) {
                    hasHazelcast = true;
                }
                if (clazz == io.micrometer.core.instrument.MeterRegistry.class) {
                    hasMeterRegistry = true;
                }
            }
            assertTrue(hasHazelcast, "Should require HazelcastInstance");
            assertTrue(hasMeterRegistry, "Should require MeterRegistry");
        }

        @Test
        @DisplayName("should import HazelcastConfig and MetricsConfig")
        void shouldImportConfigurations() {
            assertTrue(FrameworkAutoConfiguration.class
                    .isAnnotationPresent(org.springframework.context.annotation.Import.class));

            org.springframework.context.annotation.Import importAnnotation =
                    FrameworkAutoConfiguration.class.getAnnotation(
                            org.springframework.context.annotation.Import.class);

            Class<?>[] importedClasses = importAnnotation.value();
            assertEquals(2, importedClasses.length);

            boolean hasHazelcastConfig = false;
            boolean hasMetricsConfig = false;
            for (Class<?> clazz : importedClasses) {
                if (clazz == HazelcastConfig.class) {
                    hasHazelcastConfig = true;
                }
                if (clazz == MetricsConfig.class) {
                    hasMetricsConfig = true;
                }
            }
            assertTrue(hasHazelcastConfig, "Should import HazelcastConfig");
            assertTrue(hasMetricsConfig, "Should import MetricsConfig");
        }

        @Test
        @DisplayName("meterRegistry method should be annotated with ConditionalOnMissingBean")
        void meterRegistryMethodShouldBeConditionalOnMissingBean() throws NoSuchMethodException {
            java.lang.reflect.Method method = FrameworkAutoConfiguration.class.getMethod("meterRegistry");

            assertTrue(method.isAnnotationPresent(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean.class));

            org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean annotation =
                    method.getAnnotation(
                            org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean.class);

            Class<?>[] value = annotation.value();
            assertEquals(1, value.length);
            assertEquals(MeterRegistry.class, value[0]);
        }

        @Test
        @DisplayName("meterRegistry method should be annotated with Bean")
        void meterRegistryMethodShouldBeAnnotatedWithBean() throws NoSuchMethodException {
            java.lang.reflect.Method method = FrameworkAutoConfiguration.class.getMethod("meterRegistry");

            assertTrue(method.isAnnotationPresent(org.springframework.context.annotation.Bean.class));
        }
    }
}
