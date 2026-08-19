-- ============================================================================
-- WMS 测试库：完整表结构 + 种子数据（一条命令建库建表 + 灌正式数据）
-- ============================================================================
-- 用途：
--   全新环境：先建库，再执行本文件 → 自动建表（与 JPA 实体一致）+ 灌入正式数据；
--   已有环境：重复执行本文件 → 表结构不变（IF NOT EXISTS），数据重置为种子数据。
-- 用法（任一）：
--   mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS wms-test DEFAULT CHARACTER SET utf8mb4"
--   mysql --default-character-set=utf8mb4 -uroot -p wms-test -e "source db/seed.sql"
--   mysql --default-character-set=utf8mb4 -uroot -p wms-test < db/seed.sql
-- 说明：
--   1. 表结构内联在 0 节（与 JPA 实体/注解一致，含 products.deleted 逻辑删除列、
--      唯一约束、筛选索引与外键）；后端 JPA（ddl-auto=update）启动后自动对齐，不冲突；
--   2. 显式指定主键 id：商品 1-5 / 仓库 1-2 / 库位 WH-A-01-01 等为
--      smoke-test.ps1 与 JUnit 测试依赖的固定数据，请勿改动这几行；
--   3. 历史入库/出库单为业务示例（数量与库存期初不严格对账），
--      不需要时可删除 8/9 两节（库存即期初数据）；
--   4. 执行本文件后如再次启动后端，DataInitializer 检测到已有商品会跳过示例初始化。
-- ============================================================================

SET NAMES utf8mb4;

-- 0. 表结构（与 JPA 实体一致；IF NOT EXISTS：已由 JPA 建过的表自动跳过）
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

-- 1. 清空业务表（先子表后父表，关闭外键检查以便 TRUNCATE）
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE outbound_order_items;
TRUNCATE TABLE outbound_orders;
TRUNCATE TABLE inbound_order_items;
TRUNCATE TABLE inbound_orders;
TRUNCATE TABLE inventory;
TRUNCATE TABLE locations;
TRUNCATE TABLE products;
TRUNCATE TABLE warehouses;
TRUNCATE TABLE order_sequences;
SET FOREIGN_KEY_CHECKS = 1;

-- 2. 仓库
INSERT INTO warehouses (id, code, name) VALUES
  (1, 'WH-A', '广州主仓'),
  (2, 'WH-B', '深圳保税仓'),
  (3, 'WH-C', '上海前置仓');

-- 3. 库位（status: OCCUPIED=在用 / FREE=空闲）
INSERT INTO locations (id, warehouse_id, code, status) VALUES
  (1,  1, 'WH-A-01-01', 'OCCUPIED'),
  (2,  1, 'WH-A-01-02', 'OCCUPIED'),
  (3,  1, 'WH-A-02-01', 'OCCUPIED'),
  (4,  1, 'WH-A-02-02', 'FREE'),
  (5,  1, 'WH-A-03-01', 'FREE'),
  (6,  2, 'WH-B-01-01', 'OCCUPIED'),
  (7,  2, 'WH-B-01-02', 'OCCUPIED'),
  (8,  2, 'WH-B-02-01', 'FREE'),
  (9,  3, 'WH-C-01-01', 'OCCUPIED'),
  (10, 3, 'WH-C-01-02', 'FREE');

-- 4. 商品（id 1-5 为模板种子商品，smoke/JUnit 依赖，勿改）
INSERT INTO products (id, name, sku, unit, created_at, updated_at) VALUES
  (1,  '蓝牙耳机 Pro',        'SKU-001', '个', '2026-08-01 09:00:00', '2026-08-01 09:00:00'),
  (2,  'Type-C 数据线',       'SKU-002', '条', '2026-08-01 09:00:00', '2026-08-01 09:00:00'),
  (3,  '无线充电板',          'SKU-003', '个', '2026-08-01 09:00:00', '2026-08-01 09:00:00'),
  (4,  '手机壳 透明款',       'SKU-004', '个', '2026-08-01 09:00:00', '2026-08-01 09:00:00'),
  (5,  '屏幕保护膜',          'SKU-005', '张', '2026-08-01 09:00:00', '2026-08-01 09:00:00'),
  (6,  '机械键盘 87键',       'SKU-006', '个', '2026-08-05 10:00:00', '2026-08-05 10:00:00'),
  (7,  '无线鼠标',            'SKU-007', '个', '2026-08-05 10:00:00', '2026-08-05 10:00:00'),
  (8,  'USB-C 扩展坞',        'SKU-008', '个', '2026-08-05 10:00:00', '2026-08-05 10:00:00'),
  (9,  '智能手表',            'SKU-009', '块', '2026-08-05 10:00:00', '2026-08-05 10:00:00'),
  (10, '便携蓝牙音箱',        'SKU-010', '台', '2026-08-05 10:00:00', '2026-08-05 10:00:00'),
  (11, '笔记本支架 铝合金',   'SKU-011', '个', '2026-08-06 11:30:00', '2026-08-06 11:30:00'),
  (12, '高清网络摄像头',      'SKU-012', '个', '2026-08-06 11:30:00', '2026-08-06 11:30:00'),
  (13, '移动电源 20000mAh',   'SKU-013', '个', '2026-08-06 11:30:00', '2026-08-06 11:30:00'),
  (14, '主动降噪耳机',        'SKU-014', '副', '2026-08-06 11:30:00', '2026-08-06 11:30:00'),
  (15, '键盘清洁套装',        'SKU-015', '套', '2026-08-06 11:30:00', '2026-08-06 11:30:00');

-- 5. 库存（quantity < 10 为告急库存，页面标红 + 告急条统计）
INSERT INTO inventory (id, product_id, location_code, quantity, updated_at) VALUES
  (1,  1,  'WH-A-01-01', 150, '2026-08-18 15:20:00'),
  (2,  1,  'WH-A-01-02',  60, '2026-08-18 15:20:00'),
  (3,  2,  'WH-A-01-01', 320, '2026-08-18 15:20:00'),
  (4,  2,  'WH-B-01-01', 120, '2026-08-18 15:20:00'),
  (5,  3,  'WH-A-01-02',   5, '2026-08-18 15:20:00'),
  (6,  4,  'WH-A-01-01',   8, '2026-08-18 15:20:00'),
  (7,  4,  'WH-B-01-01',  45, '2026-08-18 15:20:00'),
  (8,  5,  'WH-A-02-01', 200, '2026-08-18 15:20:00'),
  (9,  6,  'WH-A-02-02',  12, '2026-08-18 15:20:00'),
  (10, 6,  'WH-B-01-02',  30, '2026-08-18 15:20:00'),
  (11, 7,  'WH-A-02-01',   3, '2026-08-18 15:20:00'),
  (12, 8,  'WH-B-01-01',  18, '2026-08-18 15:20:00'),
  (13, 9,  'WH-C-01-01',  25, '2026-08-18 15:20:00'),
  (14, 10, 'WH-C-01-02',   9, '2026-08-18 15:20:00'),
  (15, 11, 'WH-A-03-01',  40, '2026-08-18 15:20:00'),
  (16, 12, 'WH-B-02-01',   7, '2026-08-18 15:20:00'),
  (17, 13, 'WH-C-01-01',  55, '2026-08-18 15:20:00'),
  (18, 14, 'WH-A-01-02',  16, '2026-08-18 15:20:00'),
  (19, 15, 'WH-B-01-02',  22, '2026-08-18 15:20:00');

-- 6. 历史入库单（示例，可删；数量与库存期初不严格对账）
INSERT INTO inbound_orders (id, order_no, supplier_name, status, created_at, request_id) VALUES
  (1, 'IN-20260817-001', '深圳市华强供应链有限公司', 'COMPLETED', '2026-08-17 10:23:00', NULL),
  (2, 'IN-20260818-002', '东莞市力创电子科技有限公司', 'COMPLETED', '2026-08-18 09:15:00', NULL);

INSERT INTO inbound_order_items (id, order_id, product_id, quantity, location_code) VALUES
  (1, 1, 1, 100, 'WH-A-01-01'),
  (2, 1, 2, 200, 'WH-A-01-01'),
  (3, 2, 3,  60, 'WH-A-01-02');

-- 7. 历史出库单（示例，可删）
INSERT INTO outbound_orders (id, order_no, customer_name, status, created_at, request_id) VALUES
  (1, 'OUT-20260818-001', '广州优品数码贸易有限公司', 'COMPLETED', '2026-08-18 14:40:00', NULL);

INSERT INTO outbound_order_items (id, order_id, product_id, quantity, location_code) VALUES
  (1, 1, 1, 30, 'WH-A-01-01'),
  (2, 1, 2, 50, 'WH-A-01-01');

-- 8. 单号序列表（选做A：IN/OUT 序号从当前最大单号序号 +1 起）
INSERT INTO order_sequences (seq_type, next_value) VALUES
  ('IN',  3),
  ('OUT', 2);
