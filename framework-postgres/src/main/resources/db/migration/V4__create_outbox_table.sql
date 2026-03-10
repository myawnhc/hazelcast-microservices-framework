-- V4: Create outbox table for durable outbox persistence
-- Stores outbox entries so undelivered events survive process restarts.

CREATE TABLE domain_outbox (
    event_id              VARCHAR(255) PRIMARY KEY,
    event_type            VARCHAR(255) NOT NULL,
    event_data            JSONB NOT NULL,
    retry_count           INTEGER NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at_millis     BIGINT NOT NULL,
    last_attempt_at_millis BIGINT,
    failure_reason        TEXT,
    claimant_id           VARCHAR(255),
    claimed_at_millis     BIGINT,
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for status-based queries (pollPending, claimPending)
CREATE INDEX idx_outbox_status ON domain_outbox (status);

-- Partial index for efficient recovery of non-delivered entries on startup
CREATE INDEX idx_outbox_pending ON domain_outbox (status, created_at_millis)
    WHERE status IN ('PENDING', 'CLAIMED', 'FAILED');
