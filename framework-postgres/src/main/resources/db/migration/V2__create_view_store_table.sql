-- View store table for persisting materialized view entries via write-behind MapStore.
-- Uses upsert semantics — newer entries replace older ones for the same key.

CREATE TABLE domain_views (
    view_name            VARCHAR(100) NOT NULL,
    view_key             VARCHAR(255) NOT NULL,
    view_data            JSONB NOT NULL,
    last_updated_millis  BIGINT NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (view_name, view_key)
);
