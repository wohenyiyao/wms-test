# NOTES.md — 开发记录

> 使用AI按统一的开发流程记录每个任务的完成情况：
> **理解需求 → 设计（与用户确认方案、争取用户建议）→ 编码实现 → 对照需求文档验收（含功能完善性检查）→ 冒烟测试 → 漏洞思考 → 代码 Review**
> （工具链 / 环境配置类问题不在本文赘述）

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

---

## 2. AI 协作说明

- **工具**：DeepSeek Harness 编码智能体（deepseek-v4-flash 模型），以对话方式辅助编码。
- **协作方式**：理解需求后，**先与用户确认设计方案、听取用户建议**，确认后再让 AI 按模板风格生成初版实现 → 人工审查事务边界、参数校验与边界条件 → 用测试验证 → 发现问题反馈 AI 协助排查。
- **具体例子（AI 生成代码的问题与修复）**：实现入库单时，为支持 Service 跨包引用，把 DTO 中的明细类 `InboundItemRequest` 由包级私有改为 `public`，结果编译报「InboundItemRequest 不是公共的」，且**所有 Lombok 生成的方法（builder/getter/log/构造器）一并消失**。定位根因：该类是 `InboundOrderCreateRequest.java` 中的**第二个 public 顶层类**——Java 规定一个文件只能有一个 public 顶层类且须与文件名一致（包级私有则合法），javac 因该结构错误中止注解处理，导致 Lombok 全局失效。修复：拆分为独立文件 `InboundItemRequest.java`。教训：改动类可见性必须同步考虑 Java 文件结构约束；排查「注解处理未生效」应先确认是否存在结构级错误。
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

## 4. 任务进度总览

| 任务 | 状态 |
|------|:---:|
| 必做 1：入库单创建 | ✅ 完成（含测试与 review） |
| 必做 2：库存查询 | ⬜ 待做 |
| 必做 3：修复 2 个预埋 Bug | ⬜ 待做 |
| 选做 A/B/C | ⬜ 待做（完成后按同一流程记录） |
