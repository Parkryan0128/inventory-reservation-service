CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES reservations(id),
    event_type VARCHAR(40) NOT NULL,
    revision INTEGER NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(160),
    UNIQUE(order_id, revision)
);
CREATE INDEX ix_outbox_pending ON outbox_events(published_at, next_attempt_at, created_at);
CREATE TABLE processed_order_events (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_processed_order ON processed_order_events(order_id);
