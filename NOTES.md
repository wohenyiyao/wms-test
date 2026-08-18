# NOTES.md — 开发记录

## 总结（快速概览）

**完成情况**：必做 3 项 + 选做 3 项**全部完成**，全部经过自动化测试验证（JUnit 39 用例 + 前端 vitest 7 用例 + smoke 28 用例，全绿）。

| 模块 | 做了什么 | 亮点 |
|------|---------|------|
| 必做 1 入库 | 入库单创建（201 信封、事务库存累加、幂等） | `request_id` 唯一索引幂等：弱网重试返回原单不重复入库 |
| 必做 2 库存查询 | 名称/SKU/库位搜索 + 仓库筛选 + 告急条 + 分页 | **两步分页**（id 集合 + IN 回表）、OFFSET 深度上限 10000、300ms 防抖 |
| 必做 3 修复 Bug | 删除商品关联校验（提示→二次确认→force 级联清理）；编辑保持页码 | 按 §1.1 流程定位，回归用例覆盖 |
| 选做 A 出库+防超卖 | 出库单 + 库存扣减并发安全 | **Redis Lua 原子预扣 + DB 原子条件更新双层防线**（详见下） |
| 选做 B 单元测试 | 后端入库 Service 5 例 + 前端筛选逻辑 7 例（vitest） | 筛选逻辑抽纯函数可测 |
| 选做 C 性能优化 | 库存列表**后端分页** + **防抖搜索** | 500+ 行不卡顿；虚拟滚动不再必要 |

**选做 A 防超卖方案（核心亮点）**：Redis Lua 原子预扣做**高并发闸门**（内存中检查+扣减，不足直接拒绝），DB 原子条件更新（`UPDATE ... WHERE quantity >= N`）做**正确性兜底**——同一行并发扣减由 InnoDB 行锁串行化，库存永不为负；Redis 挂掉自动降级纯 DB（fail-open），正确性不依赖 Redis。配真实并发集成测试（20 线程抢库存，断言不超卖不丢失），测试还暴露并修复了 4 个真实问题（详见 §7.7）。

**关键技术决策**（人主导，设计沟通记录见各任务小节）：
- **并发安全发号**：单号序号用 `order_sequences` 序列表 `LAST_INSERT_ID` 原子取号（并发测试暴露"事务内查 max+1"不可靠后修复）；
- **幂等**：DB 唯一索引为准，前端 UUID 幂等键失败重试复用；
- **分页**：两步查询 + OFFSET 上限，游标分页作为数据量增大后的演进方案。

**协作方式**：人主导决策（方案先确认再编码，关键点记录「设计沟通记录」），AI 辅助编码与排查，所有产物经人工 review + 自动化测试验证后提交。Git 提交历史保持小步、message 描述做了什么。

> 使用AI按统一的开发流程记录每个任务的完成情况：
> **理解需求 → 设计（与用户确认方案、争取用户建议）→ 编码实现 → 对照需求文档验收（含功能完善性检查）→ 冒烟测试 → 漏洞思考 → 代码 Review → 人工代码Review**
> （工具链 / 环境配置类问题不在本文赘述）

---

## AI 使用说明（三问速答）

### 1. 你使用了哪些 AI 工具？如何使用的？

- **工具**：DeepSeek Harness 编码智能体（deepseek-v4-flash 模型，Web 界面），用于辅助编码、测试编写、问题排查与文档整理。
- **使用方式（人主导决策、AI 辅助执行）**：每个任务先由我（候选人）理解需求并确定设计方案，**方案与 AI 讨论确认后再编码**（关键决策记录为各任务的「设计沟通记录」表，展示人机协作过程）→ AI 按模板既有风格生成初版实现 → 我人工 review 事务边界、参数校验与并发问题 → 自动化测试验证 → 发现问题向 AI 结构化描述现象/复现/根因再定位修复。**所有 AI 产物都经过人工 review 与测试验证后才提交**；Git 保持小步提交、message 说明做了什么（详见 §2）。

### 2. 你遇到了什么问题？如何解决的？

| 问题 | 解决方案（详见） |
|------|-----------------|
| 预埋 Bug 1：删除有库存商品报 500（无关联校验） | 关联数量统计 → 前端二次确认 → `force=true` 事务内级联清理（§5.1） |
| 预埋 Bug 2：编辑商品后列表跳回第 1 页 | 保持当前页码重载 + 空页自动回退（§5.2） |
| 高并发防超卖 | **Redis Lua 原子预扣（闸门）+ DB 原子条件更新（兜底）双层防线**（§7.2） |
| 并发单号重复 `Duplicate entry` | `order_sequences` 序列表 + `LAST_INSERT_ID` 原子发号，替代"查最大序号 +1"（§7.2） |
| Redis 镜像与 DB 不一致、补偿遗漏 | 真实并发测试暴露 4 个问题逐一修复（初始化重复累加 / DB 拦截未补偿 Redis / 单号重复 / 清理违反外键）（§7.7） |
| 弱网重试导致重复入库 | `request_id` 唯一索引幂等 + 前端 UUID 幂等键，命中返回原单（§3.6） |
| 数据量大时列表卡顿、深分页慢 | 后端分页（两步查询延迟回表）+ 300ms 防抖 + OFFSET 深度上限 10000（§4.7） |
| 库存 upsert 并发唯一键冲突 | 记录为已知边界，选做 A 统一设计并发方案后解决（§3.7④ → §7） |

### 3. 如果有更多时间，你还会做什么？

- **游标分页（keyset）**：数据量增大后根治深分页（`WHERE id > :lastId`），当前自增主键已满足条件；
- **定时对账任务**：在「懒加载 + 增量维护 + 启动重建」基础上加 `@Scheduled` 定时核对 Redis 镜像与 DB，收敛偏差窗口；
- **认证与权限体系**：引入登录 / JWT + 操作审计，消除当前"无认证"的已知边界（漏洞清单第 2 项）；
- **前端组件级单测**：目前 vitest 覆盖筛选纯函数（7 例），可扩展到表单 / 表格组件；
- **生产化配置**：数据库口令改为环境变量注入、连接池与超时参数化、Docker 化部署、CI 流水线自动执行测试与冒烟；
- **AI 业务模块（LLM 赋能）**：把 AI 从"开发工具"延伸到"业务能力"——① 自然语言查库存（如"SKU-001 在哪些库位、还有多少"→ LLM 转结构化查询参数，复用现有两步分页接口）；② 智能补货预测（基于历史出入库数据预测需求，生成补货建议）；③ 库存健康度分析（结合低库存/超卖记录给出运营建议）；④ 深化 AI 辅助开发流程本身（AI 代码 review、AI 生成测试用例与冒烟脚本）。

---

## 1. 总体开发流程（每个任务统一执行）

1. **理解需求**：通读 `TASKS.md` 任务描述与 `docs/API_SPEC.md` 接口约定，梳理数据流；
2. **设计**：确定接口契约、事务边界、库存/单号等核心逻辑的落点；**设计方案先与用户确认、听取用户建议，确认后再编码**，并将关键决策点记录为该任务的「设计沟通记录」小节（方案分析 → 用户反馈/决定 → 最终方案），供面试官查看人机协作过程；
3. **编码实现**：按模板既有风格实现（Lombok + Builder + DTO 转换 + `@Transactional` + `BusinessException`）；
4. **对照需求文档验收**：逐条核对 TASKS.md 考核点与 API_SPEC 字段是否满足（见各任务「验收对照表」）；**同时以用户视角走查功能闭环**（操作后有无明确反馈、空态/加载态/错误提示是否齐全、边界输入与异常路径、页面交互是否可用），不完整的补全后再验收 —— 该检查并入本步，不单独成节记录；
5. **冒烟测试**：
   - 根目录 `smoke-test.ps1`：对运行中的后端做接口级冒烟（正向 + 异常用例），`powershell -File smoke-test.ps1` 即可运行；
   - 每个任务配套 **2 个 JUnit 测试类**（Service 层 + API 层），`mvn test` 可重复执行，用例 `@Transactional` 回滚不污染数据；
6. **漏洞思考**：按**固定清单**逐项审视（每个任务都用同一套，见下表）；
   - **影响大 → 修复并做兜底**（含回归用例）；
   - **影响小 / 已免疫 / 测试范围外 → 记录结论后继续**；

**漏洞思考固定清单（8 项，每任务必检）：**

| # | 方面 | 检查要点 |
|---|------|---------|
| 1 | 注入 | SQL / JPQL / 命令 / 表达式注入：是否全部参数化，有无字符串拼接 |
| 2 | 认证与越权 | 未授权访问、水平 / 垂直越权（本系统无登录，属测试范围外；引入认证时必检） |
| 3 | 敏感信息泄露 | 报错堆栈、日志敏感字段、响应多余字段、配置明文口令 / 密钥 |
| 4 | 输入边界 | 长度 / 范围 / 格式 / 类型白名单校验（超长、超限、非法字符） |
| 5 | 幂等与重复提交 | 连点、弱网重试、并发同请求是否产生重复数据 |
| 6 | 并发与竞态 | 超卖、库存扣减、唯一键冲突、脏读 / 不可重复读 |
| 7 | XSS 与输出转义 | 前端回显是否转义、HTML / 脚本注入 |
| 8 | 可用性与兜底 | 分页上限、超时、限流、异常兜底（500 兜底、重试安全、CSRF 等） |

7. **代码 Review**：从四个角度自查并修复 —— **事务安全 / SQL 性能 / 空指针风险 / 并发问题**，修复同时补回归用例。

### 1.1 Bug 定位与修复流程（任务 3：找问题能力）

> 适用：从页面/接口发现 bug 后的完整排查链路。核心：**稳定复现 → 分层定位 → 结构化协作 → 回归闭环**。

1. **页面操作触发 bug**，记录**最小复现步骤**（哪个页面 → 点了什么 → 期望什么 → 实际什么）；
2. **F12 → Network 定位接口**，看请求与响应；
3. **判断前后端分界**——接口数据对不对？
   - 响应 500 / 数据错误 → **后端**：看 `backend-java/target/run.log` 堆栈 + 直查 SQL 对比预期；
   - 响应 200 且数据正确 → **前端**：渲染 / 状态 / 交互逻辑；
4. **定义「预期 vs 实际」**（对照 TASKS / API_SPEC），用 debug / 日志 / SQL 确认根因；
5. **修复**（两种分支）：
   - **分支 1（人工主导）**：自己手动修 → AI 做代码 review；
   - **分支 2（AI 辅助）**：向 AI 结构化描述 —— ① 现象：某接口/页面出现某问题 ② 复现步骤 ③ 预估根源与代码位置；**先 review AI 的修复方案再让它落地** → AI 修复 → 人工 review 代码；
6. **补回归用例**（自动化：Service / API 测试 + smoke 用例），防止复发；
7. **页面回归原场景**：回到触发 bug 的页面做同样的操作确认修复（自动化测试通过 ≠ 页面场景通过）；
8. **全量冒烟 + 漏洞思考**（固定 8 项清单）；
9. **NOTES.md 记录**（任务 3 硬性要求），模板：现象 → 复现步骤 → 根因 → 修复方式 → 为何会产生 → 回归验证。

---

## 2. AI 协作说明

- **工具**：DeepSeek Harness 编码智能体（deepseek-v4-flash 模型），以对话方式辅助编码。
- **协作方式**：理解需求后，**先与用户确认设计方案、听取用户建议**，确认后再让 AI 按模板风格生成初版实现 → 人工审查事务边界、参数校验与边界条件 → 用测试验证 → 发现问题反馈 AI 协助排查。
- **AI 使用边界**：所有 AI 产物都经过人工 review 与测试验证后才提交。

---

## 3. 任务 1：入库单创建（已完成）

### 3.1 需求理解

- **业务**：采购部门将商品入库到指定仓库的库位；创建入库单时自动累加对应库位库存，全程事务一致。
- **接口契约**（API_SPEC 3.1）：`POST /api/inbound-orders`，请求含 `supplierName` + `items[{productId, quantity, locationCode}]`；响应含 `orderNo`（格式 `IN-YYYYMMDD-XXX`）、`status`、明细回显。
- **前端**：入库表单 —— 商品下拉搜索、仓库→库位级联、数量、多行明细、提交。

### 3.2 设计

| 设计点 | 方案 |
|--------|------|
| 数据落点 | 表结构模板已提供（`inbound_orders` / `inbound_order_items` / `inventory`，含 `uk_product_location` 唯一键）；模板缺 `InboundOrderItemRepository` → 新建 |
| 单号生成 | `IN-YYYYMMDD-XXX`：查询当日已存在单号取最大序号 +1 |
| 事务 | `@Transactional` 包裹「入库单主表 + 明细 + 库存累加」，任一步失败整体回滚 |
| 库存累加 | 按 `(product_id, location_code)` 唯一键 upsert：存在则累加、不存在则新建 |
| RESTful 状态 | 创建成功返回 HTTP 201（`@ResponseStatus(CREATED)`）；body 沿用统一信封 `code=200 + message + data`，与「通用约定」一致 |
| 校验 | DTO 层 `@NotNull/@NotBlank/@Min` + `@Valid` 级联校验；商品/库位存在性由 Service 层 `orElseThrow` 校验（404） |

### 3.2.1 设计沟通记录（人主导决策，AI 提供分析并执行）

> 每个任务的设计环节都会与候选人（需求方）确认关键决策点，记录如下。表格展示「方案分析 → 反馈决定 → 最终落地」的完整决策链，体现候选人对技术方案的判断与拍板，而非 AI 单方面决定。

| 决策点 | 提出的方案 / 分析 | 候选人的反馈 / 决定 | 最终方案与理由 |
|--------|------------------|--------------------|----------------|
| 创建接口的 HTTP 状态码 | 初版实现 POST 返回 HTTP 200，body 装统一信封 | 提出疑问：RESTful 化是否多余（反正都要自己填 data/message）→ 讨论后拍板：**创建成功用 HTTP 201，body 沿用统一信封 code=200** | `@ResponseStatus(CREATED)` + body `code=200 + message + data`；删除 `ApiResponse.success(int code, ...)` 重载，保持信封约定唯一。理由：语义化状态码与统一信封不冲突，两全 |
| 防重复入库 / 幂等 | 提出两种方案对比：① DB `request_id` 唯一索引（正确性保证）② Redis `SETNX` 入口门控（只能减少无效请求，宕机/过期后仍可能重复） | 主动提出「能不能用 Redis setnx 防重复」，并说明后续要做高并发预库存扣减选做题 | 采纳方案①为正确性层：`request_id` 唯一索引 + 命中即返回原单；Redis 仅作为可选的并发门控留待选做 A。理由：幂等必须以持久化为准，缓存不可靠 |
| 前端明细录入方式 | 初版在表格内直接添加行 | 明确要求：**添加用表单形式填写，不要在表格中添加** | 改为弹窗表单（商品搜索 → 仓库 → 库位级联 → 数量），表格只做展示/编辑/删除。理由：复杂校验与级联逻辑在表单中更清晰 |
| 明细录入交互（迭代） | 按上一决策实现为弹窗表单 | 反馈：弹窗体验不好，要求**回到表格内直接添加**（填完信息直接新增）；并主动提出**草稿持久化**：填的信息缓存到浏览器 localStorage，防止跳转/刷新后辛辛苦苦填的内容丢失 | 改为表格内行内编辑：点「+ 添加明细」在表格末尾追加一行，单元格直接变下拉/输入控件，填完点行内「保存」固化；供应商名 + 已确认明细 + 正在填的编辑行存 localStorage，进页面自动恢复并提示「已恢复上次未提交的草稿」，提交成功后自动清除 |
| 前端页面风格 | 三个页面各自实现 | 要求：**统一风格，数据用表格展示** | 抽出 `PageCard` 容器 + `el-table border stripe` + 统一工具栏/分页，三个页面复用 |
| 入库单列表 / 详情 | 候选人反馈「提交入库没有新增」（初步判断为功能缺失）→ 排查确认 DB 插入实际成功，缺的是**列表展示** → 补列表/详情接口与页面 | 后续确认**理解错需求**，要求回退列表/详情 | 回退：删除 `GET /inbound-orders`、`GET /inbound-orders/{id}` 及前端列表区域，页面恢复纯入库表单，相关测试/文档同步回退。理由：需求理解偏差在验收前发现并纠正，避免过度实现 |
| 质量流程 | 建议每完成一个接口补冒烟测试与代码 review | 采纳并要求固化为流程：**每接口冒烟 + 固定 8 项漏洞清单 + 四角度 review**，NOTES.md 面向面试官记录 | 流程固化（见 §1）：理解需求 → 设计确认 → 编码 → 验收 → 冒烟 → 漏洞思考 → review |

### 3.3 实现（文件清单）

| 文件 | 说明 |
|------|------|
| `dto/InboundItemRequest.java` | 新建。入库明细请求项（独立文件，见 §2） |
| `dto/InboundOrderResponse.java` | 新建。创建响应 DTO（含明细 productName，对齐 API_SPEC） |
| `repository/InboundOrderItemRepository.java` | 新建。明细 Repository（模板缺失） |
| `repository/InboundOrderRepository.java` | 新增 `findOrderNosByPrefix`（单号生成用）、`findByRequestId`（幂等） |
| `repository/LocationRepository.java` | 新增 `findByCode` |
| `service/InventoryService.java` | 实现 `createInboundOrder()`（单号/校验/事务/库存 upsert/幂等） |
| `controller/InventoryController.java` | `POST /api/inbound-orders`（HTTP 201） |
| `views/InboundView.vue` | 入库表单完整实现：明细以**弹窗表单**添加/编辑（商品搜索/仓库→库位级联/数量），表格只做展示与编辑/删除，提交携带幂等键 |
| `api/index.ts` | 新增 `createInboundOrder` 封装与类型 |
| `components/PageCard.vue` | 新建。统一页面卡片容器（标题 + 内容区），三个页面共用 |
| `views/ProductsView.vue` / `views/InventoryView.vue` | 统一套用 PageCard + 工具栏 + 表格 + 分页风格（InventoryView 业务逻辑属任务 2） |
| `vite.config.ts` | `/api` 代理指向 Java 后端 8080 |

### 3.4 对照文档验收

| TASKS.md / API_SPEC 要求 | 实现 | 满足 |
|------|------|:---:|
| 入库单号自动生成 `IN-YYYYMMDD-XXX` | `generateOrderNo()` 当日序号 | ✅ |
| 供应商名称、明细列表（商品/数量/库位） | 请求 DTO + `@Valid` 级联校验 | ✅ |
| 创建时自动累加对应库位库存 | `(product_id, location_code)` upsert | ✅ |
| 数据库事务保证一致性 | `@Transactional`，失败整体回滚 | ✅ |
| API 设计 RESTful | `POST /api/inbound-orders` + HTTP 201 | ✅ |
| 异常处理完善（库位不存在、数量校验） | `BusinessException(404)` + Bean Validation(400) | ✅ |
| 前端表单：商品下拉搜索/仓库→库位级联/数量/多行/提交 | `InboundView.vue` | ✅ |

### 3.5 冒烟测试

| 手段 | 内容 | 结果 |
|------|------|:---:|
| `smoke-test.ps1`（接口冒烟，16 用例） | 参考接口回归（商品/仓库/库位）+ 入库正向（201/单号格式/单号递增/重复明细/多明细/**幂等重放**）+ 异常（404×2、400×5） | ✅ 全过 |
| `InventoryServiceTest`（Service 层，5 用例） | 创建成功 + 库存累加、同订单重复明细累加、商品不存在 404、库位不存在 404、**同 requestId 幂等** | ✅ 全过 |
| `InboundOrderApiTest`（API 层，4 用例） | HTTP 201 + body code 200 + 单号格式、商品不存在 body 404、quantity 缺失 400（回归）、**同 requestId 幂等重放** | ✅ 全过 |
| 数据库核对 | 库存最终值直查 MySQL：`(1, WH-A-01-01)` 150→180（+10+20）、`(2, WH-A-01-02)` 无→5（upsert 新建） | ✅ 一致 |

> 测试配置：`src/test/resources/mockito-extensions/` 中启用 Mockito **subclass mock maker**（免 agent 附加），使 `mvn test` 在常规 JDK 与受限 CI 环境均可稳定运行。

### 3.6 漏洞思考（按固定 8 项清单逐项审视）

| # | 风险 | 场景 | 影响 | 处置 |
|---|------|------|:---:|------|
| 5 | **重复提交 / 弱网重试（无幂等）** | POST 超时后客户端重试或连点 → 重复入库单 + 库存重复累加 | 🔴 高 | **已修复**：`requestId` 幂等键（前端每次表单会话生成 UUID，失败重试复用；后端 `request_id` 唯一索引 + 命中即返回原单不重复累加），Service/API/冒烟三层回归用例 |
| 4 | 数量无上限 | 超大 `quantity` → int 溢出 / 负数库存 | 🟡 中 | **已修复**：`@Max(999999)`（与前端输入上限一致） |
| 4 | 超长字符串 | 供应商名 / 库位编码超列长 → DB 层 500 | 🟡 低-中 | **已修复**：`@Size(max=200/50)` 与列长度一致 |
| 1 | SQL 注入 | 搜索词、单号等输入拼接 | 无 | 已免疫：全部走 JPA 命名参数 / 参数化查询（记录） |
| 7 | XSS | 商品名等数据回显 | 无 | 已免疫：Vue 模板默认转义 + Element Plus（记录） |
| 2 | 认证 / 越权 | 系统无登录体系 | 🟡 中 | 接受：API_SPEC 未定义认证，属测试范围外；引入登录时必检（记录） |
| 5 | 并发同 requestId | 同一幂等键并发提交 | 🟡 低 | 接受：顺序重试已兜底；并发由唯一索引保护（后者回滚 500，不产生脏数据） |
| 6 | 库存 upsert 并发 | 并发入库同一 (商品, 库位) | 🟡 中 | 接受：任务 1 边界，选做 A 统一设计并发方案（记录） |
| 8 | 弱网超时无自动重试 | 请求超时 | 低 | 接受：前端提示错误 + `requestId` 保证手动重试安全（记录） |
| 3 | 敏感信息 | `application.yml` 明文数据库口令 | 低 | 接受：本地开发配置；zip 提交时删除环境文件（记录） |
| 8 | CSRF | 写操作跨站伪造 | 无 | 已免疫：无会话 Cookie 认证，JSON 写接口不受 CSRF 影响（记录） |

**结论**：按固定 8 项清单审视 —— 高影响项（重复入库）与中等影响项（数量/长度边界）均已修复兜底并有回归用例；注入、XSS、CSRF 已免疫；认证、并发、弱网、敏感信息等为测试范围外或可接受边界（已记录）。

### 3.7 代码 Review（四个角度）

**① 事务安全**
- `createInboundOrder()` 整体 `@Transactional`：主表、明细、库存累加同事务，任一步失败（含 404 校验）整体回滚，无部分成功；
- 同订单内重复 `(商品, 库位)` 明细：同一事务内先 INSERT（IDENTITY 立即落库）再查询，auto-flush 保证正确累加 —— 已由 `InventoryServiceTest` 与 DB 核对双重验证。

**② SQL 性能**
- 无 N+1：明细循环均为必要的写操作，每次查询走主键（`findById`）或唯一键（`findByProductIdAndLocationCode` 命中 `uk_product_location`）；
- 单号生成查询限定当日前缀（`LIKE 'IN-yyyyMMdd%'`），数据量可控；
- 预校验已移除：`@Transactional` 保证失败回滚，避免每明细两次查询（exists + findById）。

**③ 空指针风险**
- 请求体：`@NotNull`(productId/quantity) + `@NotBlank`(supplierName/locationCode) + `@Min(1)`(quantity) + `@Valid` 级联，缺失/非法字段在 Controller 入口即 400；
- 商品/库位不存在：`findById/findByCode` + `orElseThrow(BusinessException(404))`，无空引用路径；
- review 修复过一处真实问题：`quantity` 原仅 `@Min(1)`，**null 会绕过校验**（Bean Validation 对 null 不校验）导致 NPE → 补 `@NotNull` 并加回归用例。

**④ 并发问题（已知边界，任务 1 范围内可接受）**
- 单号生成基于「当日最大序号」，极端并发下两个请求可能算出相同序号 → 由 `order_no` 唯一约束兜底（后者回滚报错）；
- 库存 upsert（查询 + 累加 + 保存）非原子，并发入库同一 `(商品, 库位)` 可能唯一键冲突 → 500；
- 二者均为测试场景可接受的边界；若做选做 A（出库扣减），将统一设计并发方案（悲观锁 / 原子 `UPDATE ... WHERE quantity >= N` / 唯一键冲突重试），并在此补充说明。

### 3.8 验收结论

任务 1 功能闭环完整（创建入库单 → 库存即时累加）、文档对照全部满足、冒烟测试与 2 个测试类全绿（16 用例 / 9 测试）、漏洞思考中高影响项（重复入库）已做幂等兜底、review 发现的问题已修复并有回归用例覆盖。

---

## 4. 任务 2：库存查询（已完成）

### 4.1 需求理解

- **业务**：仓库管理员查看各库位实时库存，支持筛选与分页。
- **接口契约**（API_SPEC 4）：`GET /api/inventory?keyword=&warehouseId=&page=&pageSize=`，返回 `PageResult`（商品名/SKU/库位/仓库名/数量/更新时间）。
- **前端**：搜索栏（商品名/SKU 模糊 + 仓库下拉）、表格分页、**库存 < 10 红色高亮**。
- **考核点**：SQL 索引使用、分页正确性、前端状态管理、搜索防抖。

### 4.2 设计

| 设计点 | 方案 |
|--------|------|
| 查询实现 | 单条 JPQL：`Inventory JOIN Product JOIN Location JOIN Warehouse`，一次查询返回含商品名/SKU/仓库名的响应（库存表无仓库字段，经 库位编码→库位→仓库 反查） |
| 筛选 | `keyword` 模糊匹配 商品名称 / SKU / 库位编码（用户拍板：一个输入框覆盖）；`warehouseId` 下拉精确筛选仓库 |
| 告急筛选（增强） | `lowStockOnly=true` 时仅返回 `quantity < 10`，支撑前端「告急库存提示条」点击切换（API_SPEC 未定义，属用户要求的交互增强） |
| 分页 | **两步查询**：先查 id 集合（走主键索引零回表，分页+count 在此层）→ 再 `IN` 取详情按 id 重排（回表只发生在要返回的行）；pageSize 上限 100、页码下限 1；**OFFSET 深度上限 10000**（深分页兜底，超限返回 400 提示缩小筛选） |
| 性能 | join 全部走主键 / 唯一键（`products.id`、`locations.code`、`warehouses.id`）；`warehouseId` 走 `locations.warehouse_id` 外键索引；单查询避免 N+1；`LIKE %kw%` 前导通配无法走索引（测试规模可接受，见 review） |

### 4.2.1 设计沟通记录（人主导决策，AI 提供分析并执行）

| 决策点 | 提出的方案 / 分析 | 候选人的反馈 / 决定 | 最终方案与理由 |
|--------|------------------|--------------------|----------------|
| keyword 搜索范围 | API_SPEC 仅定义 商品名称/SKU 模糊；TASKS 另提到库位编码筛选 | 拍板：**名称 + SKU + 库位编码三者都匹配**，一个输入框全覆盖 | JPQL `name/sku/locationCode` 三字段 OR 模糊匹配。理由：实用优先，避免用户来回切换筛选维度。仓库维度走 `warehouseId` 下拉精确筛选（曾尝试把仓库名并入 keyword，确认与下拉功能重复后回退） |
| 低库存展示 | 方案 A：仅红色文字加粗；方案 B：整行浅红背景 | 提出增强：**红色加粗 + 列表头顶「告急库存」提示条（显示数量），点击后列表只显示告急行** | 前端告急提示条 + 后端 `lowStockOnly` 筛选参数（分页在服务端，前端过滤只对当前页生效不准确，故下沉到 SQL）。理由：告急数准确且可翻页，交互闭环 |
| 搜索交互 | 方案 A：输入防抖 300ms 自动搜索 + 查询按钮；方案 B：仅按钮 | 选方案 A，并要求**自动搜索时给用户提示**（避免"列表怎么自己变了"的困惑） | 防抖自动搜索 + 完成后轻提示「已按条件自动筛选，共 N 条」（短时长，不刷屏）。理由：体验好且满足防抖考核点 |

### 4.3 实现（文件清单）

| 文件 | 说明 |
|------|------|
| `repository/InventoryRepository.java` | 新增 `searchInventory`：单条 JPQL（join 商品/库位/仓库 + keyword/warehouseId/lowStockOnly 筛选 + Pageable 分页） |
| `service/InventoryService.java` | 实现 `queryInventory(keyword, warehouseId, lowStockOnly, page, pageSize)` → `PageResult<InventoryResponse>`（blank→null 归一） |
| `controller/InventoryController.java` | `GET /api/inventory` 接入 service，pageSize 上限 100、页码下限 1 |
| `dto/InventoryResponse.java` | 模板已提供（未改） |
| `views/InventoryView.vue` | 完整实现：搜索栏（防抖自动搜索 + 查询按钮 + 仓库下拉级联刷新）、告急提示条（点击切换 lowStockOnly）、quantity<10 红色加粗、分页、空态、自动搜索轻提示 |
| `api/index.ts` | `getInventory` 增加 `lowStockOnly` 参数 |

### 4.4 对照文档验收

| TASKS.md / API_SPEC 要求 | 实现 | 满足 |
|------|------|:---:|
| 按商品名称/SKU/库位筛选（keyword） | JPQL 三字段 OR 模糊匹配 | ✅ |
| 按仓库筛选（warehouseId） | `l.warehouseId = :warehouseId` | ✅ |
| 分页 page/pageSize（默认 1/20，最大 100） | `Pageable` + count，pageSize 上限 100 | ✅ |
| 返回 商品名/SKU/库位/仓库名/数量/更新时间 | `InventoryResponse` join 三表 | ✅ |
| 避免全表扫描 | join 走主键/唯一键 + 外键索引，单查询无 N+1 | ✅ |
| 前端搜索栏 + 表格 + 分页 | `InventoryView.vue` | ✅ |
| 库存 < 10 红色高亮 | `row-style` 红色加粗 | ✅ |
| 搜索防抖 | 300ms 防抖自动搜索 + 提示 | ✅ |

### 4.5 冒烟测试

| 手段 | 内容 | 结果 |
|------|------|:---:|
| `smoke-test.ps1`（接口冒烟，22 用例） | 原 16 用例 + 库存 6 用例（PageResult 结构 / keyword 按 SKU / warehouseId 筛选 / lowStockOnly 告急 / pageSize 上限 / **深分页兜底 400**） | ✅ 全过 |
| `InventoryQueryServiceTest`（Service 层，6 用例） | 字段完整（含仓库名 join 反查）、keyword 按 SKU/库位筛选、warehouseId 筛选（WH-A/WH-B 互斥）、lowStockOnly 只返回 <10、分页 page/pageSize/total、**深分页 offset 超限抛 400（含边界 9900 通过）** | ✅ 全过 |
| `InventoryApiTest`（API 层，7 用例） | 200 + PageResult 结构、keyword 行级断言、warehouseId 仓库名非空、lowStockOnly 每行 <10、pageSize 9999 截断 100、page=0 回退第 1 页、**深分页 400** | ✅ 全过 |
| `mvn test` 全量 | 22 用例（任务 1 的 9 + 任务 2 的 13） | ✅ 全过 |

> 测试数据确定性：Service 测试内新建唯一商品（UUID SKU）并入库，再断言查询行为，不依赖既有库存数据。

### 4.6 漏洞思考（按固定 8 项清单逐项审视）

| # | 风险 | 场景 | 影响 | 处置 |
|---|------|------|:---:|------|
| 1 | SQL 注入 | keyword 等输入拼接 | 无 | 已免疫：全部 JPA 命名参数（`:keyword` 等），无字符串拼接（记录） |
| 8 | 分页无上限 | 传超大 pageSize → 全表拉取拖垮 DB | 🟡 中 | **已修复**：pageSize 上限 100 + 页码下限 1（API 层 + Service 层双重防御，测试回归） |
| 4 | keyword 超长 | 超长模糊串 → 查询开销 | 🟡 低 | 接受：查询接口参数无长度限制；测试规模影响可忽略，若上线可加 `@Size`（记录） |
| 5 | 重复提交 | 查询为只读 GET | 无 | 天然幂等（记录） |
| 6 | 并发 | 查询无写操作 | 无 | 只读，无竞态（记录） |
| 7 | XSS | 商品名等回显 | 无 | 已免疫：Vue 模板默认转义（记录） |
| 2 | 认证 / 越权 | 系统无登录体系 | 🟡 中 | 接受：API_SPEC 未定义认证，测试范围外；引入登录时必检（记录） |
| 3 | 敏感信息 | 响应字段泄露 | 无 | 响应仅业务字段，无堆栈/配置泄露（记录） |
| 8 | 查询超时无限制 | 慢查询拖线程 | 低 | 接受：查询走索引 + 分页上限，测试规模无慢查询；生产可配连接/语句超时（记录） |

**结论**：按固定 8 项清单审视 —— 分页上限已修复兜底并有回归；注入、XSS、幂等、并发天然免疫；认证、keyword 长度、超时等为范围外或可接受边界（已记录）。

### 4.7 代码 Review（四个角度）

**① 事务安全**
- 查询接口只读，无事务写操作；`PageResult` 组装无状态，无部分成功问题。

**② SQL 性能**
- **分页采用两步查询（延迟回表）**：`findIdsByFilters` 只查 `SELECT i.id`（走主键/筛选索引，无回表，分页 + count 在此层）→ `findDetailsByIds` 按 `id IN` 查完整明细，回表只发生在真正要返回的行；两次查询同置于 `@Transactional(readOnly=true)` 保证同一快照；
- 单条 join 查询避免「逐行反查商品/仓库」的 N+1；join 走主键 / 唯一键 / 外键索引（见下）；
- **筛选字段索引**：`inventory.location_code`（`idx_inventory_location_code`）、`products.name`（`idx_products_name`），JPA `@Table(indexes=...)` 声明、`ddl-auto=update` 自动建；`warehouseId` 走 `locations.warehouse_id` 外键索引；
- **深分页兜底**：`OFFSET = (page-1)*pageSize > 10000` 时拒绝查询（`BusinessException 400`，提示缩小筛选），防止页码无限增大导致全表扫描；
- **游标分页（keyset）演进方案**：自增主键满足 keyset 条件（`WHERE id > :lastId ORDER BY id LIMIT :size`），可根治深分页；但会破坏「页码跳转 + total」契约与现有分页 UI，且当前数据规模无深分页问题——**当前保留 offset 分页 + 深度兜底**，数据量增大（如选做 C 500+ 行）时再切游标；
- **EXPLAIN 验证**：查询 `possible_keys` 已包含 `idx_inventory_location_code`、`warehouse_id` 等索引（当前仅个位数库存行，优化器按成本选全表扫描属正常；数据量上来后自动走索引）；
- **清理冗余索引**：inventory 表两个完全相同的唯一索引（模板 SQL `uk_product_location` 与 Hibernate `UK3kq...`）——删除模板 SQL 建的冗余索引，避免写放大；
- 已知边界：`LIKE '%kw%'` 前导通配无法走索引（B-tree 仅等值/前缀），keyword 命中 name/sku/locationCode 三列，测试规模可接受；生产可考虑 FULLTEXT 或前缀匹配改造。

**③ 空指针风险**
- `keyword`/`warehouseId` 可空：Service 层 blank→null 归一，JPQL `:param IS NULL` 短路，无 NPE；
- `lowStockOnly` 布尔参数有默认值，不会为 null；
- `InventoryResponse` 由 JPQL `new` 表达式构造，字段来源均有 join 保证非空（quantity 为 `Integer`，DB 列 NOT NULL）。

**④ 并发问题**
- 查询无写操作，无竞态；不引入共享可变状态（前端防抖 timer 为组件内局部变量，卸载即失效）。

### 4.8 验收结论

任务 2 功能闭环完整（搜索/筛选/告急/分页/高亮）、API_SPEC 与考核点全部满足、冒烟 22 用例 + 2 个测试类全绿（22 测试）、漏洞思考中分页上限与深分页深度均已兜底、review 确认索引、两步查询与空指针无问题。

---

## 5. 任务 3：修复 2 个预埋 Bug（已完成）

> 按 §1.1「Bug 定位与修复流程」实战：页面触发 → 网络定位 → 前后端分界 → 根因 → 修复 → 回归闭环。

### 5.1 Bug 1：商品删除未校验关联（后端）

| 项 | 内容 |
|------|------|
| **现象** | 删除有库存的商品：后端报 500（数据库外键约束违反），前端提示操作失败，删除后继续进行bug复现 |
| **复现步骤** | 商品列表页 → 删除任一有库存商品（如 SKU-001）→ 报 500 |
| **根因** | `ProductService.delete()` 预埋点：只校验 `existsById`，**未校验关联库存/历史入库记录**；直接 `deleteById` 撞上 `inventory`/`inbound_order_items` 的外键约束 → 500。若无外键则会删掉商品留下孤立库存（TASKS 描述的脏数据场景） |
| **修复方式** | 删除接口加 `force` 参数（默认 false）：默认先统计关联（库存 N 条 / 历史入库记录 N 条），>0 时返回 400 提示数量；前端捕获后弹确认框，用户二次确认后带 `force=true` 重试；force 模式下在同一事务内**级联清理**关联库存 + 历史入库记录后再删商品。无关联时直接删除 |
| **为何会产生** | 模板为演示"最简删除"省略了业务校验，把引用完整性完全丢给数据库外键，导致用户侧体验是 500 而非友好提示 |
| **回归验证** | `ProductDeleteServiceTest` 4 用例（有关联拒绝 / force 级联清理 / 无关联成功 / 不存在 404）+ smoke 2 用例（有关联 400、新建无关联商品删除 200）；`mvn test` 26/26、smoke 24/24 |

### 5.2 Bug 2：商品列表编辑后跳回第 1 页（前端）

| 项 | 内容 |
|------|------|
| **现象** | 商品列表翻到第 3 页，编辑某商品保存后列表跳回第 1 页 |
| **复现步骤** | 商品列表页 → 翻到第 2/3 页 → 点「编辑」→ 修改保存 → 列表回到第 1 页（期望停留在原页） |
| **根因** | `ProductsView.vue` 预埋点：`handleSubmit` 成功后执行 `currentPage.value = 1` 再重新加载——**硬编码重置页码**。该页是前端分页（全量拉取 + computed 切片），后端 update 接口无关页码，纯前端状态问题 |
| **修复方式** | 删除 `currentPage.value = 1`，编辑/新增后保持当前页码重新加载；删除后增加**空页回退**保护（当前页无数据则回退一页） |
| **为何会产生** | 模板写死了"操作完回第 1 页"，未考虑用户正在浏览的分页位置，属于典型的状态丢失类 bug |
| **回归验证** | 前端无单测框架，靠 vue-tsc + 页面手动验证（翻页→编辑→确认停留原页；删空页数据→确认回退） |

### 5.3 设计沟通记录（任务 3）

| 决策点 | 提出的方案 / 分析 | 候选人的反馈 / 决定 | 最终方案与理由 |
|--------|------------------|--------------------|----------------|
| Bug 1 修复策略 | 方案 A：有关联直接拒绝删除；方案 B：级联删除 | 提出：**提示后二次确认，确认后才删除**（直接拒绝体验差、直接删又危险） | 默认校验返回 400（附关联数量）→ 前端确认框 → `force=true` 事务内级联清理。理由：两级确认兼顾安全与体验 |
| Bug 2 修复细节 | 保持页码重新加载 | 选择**保持页码 + 空页回退** | 删除后若当前页为空自动回退一页。理由：删除最后一条时避免停在空页 |

### 5.4 验收结论

任务 3 两个预埋 Bug 均按 §1.1 流程定位并修复：删除接口具备关联校验与二次确认（事务级联清理防脏数据）、商品页编辑保持页码；回归用例（26 测试 / 24 冒烟）全绿，修复点已在代码注释与 NOTES 中标注。

---

## 7. 选做 A：出库单 + 库存扣减并发防超卖（已完成）

### 7.1 需求理解

- 实现出库单创建功能，核心难点是**库存扣减的并发安全**：出库时检查库存是否充足、高并发下防止超卖；
- 需要说明选择的并发控制方案及理由（写入 NOTES）；
- 与既有体系对齐：统一信封 `ApiResponse`、HTTP 201 创建、request_id 幂等、前端表格行内编辑交互、草稿持久化。

### 7.2 设计

**并发防超卖方案：Redis Lua 原子预扣（高并发闸门）+ DB 原子条件更新（正确性兜底）双层防线**

```
出库请求
  │
  ├─ 1. 幂等检查（requestId 唯一索引，命中返回原单）
  ├─ 2. 校验商品/库位 + 按 (productId, locationCode) 合并明细
  ├─ 3. Redis Lua 原子预扣（第一道闸）：GET 剩余 >= 需求 → DECRBY，否则拒绝
  │      - key 不存在 → 懒加载 DB 当前库存 SETNX 后重试一次
  │      - Redis 连接异常 → fail-open 放行（日志告警）
  ├─ 4. DB 事务（同一请求内同步执行，无延迟扣减窗口）：
  │      建出库单 + 明细 + 原子条件扣减
  │      UPDATE inventory SET quantity = quantity - N, updated_at = NOW()
  │      WHERE product_id=? AND location_code=? AND quantity >= N
  │      （受影响行数 = 0 → 数据库判定库存不足 → 抛 400 回滚）
  └─ 5. 任一步失败 → catch 中对已预扣的 Redis 行 revert() 补偿回滚 → 抛出
```

**为什么这样设计（理由）：**

| 方案 | 分析 |
|------|------|
| **Redis Lua 原子预扣（第一道闸）** | Redis 单线程执行 Lua 天然原子；高并发下在内存中毫秒级完成"检查+扣减"，把库存不足的请求挡在 DB 之外，减轻 DB 行锁竞争。预扣失败直接 400，不占 DB 事务 |
| **DB 原子条件更新（最终兜底）** | `UPDATE ... WHERE quantity >= N` 在 InnoDB 下对命中行加 X 锁，同一行并发扣减天然串行，库存永远不会被扣成负数。即使 Redis 预扣成功、或 Redis 挂了被降级放行，正确性始终由这条原子 SQL 保证——**Redis 与 DB 不一致只会"少卖"（多挡掉本可成功的请求），绝不超卖** |
| ~~乐观锁 version 重试~~ | 高并发下重试率高，且多一次读；适合读多写少场景，不选 |
| ~~悲观锁 SELECT FOR UPDATE~~ | 锁持有整个事务，并发吞吐低，多行扣减顺序不一致有死锁风险，不选 |
| ~~仅 Redis 预扣（无 DB 兜底）~~ | Redis 非持久化权威，宕机/数据丢失即超卖，不可作为唯一防线，不选 |

**并发安全发号：** 单号 `OUT-YYYYMMDD-XXX` 的序号最初用"查当日最大单号 + 1"，被并发测试暴露**事务内看不到未提交并发单号 → Duplicate entry**（synchronized 只串行化计算、save 未提交前读不到）。改为 **`order_sequences` 序列表 + MySQL 原子发号**：`UPDATE order_sequences SET next_value = LAST_INSERT_ID(next_value + 1) WHERE seq_type='OUT'` + `SELECT LAST_INSERT_ID()`——UPDATE 行锁让并发取号串行、LAST_INSERT_ID(expr) 返回本连接新值（与事务提交无关）。入库单号同步改造，全局递增、跨天不重置。

### 7.2.1 设计沟通记录（人主导决策，AI 提供分析并执行）

| 决策点 | 提出的方案 / 分析 | 候选人反馈 / 决定 | 最终方案与理由 |
|--------|------------------|------------------|----------------|
| 并发控制主方案 | 数据库原子条件更新（推荐）/ 悲观锁 / 乐观锁 | 主动提出：「数据库判断库存是否超扣兜底，lua 原子 redis 预扣应对高并发怎么样？」 | **双层防线**：Redis Lua 原子预扣做高并发门控 + DB 原子条件更新做正确性兜底（详见 §7.2）。Redis 承担吞吐、DB 承担正确性，两者职责分离 |
| Redis 预扣后何时更新数据库 | ——（用户主动提问，理解方案） | 追问：「没说明什么时候去更新数据库呢？」 | **预扣与 DB 扣减同一请求内同步完成**，无延迟扣减窗口；DB 失败即补偿回滚 Redis（详见 §7.2 一致性语义） |
| 出库页库存展示 | 加"可用库存"列（需给 GET /api/inventory 加 productId 参数）/ 不加只靠后端校验 | 选择加展示 | GET /api/inventory 增加可选 `productId` 过滤；行内选商品后实时显示该库位可用库存，超量前端直接拦截标红，后端仍兜底 |
| 并发验证方式 | 真实并发集成测试 / 仅 Mockito 单测 | 选择写真实并发测试 | `OutboundConcurrencyTest`：20 线程抢同一库存（初始 100、每单 6），断言成功数 ≤ 16、最终库存精确 = 初始 − 成功量、Redis 镜像一致 |
| Redis 运行方式 | 本机 E:\redis 启动 / Docker 容器 | 选择本机 | 本机 Redis 8.8（端口 6379、无密码），启动命令写入 README；应用侧 spring-data-redis 连接 |
| Redis 不可用降级 | fail-open 降级纯 DB / fail-closed 拒绝出库 | 选择 fail-open | Redis 连接异常时跳过预扣、直接走 DB 原子扣减：正确性由 DB 保证，只损失高并发拦截能力；系统可用性优先 |
| Redis 与 DB 一致性对账 | 懒加载 + 增量维护 + 启动重建 / 再加定时对账任务 | 选择前者 | 库存 key 懒加载（首次从 DB 读）+ 入库/出库成功时增量维护 + 服务启动全量重建；定时对账（@Scheduled）记为演进方案 |

### 7.3 实现（文件清单）

后端（`backend-java/src/main/java/com/wms/`）：
- `entity/OutboundOrder.java`、`entity/OutboundOrderItem.java`：出库单主表/明细（`outbound_orders` / `outbound_order_items`）
- `entity/OrderSequence.java` + `repository/OrderSequenceRepository.java`：单号序列表 + 原子发号（LAST_INSERT_ID 技巧）
- `repository/OutboundOrderRepository.java`、`repository/OutboundOrderItemRepository.java`：幂等查询 / 明细查询 / 商品关联统计与级联
- `repository/InventoryRepository.java`：新增 `deductStock` 原子条件扣减（@Modifying bulk update，手动刷新 updatedAt，注释说明一级缓存注意事项）
- `service/RedisStockService.java`：Lua 原子预扣（GET→DECRBY 脚本）/ 补偿回滚 / 入库镜像同步 / 启动重建 / fail-open 降级
- `service/OutboundOrderService.java`：出库主流程（幂等 → 校验合并 → 预扣 → DB 事务 → 失败补偿）；明细按 (商品,库位) 合并避免"扣一半回滚"
- `controller/OutboundController.java`：`POST /api/outbound-orders`（HTTP 201 + 统一信封）
- `config/StockSyncRunner.java`：启动时全量重建 Redis 库存镜像
- `config/DataInitializer.java`：初始化 `order_sequences`（IN/OUT 行，next_value 取当前 DB 最大序号 + 1）
- 联动：`InventoryService.createInboundOrder` 入库成功后同步 Redis 镜像；`ProductService.delete` 关联校验/级联清理扩展出库记录；`InventoryService/InventoryRepository/InventoryController` 查询增加 `productId` 过滤

前端（`frontend-vue/src/`）：
- `views/OutboundView.vue`：出库页（客户名称、表格行内编辑、**可用库存列**实时展示+超量前端拦截、localStorage 草稿 `wms.outbound.draft`、requestId 幂等键）
- `api/index.ts`：`createOutboundOrder` / `OutboundItemRequest` / `getInventory` 增加 `productId`；`router/index.ts` + `App.vue` 加 `/outbound` 入口

### 7.4 对照验收

- API_SPEC 约定：统一信封 `code=200` + HTTP 201 创建 ✓；参数校验（customerName 非空、quantity 1..999999、items 非空）✓；
- 幂等：requestId 唯一索引 + 命中返回原单 ✓（含前端失败重试复用、成功换新键）；
- 并发安全：20 线程真实并发集成测试通过（详见 7.5）✓；
- 前端：出库页行内编辑/草稿/可用库存列/路由菜单 ✓（vue-tsc 通过）。

### 7.5 冒烟测试

- **JUnit 39 用例全绿**（新增 12 例）：
  - `OutboundOrderServiceTest`（Mockito，6 例）：预扣成功 / Redis 库存不足 400 / DB 兜底拦截并补偿 Redis / 幂等重放 / 同商品库位合并只扣一次 / 商品不存在 404；
  - `OutboundOrderApiTest`（MockMvc，5 例）：201+信封+单号 `OUT-\d{8}-\d{3,}` / 库存不足 400（body code 400）/ 幂等重放同单号 / 商品 404 / 库位 404；
  - `OutboundConcurrencyTest`（**真实并发**，1 例）：20 线程 × 6 件抢库存 100，断言成功数 ≤ 16、成功数×6 + 最终库存 = 100、Redis 镜像与 DB 一致、库存 ≥ 0；
  - 既有测试适配：queryInventory 新增 productId 参数、单号格式断言放宽（序号全局递增后可能超过 3 位）。
- **smoke 28 用例全绿**（新增 4 例）：出库正向（201 + OUT 单号）、幂等重放、库存不足 400、`GET /api/inventory?productId=` 过滤；用独立商品 SMOKE-OUT-1 + 入库/出库数量平衡，可重复运行。

### 7.6 漏洞思考（按固定 8 项清单逐项审视）

| # | 项 | 分析与措施 |
|---|-----|-----------|
| 1 | 注入 | 全部 JPQL 参数化（`:param`），序列表发号用原生 SQL 但参数绑定 `:seqType`，无拼接 |
| 2 | 认证越权 | 项目无认证体系（模板现状）；出库接口与既有接口一致，未扩大暴露面，记录为已知边界 |
| 3 | 敏感信息 | 响应仅返回订单/明细/库存字段，无凭证类数据 |
| 4 | 输入边界 | `quantity` @Min(1) @Max(999999)、customerName 长度 ≤200、items @NotEmpty；查询 pageSize 上限 100（沿用） |
| 5 | 幂等重复提交 | requestId 唯一索引 + 命中返回原单；前端失败重试复用同一键、成功换新；并发下唯一索引兜底 |
| 6 | 并发竞态 | **防超卖双层防线**（Redis Lua 预扣 + DB 原子条件更新）；**单号并发安全**（序列表原子发号，并发测试曾暴露 Duplicate entry 并修复）；明细合并避免"扣一半回滚"；补偿只对已预扣行执行 |
| 7 | XSS | 前端渲染为 el-table 文本插值（自动转义），出库页同入库页 |
| 8 | 可用性兜底 | Redis fail-open 降级纯 DB（正确性不依赖 Redis）；预扣失败快速 400 不占 DB 事务；出库单无列表接口不涉及分页上限；超时 2s（application.yml） |

### 7.7 代码 Review（事务 / SQL 性能 / 空指针 / 并发）

**事务：**
- `createOutboundOrder` 整体 `@Transactional(rollbackFor=Exception.class)`：预扣、建单、明细、扣减任一步失败整体回滚；Redis 操作不参与 DB 事务，失败由 catch 补偿；
- 补偿逻辑覆盖**预扣循环 + DB 扣减段**（初版只包住预扣循环，被并发测试暴露"DB 兜底拦截未补偿 Redis"，已修复）。

**SQL 性能：**
- 扣减是单条索引定位 UPDATE（`(product_id, location_code)` 唯一键），无锁竞争放大；发号是单行 UPDATE（行锁微秒级）；
- 库存查询沿用两步分页 + 深分页上限，本次仅加 `productId` 等值过滤（走索引）；
- 入库同步 Redis 是旁路操作（失败不影响 DB）。

**空指针：**
- `preDeduct` 返回 null（脚本异常）按降级放行；`initFromDb` DB 无行按 0；`toResponse` 商品名 orElse("");
- Redis 异常全部 catch 降级，不向业务层抛原始连接异常。

**并发（本轮重点，测试暴露并修复 4 个真实问题）：**
| 问题 | 现象（测试暴露） | 修复 |
|------|-----------------|------|
| Redis 镜像初始化重复累加 | 入库 100 后镜像 200（`setIfAbsent(0)` 后又 set DB 值再 INCRBY） | `increase` 改为：key 不存在 → 直接以 DB 当前值初始化；存在 → INCRBY |
| DB 兜底拦截未补偿 Redis | 并发测试 Redis=80 vs DB=82（17 个线程 DB 失败但预扣未退回） | try-catch 扩展覆盖 DB 扣减段，失败统一补偿已预扣行 |
| 并发单号重复 | `Duplicate entry 'OUT-...-005'`（synchronized 内"计算"但 save 未提交，其他线程读不到） | 序列表 LAST_INSERT_ID 原子发号（入库/出库统一） |
| 测试清理顺序违反外键 | tearDown 删主表被 `outbound_order_items.order_id` 外键拦截，残留脏数据 | 先删明细再删主表（事务内执行）；`ProductService.delete` 的级联顺序本就正确（先明细后商品） |

**验收结论：** 出库单 + 双层防超卖闭环完成：JUnit 39 用例（含真实并发集成）+ smoke 28 用例全绿；并发安全由"Redis 预扣闸门 + DB 原子兜底 + 失败补偿 + 序列表发号"四重机制保证；方案与理由、演进方向（定时对账、Redis 预扣的 Lua 门控扩展）已记录。

---

## 8. 选做 B / C 完成说明

**选做 B：单元测试** ✅（两块都已覆盖，共 12 例）
- **后端（入库单创建的 Service 层）**：任务 1 时已实现 `InventoryServiceTest`（5 例：创建+库存累加 / 同商品库位重复明细累加 / 商品 404 / 库位 404 / 幂等重放不重复累加），满足"至少 2 个用例"；
- **前端（库存列表的筛选逻辑）**：本次补齐——引入 vitest，把 `InventoryView` 的筛选逻辑抽取为纯函数 `src/utils/filters.ts`（`buildInventoryQuery` 参数组装 + `debounce` 防抖），`filters.test.ts` 7 例（参数空值剔除/keyword trim/组合透传；防抖连续触发只执行最后一次/超时后再次执行/cancel 取消），fake timers 验证。组件改为复用抽取逻辑，行为不变，vue-tsc 通过。

**选做 C：前端性能优化** ✅（任务 2 实施时已覆盖两种方案，500+ 行不卡顿）
- **分页改为后端分页**：库存列表从"全量拉取 + 前端切片"改为后端分页（两步查询 `SELECT id` + `IN` 取详情、OFFSET 深度上限 10000 兜底），500+ 行时只渲染当前页，避免一次性渲染全部行；
- **防抖搜索**：搜索框 300ms 防抖自动搜索（stop 输入才发请求），减少无效请求；
- 虚拟滚动未引入：后端分页后单页数据量已受控（pageSize ≤ 100），虚拟滚动针对"一次性渲染大量行"的场景，不再必要；若未来改为前端全量渲染再评估。

---

## 6. 任务进度总览

| 任务 | 状态 |
|------|:---:|
| 必做 1：入库单创建 | ✅ 完成（含测试与 review） |
| 必做 2：库存查询 | ✅ 完成（含测试与 review） |
| 必做 3：修复 2 个预埋 Bug | ✅ 完成（含测试与 review） |
| 选做 A：出库单 + 库存扣减防超卖 | ✅ 完成（Redis Lua 预扣 + DB 原子兜底双层防线，39 测试 + 28 冒烟全绿，含真实并发集成测试） |
| 选做 B：单元测试 | ✅ 完成（后端 InventoryServiceTest 5 例 + 前端 vitest filters 7 例） |
| 选做 C：前端性能优化 | ✅ 完成（后端分页 + 防抖搜索，任务 2 实施；虚拟滚动不再必要） |
