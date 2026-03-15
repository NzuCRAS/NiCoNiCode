# NiCoNiCode

AI 驱动的技术追踪与智能问答平台，实时追踪开源项目动态，为开发者生成深度技术报道。

## 功能特性

### AI 智能对话
- 基于 LangChain4j 构建的技术问答 Agent
- 意图识别 + 问题重写 + 工具调用 + RAG 双通道检索
- 流式输出 (SSE)，会话记忆自动摘要压缩
- 模型熔断降级 (DeepSeek-V3 主模型 + V2.5 备用)

### 技术追踪 (狗仔系统)
- Multi-Agent 流水线：SearchAgent → WriterAgent → ReviewerAgent
- 多渠道检测：GitHub Release / Tag / Commit
- 技术指数评分 (0-1000)：基于 Star、Fork、社区活跃度等多维指标
- 综合指数排序：技术指数 * 0.6 + 时间指数 * 0.4
- 自动同步报道至知识库

### 知识库
- 5 步 ETL 管道：解析 → 清洗 → 分块 → 向量化 → 存储
- 向量语义搜索 (Qdrant + BAAI/bge-m3)
- 全文关键词搜索 (MySQL FULLTEXT)

### 管理后台
- 报道 / 知识文档 / 追踪技术 / 分类 / 勘误 / 用户管理
- 追踪频率动态调节 (最短 30 分钟)
- 一键触发全量检测

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.6, Java 17 |
| AI 框架 | LangChain4j 1.0.0-beta2 |
| AI 模型 | DeepSeek-V3 (SiliconFlow API) |
| 向量嵌入 | BAAI/bge-m3 (1024维) |
| 向量数据库 | Qdrant |
| 关系数据库 | MySQL 8.0+ (MyBatis-Plus 3.5.9) |
| 认证 | JWT (JJWT 0.12.6) + Spring Security |
| 前端 | Vue 3 + TypeScript + Pinia + Tailwind CSS |
| 构建工具 | Vite 6 |

## 项目结构

```
niconicode/
├── src/main/java/com/niconicode/
│   ├── agent/
│   │   ├── chat/           # 聊天 Agent 系统
│   │   │   ├── controller/ # ChatController
│   │   │   ├── service/    # ChatService, IntentClassifier, QueryRewriter,
│   │   │   │               # MemoryService, RagService, ToolExecutionService,
│   │   │   │               # TraceLogger
│   │   │   ├── mcp/        # LangChain4jConfig (模型配置 + 熔断器)
│   │   │   └── tool/       # KnowledgeMcpTools, TechTrackerTools
│   │   └── tracker/        # 狗仔 Agent 系统
│   │       ├── agent/      # SearchAgent, WriterAgent, ReviewerAgent
│   │       ├── controller/ # TrackerController
│   │       ├── service/    # TrackerService, GitHubMonitorService,
│   │       │               # TechIndexCalculator
│   │       ├── scheduler/  # TrackingScheduler
│   │       ├── entity/     # TechReport, TrackedTech, HotTopic
│   │       ├── mapper/     # MyBatis Mapper
│   │       └── dto/        # GitHubReleaseInfo, GitHubCommitInfo
│   ├── knowledge/          # 知识库系统
│   │   ├── service/        # KnowledgeService, VectorService,
│   │   │                   # EmbeddingService, DocumentETLService
│   │   ├── entity/         # KnowledgeDoc
│   │   └── mapper/         # KnowledgeDocMapper
│   ├── auth/               # 认证系统 (JWT + Security)
│   ├── admin/              # 管理后台
│   ├── conversation/       # 会话实体与 Mapper
│   ├── errata/             # 勘误系统
│   └── common/             # 通用工具 (R, BusinessException)
├── frontend/               # Vue 3 前端
│   └── src/
│       ├── views/          # 9 个页面组件
│       ├── stores/         # Pinia 状态管理 (user, chat, knowledge)
│       ├── services/       # Axios API 封装
│       └── utils/          # 文本处理工具
└── src/main/resources/
    ├── application.yml     # 应用配置
    ├── db/schema.sql       # 数据库建表脚本
    └── logback-spring.xml  # 日志配置
```

## 快速开始

### 环境要求
- Java 17+
- Node.js 18+
- MySQL 8.0+
- Qdrant (向量数据库)

### 后端启动

```bash
# 1. 导入数据库
mysql -u root -p < src/main/resources/db/schema.sql

# 2. 修改配置 (application.yml)
#    - 数据库连接
#    - SiliconFlow API Key
#    - Qdrant 地址
#    - 邮箱 SMTP 配置

# 3. 启动
mvn spring-boot:run
```

### 前端启动

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 `http://localhost:5173`，API 自动代理到 `http://localhost:8080`。

## API 概览

| 模块 | 端点 | 权限 |
|------|------|------|
| 聊天 | `POST /api/chat/send/stream` | 登录用户 |
| 报道 | `GET /api/tracker/reports/by-score` | 公开 |
| 知识库 | `GET /api/knowledge/docs` | 公开 |
| 管理 | `/api/admin/**` | ADMIN |

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
