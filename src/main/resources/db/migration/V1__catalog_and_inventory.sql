CREATE TABLE products (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    price_cents BIGINT NOT NULL CHECK (price_cents > 0),
    currency VARCHAR(3) NOT NULL,
    available INTEGER NOT NULL CHECK (available >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    sold INTEGER NOT NULL DEFAULT 0 CHECK (sold >= 0),
    initial_stock INTEGER NOT NULL CHECK (initial_stock >= 0),
    CHECK (available + reserved + sold = initial_stock)
);
