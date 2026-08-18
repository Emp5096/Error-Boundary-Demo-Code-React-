CREATE TABLE IF NOT EXISTS export_demo_data (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL,
    customer_name VARCHAR(80) NOT NULL,
    region VARCHAR(32) NOT NULL,
    amount DECIMAL(14, 2) NOT NULL,
    status VARCHAR(24) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_export_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
