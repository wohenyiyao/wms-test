# agent.md — 给 AI 助手的项目交接文档

> 本文档是给**下一次 AI 会话**的上下文交接，记录本项目的事实、协作约定与环境坑。
> 接手本项目的 AI 阅读顺序：README（项目介绍/启动）→ TASKS.md（任务清单）→ NOTES.md（开发记录，面试向）→ **本文档（环境与协作约定，续活必读）**。

## 1. 项目是什么

WMS 仓储管理系统（面试项目）：Java 17 + Spring Boot 3.2.5 + Vue 3 + TS + Element Plus + MySQL 5.7 + Redis。
功能：商品/仓库/库位档案、入库（幂等防重复）、库存查询（两步分页 + 防抖）、出库（**Redis Lua 预扣 + DB 原子扣减双层防超卖**）。
必做 3 任务 + 选做 A/B/C **全部完成**：JUnit 39 用例 + 前端 vitest 7 用例 + smoke 28 用例全绿。

## 2. 关键环境事实（本机 Windows，踩过的坑）

| 项 | 事实 |
|----|------|
| Maven | `mvn` 不在 PATH → 用 `E:\maven\bin\mvn.cmd`，并加 `-Dmaven.repo.local=$env:TEMP\m2-repo` |
| MySQL | `C:\mysql57\bin\mysql.exe --host=127.0.0.1 --user=root --password=root --database=wms-test`；密码警告 + exit 1 是噪音；PS 5.1 下用 `MYSQL_PWD` 环境变量规避 NativeCommandError |
| Redis | 本机 `E:\redis`（MSYS2 构建）：**必须 workdir=E:\redis 用相对路径** `.\redis-server.exe .\redis.conf`（绝对路径会被拼到 CWD 而失败）；当前默认关闭，出库 fail-open 降级纯 DB，正确性不受影响 |
| 前端类型校验 | `vite build` 在沙箱会 EPERM → 用 `npx vue-tsc --noEmit` 校验 |
| git push | 全局代理 `http://127.0.0.1:7890`（本机代理软件需开着）；代理没开时用 `git -c http.proxy= -c http.sslBackend=openssl push origin main` 临时直连（不要改全局配置）；凭据 PAT 存于 cmdkey（user wdb），勿动 |
| 仓库 | **PUBLIC**（wohenyiyao/wms-test）——不要提交任何真实密钥；`application.yml` 含 root/root 测试口令（已知边界，已接受） |
| 后端运行 | 通常由用户在 IDEA 里跑（8080）；沙箱侧不要另起后端（曾用 8081 临时后端已停） |
| PS 5.1 限制 | 整数 JSON 解析为 [int]、脚本保持 ASCII-only、native stderr 重定向不可靠 |

## 3. 协作约定（用户固定要求，必须遵守）

- **流程固定**：理解需求 → 设计方案**先与用户确认**（用 ask_user_question，争取用户建议，确认后再编码）→ 编码 → 对照需求验收 → 冒烟测试 + 每任务 **2 个 JUnit 测试类**（Service + API 层）→ 漏洞思考（固定 8 项清单）→ 代码 Review（事务 / SQL 性能 / 空指针 / 并发）→ NOTES.md 记录
- **小步提交**：每个可独立交付的改动单独 commit + push，message 写清楚"做了什么"（提交历史给面试官看渐进开发）
- **人主导决策**：NOTES.md 是面试向文档，记录"人主导决策、AI 执行"（各任务「设计沟通记录」表）；不要替用户拍板
- **用户会并行编辑文件**：改文件前必须重新 read（NOTES.md 曾被旧缓存覆盖过）；用户短句指令要准确解读
- **环境/配置类问题不进 NOTES.md**（面试向，排除环境噪音；这类事实写在本文件）

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
- **删除商品**：默认校验关联（库存 N 条 / 历史入库 N 条 / 出库 N 条），>0 返回 400 提示数量 → 前端二次确认 → `force=true` 事务内级联清理；测试库有外键，删除顺序必须先子表后父表
- **前端约定**：表格行内编辑；草稿持久化 localStorage（`wms.inbound.draft` / `wms.outbound.draft`）；防抖 300ms + 自动搜索提示；库存 < 10 红色加粗 + 告急条点击切换；出库页「可用库存」列超量前端拦截
- **测试数据依赖**：smoke/JUnit 依赖固定 id（商品 1-5、仓库 1-2、库位 WH-A-01-01、product 1 库存 150）——seed.sql 保持兼容；测试自建数据用 UUID SKU 且用后清理

## 5. 测试怎么跑

- 后端：`E:\maven\bin\mvn.cmd -Dmaven.repo.local=$env:TEMP\m2-repo test`（在 `backend-java` 下；需 MySQL；`OutboundConcurrencyTest` 真实并发需 Redis）
- 前端：`npm test`（vitest，在 `frontend-vue` 下，7 例）
- 冒烟：`powershell -ExecutionPolicy Bypass -File smoke-test.ps1`（需后端运行，28 用例）
- 任何改动必须保证既有测试全绿 + 新功能补用例

## 6. 数据库初始化

- 表结构由 JPA `ddl-auto=update` 自动创建/更新，无需手工建表
- 种子数据：`mysql --default-character-set=utf8mb4 -uroot -p wms-test -e "source db/seed.sql"`（清空业务表 + 15 商品 / 3 仓库 / 10 库位 / 19 库存行 / 历史订单示例 / 序列表 IN=3 OUT=2）
- 后端启动时自动：初始化序列表（next_value = 当前 DB 最大序号 + 1）→ 重建 Redis 库存镜像

## 7. 最近一次更新时的状态

- git 工作区干净，HEAD = origin/main（最后一次 push 已确认）
- 全部任务完成；Redis 已关闭（要验证出库流程按 README 重启）
- 可选待办：application.yml 口令改环境变量（用户提过，未做）；用户重启 IDEA 后端后可页面验证出库页/删除确认/分页保持
