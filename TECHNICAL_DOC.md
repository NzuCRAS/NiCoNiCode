# NiCoNiCode 技术文档

## 1. 项目概述

NiCoNiCode 是一个 **AI 驱动的技术问答、知识库与热点技术追踪平台**。它利用大语言模型（DeepSeek V3）为开发者提供智能对话、技术更新追踪和知识管理能力。

### 技术栈

| 层级 | 技术 |
|------|------|
| **后端框架** | Spring Boot 3.3.6 |
| **AI 框架** | LangChain4j 1.0.0-beta2 |
| **ORM** | MyBatis-Plus 3.5.9 |
| **数据库** | MySQL 8.0 |
| **向量数据库** | Qdrant |
| **前端框架** | Vue 3 + TypeScript + Pinia |
| **样式** | Tailwind CSS |
| **构建工具** | Maven (后端) + Vite (前端) |
| **AI 模型** | DeepSeek V3 (SiliconFlow API) |
| **Embedding** | BAAI/bge-m3 (1024维) |
| **认证** | JWT (JJWT 0.12.6) |

---

## 2. 已完成工作 — 6 大模块概述

### 2.1 用户认证模块 (`auth/`)
- 邮箱验证码注册 + 密码登录
- JWT 无状态认证（7天过期）
- 角色系统：USER / ADMIN
- Spring Security 统一权限控制

### 2.2 AI 对话模块 (`agent/chat/`)
- 基于 DeepSeek V3 的智能对话
- 支持流式回复（Server-Sent Events）
- RAG 知识增强：查询时从 Qdrant 检索相关知识片段
- 会话管理：创建/删除/历史消息

### 2.3 技术追踪模块 (`agent/tracker/`)
- 追踪 8+ 主流技术（Spring Boot, Vue.js, React, Node.js, Docker, Kubernetes, Rust, LangChain4j）
- GitHub Release 监控 + 版本比对
- AI 自动生成技术报道（Markdown 格式, 800-1500字）
- 定时调度（TrackingScheduler）自动检查更新
- 热点话题统计

### 2.4 知识库模块 (`knowledge/`)
- 文档 CRUD + 全文搜索
- 语义搜索（Qdrant 向量检索）
- 自动向量化：文档创建后异步分片 → Embedding → 存入 Qdrant
- 多来源：对话精华、追踪报道、手动创建

### 2.5 分类与勘误系统 (`category/`, `errata/`)
- 分类管理：框架、语言、工具、平台、运行时、数据库、AI
- 报道和知识文档支持分类过滤
- 用户勘误提交 + 管理员审核机制

### 2.6 管理员后台 (`admin/`)
- 报道管理：编辑/删除/状态切换
- 知识库管理：编辑/删除
- 分类管理：增删
- 勘误审核：采纳/驳回
- 用户管理：角色/状态切换

---

## 3. AI Agent 架构

### 3.1 模型调用链

```
用户输入
  ↓
ChatService.processMessage()
  ├── RagService → EmbeddingService → Qdrant (语义检索知识片段)
  ├── MemoryService (会话上下文管理)
  └── ChatLanguageModel.chat() → DeepSeek V3 API
        ↓
    AI 回复（支持流式 SSE）
```

### 3.2 LangChain4j 集成

- **ChatLanguageModel**: 同步对话模型 (OpenAiChatModel)
- **StreamingChatLanguageModel**: 流式对话模型
- **EmbeddingModel**: 文本向量化 (SiliconFlow BAAI/bge-m3)
- **MCP 支持**: McpToolProvider + @Tool 注解工具

### 3.3 RAG 流程

```
用户问题 → EmbeddingService.embed(query)
         → VectorService.search(vector, topK=5)
         → 拼接相关知识片段到 System Prompt
         → AI 模型生成增强回答
```

---

## 4. 技术追踪完整 Pipeline

```
┌─────────────────────────────────────────────────────────┐
│  TrackingScheduler (定时任务, 每6小时)                    │
│    ↓                                                     │
│  TrackerService.checkTechUpdate(techId)                  │
│    ↓                                                     │
│  GitHubMonitorService.checkLatestRelease(repo, version)  │
│    ├── 无新版本 → 跳过                                    │
│    └── 发现新版本 ↓                                       │
│  GitHubMonitorService.getFullReleaseInfo(repo, tag)      │
│    ↓ 返回 GitHubReleaseInfo (tag, body, url, date)       │
│  TrackerService.generateReport(tech, version, info)      │
│    ├── 构建增强 Prompt (发布日期+来源+结构要求)            │
│    ├── ChatLanguageModel.chat(prompt)                    │
│    ├── cleanMarkdownContent() 清洗输出                    │
│    ├── 创建 TechReport (含 sourceUrls)                   │
│    └── KnowledgeService.createDoc() 同步到知识库          │
│         └── asyncEmbedAndStore() 异步向量化               │
└─────────────────────────────────────────────────────────┘
```

### 报道生成 Prompt 策略
- 要求 800-1500 字，分段结构
- 包含：版本亮点 → 更新内容详解 → 对开发者的影响 → 升级建议 → 来源链接
- 明确禁止外层 ` ```markdown ``` ` 包裹
- 后端 `cleanMarkdownContent()` 双重保障

---

## 5. 下一步 Agent 优化方向

### 5.1 多工具链式调用
当前 AI 只有单次调用能力。下一步可以引入 LangChain4j 的 Agent 模式，让 AI 自主决定调用顺序：
- 查询 GitHub API → 分析 Release → 搜索知识库 → 生成报道

### 5.2 多源信息聚合
目前仅监控 GitHub Release。计划扩展：
- RSS Feed 解析（已预留 RssService）
- 官方博客抓取
- StackOverflow/HackerNews 热度监控
- NPM/Maven Central 版本更新

### 5.3 个性化推送
- 用户订阅感兴趣的技术标签
- 新报道匹配用户偏好后推送通知
- 邮件摘要（每日/每周）

### 5.4 知识图谱
- 技术间依赖关系建模（Spring Boot → Java, Vue.js → Node.js）
- 版本兼容性矩阵
- 升级路径推荐

### 5.5 Agent 自主学习
- 根据用户反馈（勘误）自动修正知识库
- 报道质量评分 + 自动改进 Prompt
- 热点话题自动升级为追踪目标

---

## 6. 项目结构

```
niconicode/
├── src/main/java/com/niconicode/
│   ├── NiconicodeApplication.java
│   ├── admin/          # 管理员后台
│   ├── agent/
│   │   ├── chat/       # AI 对话
│   │   └── tracker/    # 技术追踪
│   ├── auth/           # 认证授权
│   ├── category/       # 分类系统
│   ├── common/         # 公共工具
│   ├── conversation/   # 会话存储
│   ├── errata/         # 勘误系统
│   └── knowledge/      # 知识库
├── src/main/resources/
│   ├── application.yml
│   └── db/schema.sql
├── frontend/
│   └── src/
│       ├── views/      # 页面组件
│       ├── stores/     # Pinia 状态管理
│       ├── services/   # API 客户端
│       ├── router/     # Vue Router
│       └── utils/      # 工具函数
└── TECHNICAL_DOC.md
```

---

## 7. API 端点汇总

### 公开端点
```
POST /api/auth/login, /register, /send-code
GET  /api/auth/me
GET  /api/tracker/reports, /reports/{id}, /reports/latest
GET  /api/knowledge/docs, /docs/{id}, /search
GET  /api/categories
GET  /api/errata/doc
```

### 认证端点
```
POST /api/chat/send, /send/stream
GET  /api/chat/sessions, /sessions/{id}/messages
POST /api/chat/sessions
DELETE /api/chat/sessions/{id}
POST /api/errata
```

### 管理员端点
```
GET/PUT/DELETE /api/admin/reports/{id}
GET/PUT/DELETE /api/admin/knowledge/{id}
GET/PUT        /api/admin/errata/{id}
GET/PUT        /api/admin/users/{id}
POST/PUT/DELETE /api/categories/{id}
POST/DELETE    /api/tracker/techs/{id}
POST           /api/tracker/check-now/{id}
```
