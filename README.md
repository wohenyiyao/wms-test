# 全栈工程师 — 线上测试

##  测试说明

-  **允许并鼓励使用 AI 工具**（Copilot、ChatGPT、Claude 等）辅助开发
-  **允许查阅任何资料**（文档、搜索引擎、技术博客等）
-  **示例项目中包含Java(springboot)/python(FastAPI)后端与Vue3/react前端，选择你熟悉的技术栈完成需求**


请阅读 [AI 辅助开发指南](./AI_USAGE_GUIDE.md) 了解我们对 AI 使用的期望。

---

##  业务背景

你需要为一个简化版 **仓库管理系统（WMS）** 补充功能。系统目前已有：

- 商品（Product）基础 CRUD
- 仓库（Warehouse）与库位（Location）基础数据
- 前端页面框架（路由、布局、API 客户端）

你需要在此基础上完成入库、出库、库存查询等功能。

---

##  技术栈选择

请从以下组合中选择 **一种后端 + 一种前端** 进行开发：

| 模块   | 选项 A                    | 选项 B                      |
|--------|---------------------------|-----------------------------|
| 后端   | Java 17 + Spring Boot 3   | Python 3.11+ + FastAPI      |
| 前端   | Vue 3 + Element Plus      | React 18 + Ant Design       |

> 两种技术栈的**业务需求完全相同**，选择你更熟悉的一套即可。

---

##  项目结构

```
wms-test/
├── README.md                 # 本文件
├── TASKS.md                  # 任务清单（你需要完成的内容）
├── AI_USAGE_GUIDE.md         # AI 辅助开发指南
├── docs/
│   └── API_SPEC.md           # API 接口规范
├── backend-java/             # Java / Spring Boot 模板
├── backend-python/           # Python / FastAPI 模板
├── frontend-vue/             # Vue 3 模板
└── frontend-react/           # React 18 模板
```

---

##  快速启动

### 后端（Python / FastAPI）

```bash
cd backend-python
uv sync                              # 安装依赖
uv run uvicorn app.main:app --reload # 启动服务 http://localhost:8000
uv run pytest                        # 运行测试
```

API 文档自动生成：http://localhost:8000/docs

### 后端（Java / Spring Boot）

前置依赖：
- **MySQL 5.7**：创建库 `wms-test`（连接配置见 `backend-java/src/main/resources/application.yml`）
- **Redis**（选做 A「出库防超卖」的库存预扣门控；不启动则出库自动降级为纯 DB 扣减，正确性不受影响）：
  ```bash
  redis-server            # 默认端口 6379，无密码
  ```

```bash
cd backend-java
mvn spring-boot:run               # 启动服务 http://localhost:8080
mvn test                          # 运行测试（39 用例，需 MySQL + Redis 就绪）
```

API 文档：http://localhost:8080/swagger-ui.html

### 前端（Vue 3）

```bash
cd frontend-vue
npm install
npm run dev                          # 启动 http://localhost:5173
```

### 前端（React 18）

```bash
cd frontend-react
npm install
npm run dev                          # 启动 http://localhost:5173
```

---

##  提交方式

1. **Fork 本仓库**，在你自己 Fork 的仓库中完成开发(或以zip形式提交)
2. 确保 **保留 Git 提交历史**（我们会关注你的开发过程）
3. 在项目根目录添加 `NOTES.md`，简要说明：
   - 你使用了哪些 AI 工具？如何使用的？
   - 遇到了什么问题？如何解决的？
   - 如果有更多时间，你还会做什么？
4. 将你的 Fork 仓库地址(或源代码zip，请确认包含.git文件夹)发送至 **zhangjiahui@gzyouliu.cn**，抄送 jiangziqi@gzyouliu.cn、dengsuiming@gzyouliu.cn
5. 如果以zip形式提交，请删除.venv/.node_modules等环境文件夹

**截止时间：收到测试题后 48 小时内**

---

##  常见问题

**Q: 可以用 AI 写完所有代码吗？**
A: 可以，但你需要对代码质量和正确性负责。我们会审查代码的逻辑一致性、架构合理性，以及 Git 提交记录反映的开发过程。

**Q: 不会某个技术栈怎么办？**
A: 这正是 AI 工具发挥价值的地方。你可以选择一个相对熟悉的，用 AI 弥补不熟悉的部分。我们也会关注你如何验证 AI 生成代码的正确性。

**Q: 时间不够完成所有选做任务？**
A: 没关系。必做任务的完成质量比选做数量更重要。优先保证代码质量和测试覆盖。

**Q: 模型需要付费？免费模型代码生成质量不佳？**
A：不必为了测试专门购买模型，我们会更关注你的git提交记录和你在NOTE.md中的说明，展现思路比代码质量更重要。
