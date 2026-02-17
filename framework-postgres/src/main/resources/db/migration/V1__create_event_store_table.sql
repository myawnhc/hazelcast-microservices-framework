-- Event store table for persisting domain events via write-behind MapStore.
-- Each row represents a single domain event from a Hazelcast IMap.

CREATE TABLE domain_events (
    map_key          VARCHAR(255) NOT NULL,
    aggregate_id     VARCHAR(255) NOT NULL,
    sequence         BIGINT NOT NULL,
    event_type       VARCHAR(255) NOT NULL,
    event_data       JSONB NOT NULL,
    timestamp_millis BIGINT NOT NULL,
    correlation_id   VARCHAR(255),
    map_name         VARCHAR(100) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (map_name, map_key)
);

CREATE INDEX idx_events_aggregate ON domain_events (map_name, aggregate_id);
CREATE INDEX idx_events_type ON domain_events (map_name, event_type);
CREATE INDEX idx_events_timestamp ON domain_events (map_name, timestamp_millis);
