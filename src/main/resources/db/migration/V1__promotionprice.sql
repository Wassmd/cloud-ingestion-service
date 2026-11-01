CREATE SCHEMA IF NOT EXISTS promotion;

CREATE TABLE IF NOT EXISTS promotion.promotion_price (
    id SERIAL PRIMARY KEY,
    product_id INT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    start_date TIMESTAMP NOT NULL,
    end_date TIMESTAMP NOT NULL,
    source_file TEXT   NOT NULL,
    CONSTRAINT uq_promotion UNIQUE (product_id, start_date, source_file),
    CONSTRAINT chk_dates CHECK (end_date >= start_date)
);