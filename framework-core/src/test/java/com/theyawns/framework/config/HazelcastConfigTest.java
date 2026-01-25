package com.theyawns.framework.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HazelcastConfig.
 *
 * <p>Note: These tests verify the configuration bean is created correctly.
 * Integration tests with actual Hazelcast instances are separate.
 */
@DisplayName("HazelcastConfig - Hazelcast configuration")
class HazelcastConfigTest {

    private HazelcastConfig config;
    private HazelcastInstance instance;

    @BeforeEach
    void setUp() {
        config = new HazelcastConfig();
    }

    @AfterEach
    void tearDown() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    @Nested
    @DisplayName("Bean creation")
    class BeanCreation {

        @Test
        @DisplayName("should create HazelcastInstance bean")
        void shouldCreateHazelcastInstanceBean() {
            // Use reflection to set default values since @Value won't work in unit test
            setFieldValue(config, "clusterName", "test-cluster");
            setFieldValue(config, "eventJournalCapacity", 10000);
            setFieldValue(config, "eventJournalTtlSeconds", 3600);
            setFieldValue(config, "backupCount", 1);
            setFieldValue(config, "networkPort", 5701);
            setFieldValue(config, "portAutoIncrement", true);

            instance = config.hazelcastInstance();

            assertNotNull(instance);
            assertEquals("test-cluster", instance.getConfig().getClusterName());
        }

        @Test
        @DisplayName("should configure Jet to be enabled")
        void shouldConfigureJetEnabled() {
            setFieldValue(config, "clusterName", "test-cluster");
            setFieldValue(config, "eventJournalCapacity", 10000);
            setFieldValue(config, "eventJournalTtlSeconds", 3600);
            setFieldValue(config, "backupCount", 1);
            setFieldValue(config, "networkPort", 5702);
            setFieldValue(config, "portAutoIncrement", true);

            instance = config.hazelcastInstance();

            assertTrue(instance.getConfig().getJetConfig().isEnabled());
        }
    }

    @Nested
    @DisplayName("Map configurations")
    class MapConfigurations {

        @Test
        @DisplayName("should configure pending maps with event journal")
        void shouldConfigurePendingMapsWithEventJournal() {
            setFieldValue(config, "clusterName", "test-cluster");
            setFieldValue(config, "eventJournalCapacity", 5000);
            setFieldValue(config, "eventJournalTtlSeconds", 1800);
            setFieldValue(config, "backupCount", 1);
            setFieldValue(config, "networkPort", 5703);
            setFieldValue(config, "portAutoIncrement", true);

            instance = config.hazelcastInstance();
            Config hzConfig = instance.getConfig();
            MapConfig pendingConfig = hzConfig.getMapConfig("*_PENDING");

            assertTrue(pendingConfig.getEventJournalConfig().isEnabled());
            assertEquals(5000, pendingConfig.getEventJournalConfig().getCapacity());
            assertEquals(1800, pendingConfig.getEventJournalConfig().getTimeToLiveSeconds());
        }

        @Test
        @DisplayName("should configure event store maps without eviction")
        void shouldConfigureEventStoreMapsWithoutEviction() {
            setFieldValue(config, "clusterName", "test-cluster");
            setFieldValue(config, "eventJournalCapacity", 10000);
            setFieldValue(config, "eventJournalTtlSeconds", 3600);
            setFieldValue(config, "backupCount", 2);
            setFieldValue(config, "networkPort", 5704);
            setFieldValue(config, "portAutoIncrement", true);

            instance = config.hazelcastInstance();
            Config hzConfig = instance.getConfig();
            MapConfig esConfig = hzConfig.getMapConfig("*_ES");

            assertEquals(com.hazelcast.config.EvictionPolicy.NONE,
                    esConfig.getEvictionConfig().getEvictionPolicy());
            assertEquals(2, esConfig.getBackupCount());
        }

        @Test
        @DisplayName("should configure view maps with read backup data")
        void shouldConfigureViewMapsWithReadBackupData() {
            setFieldValue(config, "clusterName", "test-cluster");
            setFieldValue(config, "eventJournalCapacity", 10000);
            setFieldValue(config, "eventJournalTtlSeconds", 3600);
            setFieldValue(config, "backupCount", 1);
            setFieldValue(config, "networkPort", 5705);
            setFieldValue(config, "portAutoIncrement", true);

            instance = config.hazelcastInstance();
            Config hzConfig = instance.getConfig();
            MapConfig viewConfig = hzConfig.getMapConfig("*_VIEW");

            assertTrue(viewConfig.isReadBackupData());
        }

        @Test
        @DisplayName("should configure completions maps with TTL")
        void shouldConfigureCompletionsMapsWithTtl() {
            setFieldValue(config, "clusterName", "test-cluster");
            setFieldValue(config, "eventJournalCapacity", 10000);
            setFieldValue(config, "eventJournalTtlSeconds", 3600);
            setFieldValue(config, "backupCount", 1);
            setFieldValue(config, "networkPort", 5706);
            setFieldValue(config, "portAutoIncrement", true);

            instance = config.hazelcastInstance();
            Config hzConfig = instance.getConfig();
            MapConfig completionsConfig = hzConfig.getMapConfig("*_COMPLETIONS");

            assertEquals(3600, completionsConfig.getTimeToLiveSeconds());
        }
    }

    @Nested
    @DisplayName("Network configuration")
    class NetworkConfiguration {

        @Test
        @DisplayName("should configure custom port")
        void shouldConfigureCustomPort() {
            setFieldValue(config, "clusterName", "test-cluster");
            setFieldValue(config, "eventJournalCapacity", 10000);
            setFieldValue(config, "eventJournalTtlSeconds", 3600);
            setFieldValue(config, "backupCount", 1);
            setFieldValue(config, "networkPort", 5800);
            setFieldValue(config, "portAutoIncrement", false);

            instance = config.hazelcastInstance();
            Config hzConfig = instance.getConfig();

            assertEquals(5800, hzConfig.getNetworkConfig().getPort());
            assertFalse(hzConfig.getNetworkConfig().isPortAutoIncrement());
        }

        @Test
        @DisplayName("should enable multicast by default")
        void shouldEnableMulticastByDefault() {
            setFieldValue(config, "clusterName", "test-cluster");
            setFieldValue(config, "eventJournalCapacity", 10000);
            setFieldValue(config, "eventJournalTtlSeconds", 3600);
            setFieldValue(config, "backupCount", 1);
            setFieldValue(config, "networkPort", 5801);
            setFieldValue(config, "portAutoIncrement", true);

            instance = config.hazelcastInstance();
            Config hzConfig = instance.getConfig();

            assertTrue(hzConfig.getNetworkConfig().getJoin().getMulticastConfig().isEnabled());
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
