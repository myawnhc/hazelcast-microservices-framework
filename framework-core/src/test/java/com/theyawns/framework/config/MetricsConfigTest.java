package com.theyawns.framework.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricsConfig.
 */
@DisplayName("MetricsConfig - Micrometer configuration")
class MetricsConfigTest {

    private MetricsConfig metricsConfig;

    @BeforeEach
    void setUp() {
        metricsConfig = new MetricsConfig();
        setFieldValue(metricsConfig, "applicationName", "test-app");
        setFieldValue(metricsConfig, "applicationVersion", "1.0.0-test");
    }

    @Nested
    @DisplayName("Common tags customizer")
    class CommonTagsCustomizer {

        @Test
        @DisplayName("should create meter registry customizer bean")
        void shouldCreateMeterRegistryCustomizerBean() {
            MeterRegistryCustomizer<MeterRegistry> customizer = metricsConfig.metricsCommonTags();
            assertNotNull(customizer);
        }

        @Test
        @DisplayName("should add application tag")
        void shouldAddApplicationTag() {
            MeterRegistryCustomizer<MeterRegistry> customizer = metricsConfig.metricsCommonTags();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            customizer.customize(registry);

            // Verify common tags are set by checking if they're applied to new meters
            registry.counter("test.counter").increment();
            assertTrue(registry.find("test.counter")
                    .tags("application", "test-app")
                    .counter() != null);
        }

        @Test
        @DisplayName("should add version tag")
        void shouldAddVersionTag() {
            MeterRegistryCustomizer<MeterRegistry> customizer = metricsConfig.metricsCommonTags();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            customizer.customize(registry);

            registry.counter("test.counter").increment();
            assertTrue(registry.find("test.counter")
                    .tags("version", "1.0.0-test")
                    .counter() != null);
        }
    }

    @Nested
    @DisplayName("JVM metrics beans")
    class JvmMetricsBeans {

        @Test
        @DisplayName("should create JVM memory metrics bean")
        void shouldCreateJvmMemoryMetricsBean() {
            JvmMemoryMetrics metrics = metricsConfig.jvmMemoryMetrics();
            assertNotNull(metrics);
        }

        @Test
        @DisplayName("should create JVM GC metrics bean")
        void shouldCreateJvmGcMetricsBean() {
            JvmGcMetrics metrics = metricsConfig.jvmGcMetrics();
            assertNotNull(metrics);
        }

        @Test
        @DisplayName("should create JVM thread metrics bean")
        void shouldCreateJvmThreadMetricsBean() {
            JvmThreadMetrics metrics = metricsConfig.jvmThreadMetrics();
            assertNotNull(metrics);
        }

        @Test
        @DisplayName("should create class loader metrics bean")
        void shouldCreateClassLoaderMetricsBean() {
            ClassLoaderMetrics metrics = metricsConfig.classLoaderMetrics();
            assertNotNull(metrics);
        }

        @Test
        @DisplayName("should create processor metrics bean")
        void shouldCreateProcessorMetricsBean() {
            ProcessorMetrics metrics = metricsConfig.processorMetrics();
            assertNotNull(metrics);
        }
    }

    @Nested
    @DisplayName("Metrics binding")
    class MetricsBinding {

        @Test
        @DisplayName("JVM memory metrics should bind to registry")
        void jvmMemoryMetricsShouldBindToRegistry() {
            JvmMemoryMetrics metrics = metricsConfig.jvmMemoryMetrics();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics.bindTo(registry);

            // Check for some expected JVM memory metrics
            assertFalse(registry.getMeters().isEmpty());
        }

        @Test
        @DisplayName("JVM thread metrics should bind to registry")
        void jvmThreadMetricsShouldBindToRegistry() {
            JvmThreadMetrics metrics = metricsConfig.jvmThreadMetrics();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics.bindTo(registry);

            assertFalse(registry.getMeters().isEmpty());
        }

        @Test
        @DisplayName("Class loader metrics should bind to registry")
        void classLoaderMetricsShouldBindToRegistry() {
            ClassLoaderMetrics metrics = metricsConfig.classLoaderMetrics();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics.bindTo(registry);

            assertFalse(registry.getMeters().isEmpty());
        }

        @Test
        @DisplayName("Processor metrics should bind to registry")
        void processorMetricsShouldBindToRegistry() {
            ProcessorMetrics metrics = metricsConfig.processorMetrics();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics.bindTo(registry);

            assertFalse(registry.getMeters().isEmpty());
        }
    }

    /**
     * Helper method to set private field values using reflection.
     */
    private void setFieldValue(Object object, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(object, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
