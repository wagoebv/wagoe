-- Migration 20260823052706: Create products table

CREATE TABLE IF NOT EXISTS products (
  id UUID PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  sku VARCHAR(255) NOT NULL,
  price DOUBLE PRECISION,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP,
  deleted_at TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_products_created_at ON products(created_at);
