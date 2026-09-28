CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    product_id UUID NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    unit_price_cents BIGINT NOT NULL CHECK (unit_price_cents > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RESERVED','CONFIRMED','CANCELLED','EXPIRED','PAYMENT_FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_reservation_owner ON reservations(owner_id, created_at);
CREATE INDEX ix_reservation_expiry ON reservations(status, expires_at);
