# AGENT.md — 给 AI 助手的项目交接文档

> 本文档是给**接手本项目的 AI 会话**的通用交接文档：项目全貌、文档导航、协作约定、不可破坏的设计决策。
> 阅读顺序：README（项目介绍/启动）→ TASKS.md（任务清单）→ NOTES.md（开发记录，面试向）→ **本文档（协作与设计约定）**。
> 本文件不涉及特定机器/环境配置；环境与启动问题以 README 的说明和实际报错上下文为准。

## 1. 项目是什么

WMS 仓储管理系统（面试项目）：Java 17 + Spring Boot 3.2 + Vue 3 + TS + Element Plus + MySQL + Redis。
功能：商品/仓库/库位档案、入库（幂等防重复）、库存查询（两步分页 + 防抖）、出库（**Redis Lua 预扣 + DB 原子扣减双层防超卖**）。
必做 3 任务 + 选做 A/B/C **全部完成**：JUnit 40 用例 + 前端 vitest 7 用例 + smoke 28 用例全绿。

## 2. 文档导航

| 文档 | 用途 |
|------|------|
| `README.md` | 项目介绍、技术栈、快速启动、测试命令（对使用者/AI 都适用） |
| `TASKS.md` | 任务清单（必做 3 项 + 选做 A/B/C + 提交检查清单） |
| `NOTES.md` | 开发记录（面试向：总结、统一流程、各任务设计沟通记录、漏洞思考 8 项、代码 Review） |
| `docs/API_SPEC.md` | 接口规范 |
| `db/seed.sql` | 数据库种子数据（清空业务表 + 正式数据） |
| `smoke-test.ps1` | 接口级冒烟脚本（28 用例） |

## 3. 协作约定（用户固定要求，必须遵守）

- **流程固定**：理解需求 → 设计方案**先与用户确认**（用 ask_user_question，争取用户建议，确认后再编码）→ 编码 → 对照需求验收 → 冒烟测试 + 每任务 **2 个 JUnit 测试类**（Service + API 层）→ 漏洞思考（固定 8 项清单）→ 代码 Review（事务 / SQL 性能 / 空指针 / 并发）→ NOTES.md 记录
- **小步提交**：每个可独立交付的改动单独 commit + push，message 写清楚"做了什么"（提交历史给面试官看渐进开发）
- **人主导决策**：NOTES.md 是面试向文档，记录"人主导决策、AI 执行"（各任务「设计沟通记录」表）；不要替用户拍板
- **用户会并行编辑文件**：改文件前必须重新 read（NOTES.md 曾被旧缓存覆盖过）；用户短句指令要准确解读
- **环境/配置类问题不进 NOTES.md**（面试向，排除环境噪音）

## 4. 不可破坏的核心设计决策

- **统一信封** `ApiResponse{code,message,data}`：成功 body code=200；POST 创建 HTTP 201（`@ResponseStatus`）+ body code 200
- **异常**：`BusinessException(code,msg)` → HTTP 400，body 携带业务 code（404 商品/库位不存在、400 库存不足/参数错误）
- **防超卖双层**（核心亮点，勿拆）：
  - Redis Lua 原子预扣：key `wms:stock:{productId}:{locationCode}`，GET 剩余 ≥ 需求 → DECRBY；key 不存在 → 懒加载 DB 值 SETNX 后重试；Redis 异常 → **fail-open 放行**（正确性由 DB 兜底）
  - DB 原子条件更新：`UPDATE inventory SET quantity=quantity-:qty ... WHERE product_id=? AND location_code=? AND quantity>=:qty`，affected=0 → 400 回滚
  - 整个「预扣循环 + DB 事务」包在 try-catch，失败统一补偿已预扣行（曾漏补偿 DB 段被并发测试暴露）
- **幂等**：`request_id` 唯一索引为准，命中返回原单；前端 UUID 幂等键（失败重试复用、成功换新）
- **并发发号**：`order_sequences` 序列表 + `UPDATE ... SET next_value=LAST_INSERT_ID(next_value+1) WHERE seq_type=?` + `SELECT LAST_INSERT_ID()`（native query）；IN/OUT 全局递增跨天不重置；**不要改回"查 max+1"**（并发下 Duplicate entry）
- **两步分页**：`findIdsByFilters`（只查 id + count，零回表）→ `findDetailsByIds`（`id IN` 回表，按 id 重排）；OFFSET 深度上限 10000（超限 400）；pageSize ≤ 100；keyword 匹配 name/sku/locationCode；`productId` 可选过滤（出库页用）
- **扣减注意**：`deductStock` 是 @Modifying bulk update（绕生命周期），手动刷新 updatedAt；**不要在同一事务里先 load Inventory 实体再改**
- **明细合并**：出库明细按 (productId, locationCode) 合并后再预扣/扣减，避免"扣一半回滚"
- **删除商品（逻辑删除 + 渐进式确认）**：`Product` 实体 `@SQLDelete` + `@SQLRestriction("deleted = 0")`——`deleteById` 变成 `UPDATE deleted=1`，所有查询/join 自动过滤已删商品（列表/搜索/详情/编辑/入库/出库引用一律 404）；不物理删除、不清关联数据（库存/历史单据保留可追溯）；**渐进式确认**：有关联数据（库存/历史入库/出库记录）且未传 `force` 时返回 400 提示关联数量与影响范围，前端二次确认后带 `force=true` 才执行删除（force 仅表示"确认"，删除仍是逻辑删除）；`existsBySku` 用 native query 绕过软删过滤（SKU 全局唯一含已删；返回 long 计数防 ClassCastException）；**`DataInitializer` 判断是否初始化示例数据必须用原生 SQL 计数（含已删），否则商品全删后 count()=0 会重复初始化撞 SKU 唯一约束**
- **前端约定**：表格行内编辑；草稿持久化 localStorage（`wms.inbound.draft` / `wms.outbound.draft`）；防抖 300ms + 自动搜索提示；库存 < 10 红色加粗 + 告急条点击切换；出库页「可用库存」列超量前端拦截
- **测试数据依赖**：smoke/JUnit 依赖固定 id（商品 1-5、仓库 1-2、库位 WH-A-01-01、product 1 库存 150）——seed.sql 保持兼容；测试自建数据用 UUID SKU 且用后清理

## 5. 测试怎么跑（通用命令）

- 后端：`mvn test`（在 `backend-java` 下；需 MySQL；`OutboundConcurrencyTest` 真实并发需 Redis）
- 前端：`npm test`（vitest，在 `frontend-vue` 下，7 例）
- 冒烟：`powershell -ExecutionPolicy Bypass -File smoke-test.ps1`（需后端运行，28 用例）
- 任何改动必须保证既有测试全绿 + 新功能补用例

## 6. 数据库初始化（通用命令）

- 一个 SQL 文件搞定：`db/seed.sql` 内含**完整表结构**（CREATE TABLE IF NOT EXISTS，与 JPA 实体一致：唯一约束/索引/外键/products.deleted 逻辑删除列）+ **种子数据**（15 商品 / 3 仓库 / 10 库位 / 19 库存行 / 历史订单示例 / 序列表 IN=3 OUT=2）；空库执行即建表灌数据，已有库重复执行只重置数据
- 后端 JPA `ddl-auto=update` 启动时自动对齐表结构（新增列等），与 seed.sql 不冲突
- 后端启动时自动：初始化序列表（next_value = 当前 DB 最大序号 + 1）→ 重建 Redis 库存镜像

## 7. 当前任务状态

- 必做 3 项 + 选做 A/B/C 全部完成，测试全绿（JUnit 40 / vitest 7 / smoke 28）
- 商品删除已演进为逻辑删除（修改后端代码后需重启后端生效）
- 可选待办：`application.yml` 数据库口令改环境变量注入（未做，已记录）
