ALTER TABLE reservations ADD COLUMN idempotency_key VARCHAR(80);
UPDATE reservations SET idempotency_key = CAST(id AS VARCHAR(80));
ALTER TABLE reservations ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE reservations ADD CONSTRAINT uq_owner_idempotency UNIQUE (owner_id, idempotency_key);
ALTER TABLE reservations ADD COLUMN revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0);
