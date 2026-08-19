# WMS 仓储管理系统

基于 **Java 17 + Spring Boot 3 + Vue 3** 的简化版仓库管理系统（面试项目），覆盖商品/仓库/库位基础档案、入库、出库、库存查询，并针对高并发库存扣减做了防超卖设计。

## 功能总览

| 模块 | 说明 |
|------|------|
| 商品 / 仓库 / 库位 | 基础档案 CRUD（商品删除为**逻辑删除**：标记 deleted、历史单据保留可追溯） |
| 入库管理 | 创建入库单（表格行内编辑、草稿持久化、`request_id` 幂等防重复入库） |
| 出库管理 | 创建出库单（可用库存实时展示、超量前端拦截；**Redis Lua 预扣 + DB 原子条件更新双层防超卖**） |
| 库存查询 | 名称/SKU/库位关键字搜索、仓库筛选、告急库存（<10 标红 + 告急条）、两步分页、300ms 防抖自动搜索 |

## 技术栈

- **后端**：Java 17 · Spring Boot 3.2 · Spring Data JPA · MySQL 5.7 · Redis（出库防超卖门控）· Lombok · springdoc
- **前端**：Vue 3 · TypeScript · Vite · Element Plus
- **测试**：JUnit 5 + MockMvc（后端 40 用例）· Vitest（前端 7 用例）· PowerShell 接口冒烟（28 用例）

## 快速启动

前置依赖：
- **MySQL 5.7**：创建库 `wms-test`（连接配置见 `backend-java/src/main/resources/application.yml`，默认 root/root）
- **Redis**（选做 A 出库防超卖门控，默认 6379 无密码）：不启动时出库自动降级为纯 DB 扣减（fail-open），正确性不受影响

### 1. 初始化数据库（一个 SQL 文件 = 完整表结构 + 种子数据）

首次运行或想重置时，执行 `db/seed.sql` 即可——**自动建表（含 products.deleted 逻辑删除列、唯一约束、索引、外键，与 JPA 实体一致）+ 写入种子数据**（15 商品 / 3 仓库 / 10 库位 / 19 库存行，含历史订单示例）：

```bash
# 若库不存在先创建：
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS wms-test DEFAULT CHARACTER SET utf8mb4"
# 建表 + 灌数据（可重复执行：表结构不变，数据重置为种子数据）：
mysql --default-character-set=utf8mb4 -uroot -p wms-test -e "source db/seed.sql"
```

> 后端 JPA（`ddl-auto=update`）启动后会自动对齐表结构（新增列等），与 seed.sql 互不冲突。

### 2. 启动后端（http://localhost:8080）

```bash
cd backend-java
mvn spring-boot:run
```

- API 文档（Swagger）：http://localhost:8080/swagger-ui.html
- 启动时自动：创建/更新表结构 → 初始化单号序列表 → 重建 Redis 库存镜像

### 3. 启动前端（http://localhost:5173）

```bash
cd frontend-vue
npm install
npm run dev
```

## 测试

| 项 | 命令 | 说明 |
|----|------|------|
| 后端测试 | `cd backend-java && mvn test` | 40 用例：入库/库存/删除/出库 Service 与 API，含 **20 线程真实并发防超卖集成测试**（需 MySQL + Redis） |
| 前端测试 | `cd frontend-vue && npm test` | 7 用例：库存筛选参数组装 + 防抖逻辑 |
| 接口冒烟 | `powershell -ExecutionPolicy Bypass -File smoke-test.ps1` | 28 用例：商品/仓库/入库/库存/删除/出库全链路（需后端运行，默认 8080，可 `-BaseUrl` 指定） |

## 项目结构

```
wms-test/
├── README.md                 # 本文件
├── TASKS.md                  # 任务清单
├── NOTES.md                  # 开发记录（总结/流程/各任务设计沟通/漏洞思考/Review）
├── AGENT.md                  # 给 AI 助手的通用交接文档（项目全貌/协作约定/设计决策）
├── db/seed.sql               # 完整 SQL：建表（含 deleted 列/索引/外键）+ 种子数据
├── smoke-test.ps1            # 接口冒烟脚本
├── backend-java/             # Spring Boot 后端
│   └── src/main/java/com/wms/
│       ├── controller/       # REST 控制器（商品/仓库/入库/库存/出库）
│       ├── service/          # 业务逻辑（含 RedisStockService 库存门控、OutboundOrderService 防超卖）
│       ├── repository/       # JPA 数据层（两步分页、原子条件扣减、序列表发号）
│       ├── entity/ dto/      # 实体与传输对象
│       └── config/           # 数据初始化、库存镜像启动重建
└── frontend-vue/             # Vue 3 前端
    └── src/
        ├── views/            # 商品/库存/入库/出库页面
        ├── api/              # API 客户端
        └── utils/            # 可测试的纯逻辑（筛选参数、防抖）
```

## 设计要点（详见 NOTES.md）

- **防超卖**：Redis Lua 原子预扣（高并发闸门）+ DB 原子条件更新（`UPDATE ... WHERE quantity >= N` 正确性兜底），失败补偿回滚；Redis 不可用自动降级纯 DB
- **并发安全发号**：`order_sequences` 序列表 + `LAST_INSERT_ID` 原子取号（并发测试暴露并修复"事务内查 max+1"不可靠）
- **幂等**：`request_id` 唯一索引 + 命中返回原单；前端 UUID 幂等键失败重试复用
- **分页**：两步查询（id 集合 + IN 回表）+ OFFSET 深度上限 10000；游标分页为演进方案
- **逻辑删除**：商品删除标记 `deleted`（`@SQLDelete` + `@SQLRestriction` 软删），不物理删除、历史单据与库存保留可追溯；SKU 全局唯一（含已删记录）；删除保留渐进式确认（有关联先提示数量，二次确认后执行）
