-- Migration 20260919100909: Create products table

CREATE TABLE IF NOT EXISTS products (
  id UUID PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  sku VARCHAR(255) NOT NULL,
  price DECIMAL(19,4),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP,
  deleted_at TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_products_created_at ON products(created_at);
