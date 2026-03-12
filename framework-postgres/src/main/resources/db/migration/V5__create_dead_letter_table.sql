-- V5: Create dead letter queue table for durable DLQ persistence
-- Stores DLQ entries so pending entries survive process restarts.

CREATE TABLE domain_dead_letters (
    dlq_entry_id             VARCHAR(255) PRIMARY KEY,
    original_event_id        VARCHAR(255) NOT NULL,
    event_type               VARCHAR(255) NOT NULL,
    topic_name               VARCHAR(255) NOT NULL,
    event_data               JSONB,
    failure_reason           TEXT,
    failure_timestamp_millis BIGINT NOT NULL,
    source_service           VARCHAR(255),
    saga_id                  VARCHAR(255),
    correlation_id           VARCHAR(255),
    replay_count             INTEGER NOT NULL DEFAULT 0,
    status                   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for status-based queries
CREATE INDEX idx_dead_letters_status ON domain_dead_letters (status);

-- Partial index for efficient recovery of PENDING entries on startup
CREATE INDEX idx_dead_letters_pending ON domain_dead_letters (status, failure_timestamp_millis)
    WHERE status = 'PENDING';
