-- V3: Add archival support for event store
-- Enables moving old events from domain_events to domain_events_archive
-- to keep the active table bounded during long-running deployments.

ALTER TABLE domain_events ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index: only non-archived events, ordered by creation time
CREATE INDEX idx_events_archived ON domain_events (archived, created_at) WHERE archived = FALSE;

-- Full index on created_at for archival queries
CREATE INDEX idx_events_created_at ON domain_events (created_at);

-- Archive table: identical schema to domain_events
CREATE TABLE domain_events_archive (LIKE domain_events INCLUDING ALL);
