-- ============================================================================
-- WMS 测试库：表结构（schema）——建表用；数据见 db/seed.sql
-- ============================================================================
-- 用途：创建 / 对齐 9 张业务表（与 JPA 实体 / 注解一致）。
-- 用法：
--   mysql --default-character-set=utf8mb4 -uroot -p wms-test -e "source db/schema.sql"
-- 说明：
--   1. CREATE TABLE IF NOT EXISTS：可重复执行；后端 JPA（ddl-auto=update）启动后自动对齐，不冲突；
--   2. 含 products.deleted 逻辑删除列、唯一约束、筛选索引、外键；
--   3. 灌数据请执行 db/seed.sql（需先执行本文件建表，或由后端 JPA 建表）。
-- ============================================================================

SET NAMES utf8mb4;

-- 表结构（与 JPA 实体一致；IF NOT EXISTS：已由 JPA 建过的表自动跳过）
CREATE TABLE IF NOT EXISTS warehouses (
  id   BIGINT       NOT NULL AUTO_INCREMENT,
  code VARCHAR(50)  NOT NULL,
  name VARCHAR(200) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_warehouses_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS locations (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  warehouse_id BIGINT      NOT NULL,
  code         VARCHAR(50) NOT NULL,
  status       VARCHAR(20) DEFAULT 'FREE',
  PRIMARY KEY (id),
  UNIQUE KEY uk_locations_code (code),
  KEY idx_locations_warehouse_id (warehouse_id),
  CONSTRAINT fk_locations_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS products (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  name       VARCHAR(200) NOT NULL,
  sku        VARCHAR(50)  NOT NULL,
  unit       VARCHAR(20),
  deleted    BIT(1)       NOT NULL DEFAULT b'0',
  created_at datetime(6),
  updated_at datetime(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_products_sku (sku),
  KEY idx_products_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  product_id    BIGINT      NOT NULL,
  location_code VARCHAR(50) NOT NULL,
  quantity      INT         NOT NULL DEFAULT 0,
  updated_at    datetime(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_location (product_id, location_code),
  KEY idx_inventory_location_code (location_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inbound_orders (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  order_no      VARCHAR(50) NOT NULL,
  supplier_name VARCHAR(200),
  status        VARCHAR(20),
  created_at    datetime(6),
  request_id    VARCHAR(64),
  PRIMARY KEY (id),
  UNIQUE KEY uk_inbound_order_no (order_no),
  UNIQUE KEY uk_inbound_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inbound_order_items (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  order_id      BIGINT      NOT NULL,
  product_id    BIGINT      NOT NULL,
  quantity      INT         NOT NULL,
  location_code VARCHAR(50) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_inbound_items_order (order_id),
  KEY idx_inbound_items_product (product_id),
  CONSTRAINT fk_inbound_items_order FOREIGN KEY (order_id) REFERENCES inbound_orders (id),
  CONSTRAINT fk_inbound_items_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS outbound_orders (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  order_no      VARCHAR(50) NOT NULL,
  customer_name VARCHAR(200),
  status        VARCHAR(20),
  created_at    datetime(6),
  request_id    VARCHAR(64),
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbound_order_no (order_no),
  UNIQUE KEY uk_outbound_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS outbound_order_items (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  order_id      BIGINT      NOT NULL,
  product_id    BIGINT      NOT NULL,
  quantity      INT         NOT NULL,
  location_code VARCHAR(50) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_outbound_items_order (order_id),
  KEY idx_outbound_items_product (product_id),
  CONSTRAINT fk_outbound_items_order FOREIGN KEY (order_id) REFERENCES outbound_orders (id),
  CONSTRAINT fk_outbound_items_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_sequences (
  seq_type   VARCHAR(20) NOT NULL,
  next_value BIGINT      NOT NULL,
  PRIMARY KEY (seq_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
