# AI 日记镜子系统 - 开发进度

> 本文档用于跨对话快速跟踪项目进度，避免重复理解项目结构。
> 最后更新：2026-08-12（日历导航接口 + 文档与代码对齐 + Proto 同步）

---

## 一、项目概览

- **项目名称**：Mu-mirror-B（AI 日记镜子系统）
- **项目性质**：毕业设计 / 论文项目
- **核心理念**：零负担记录 → AI 自动整理 → 生成用户画像（"镜子"）
- **技术栈**：Spring Boot 3.5 + MyBatis-Plus + PostgreSQL + Redis + JWT + Python gRPC AI 服务
- **端口**：9005，上下文路径 `/api`
- **开发时间线**：2026-08 ~ 2027-04（论文答辩）

---

## 二、模块开发状态

| 优先级 | 模块 | 状态 | 说明 |
|--------|------|------|------|
| P0 | 用户认证 (Auth) | ✅ 完成 | 注册、登录、JWT、/auth/me |
| P0 | 记录管理 (Records) | ✅ 完成 | CRUD + 软删除 + 人工审查 + API文档注解 |
| P0 | 镜子/画像 (Mirror) | ⬜ 未开始 | 依赖 Records + AI 服务 |
| P0 | 对话 (Chat) | ⬜ 未开始 | 依赖 Records + AI 服务 |
| P0 | 用户设置 (Settings) | ✅ 完成 | AI 模型配置 CRUD + AES 加密 + 注册自动创建 |
| P1 | 日历导航 (Calendar) | ✅ 完成 | 按月查询每天记录数，用于日历标记 |
| P1 | 每日摘要 (Daily Summary) | ⬜ 未开始 | 依赖 Records |
| P1 | 写作灵感 (Inspiration) | ⬜ 未开始 | 依赖 AI 服务 |
| P2 | 活动统计 (Activity) | ⬜ 未开始 | 依赖 Records |
| - | AI 服务 (Python gRPC) | 🔧 进行中 | 分类层已接入，配置传递已通，Proto 已同步 |
| - | 前端 (Vue 3) | ⬜ 未开始 | |

---

## 三、已完成的代码结构

```
src/main/java/org/xianshen/mumirrorb/
├── MuMirrorBApplication.java
├── common/
│   ├── enums/
│   │   ├── ResultCode.java              # 统一错误码
│   │   ├── ContentType.java             # 内容类型枚举
│   │   ├── MoodType.java                # 情绪类型枚举
│   │   └── RecordStatus.java            # 记录状态枚举（processing/reviewing/done/failed）
│   ├── exception/
│   │   ├── BusinessException.java
│   │   ├── AuthenticationException.java
│   │   └── GlobalExceptionHandler.java
│   ├── handler/
│   │   ├── JsonbTypeHandler.java        # JSONB 类型处理器
│   │   └── UuidTypeHandler.java         # UUID 类型处理器
│   ├── security/
│   │   ├── JwtAuthenticationFilter.java # JWT 过滤器
│   │   └── UserDetailsServiceImpl.java
│   └── utils/
│       ├── JwtUtils.java
│       └── CryptoUtils.java             # AES-GCM 加密工具
├── config/
│   ├── SecurityConfig.java              # 路由权限配置
│   ├── Knife4jConfig.java               # API 文档
│   ├── WebMvcConfig.java                # CORS
│   └── GrpcClientConfig.java            # gRPC channel 管理
├── controller/
│   ├── AuthController.java              # 认证端点（4个）
│   ├── RecordController.java            # 记录端点（6个）
│   └── SettingsController.java          # 配置端点（4个）
├── mapper/
│   ├── UserMapper.java
│   ├── RecordMapper.java
│   ├── TagMapper.java
│   └── SettingsMapper.java
├── pojo/
│   ├── R.java                           # 统一响应 {code, message, data, timestamp}
│   ├── DO/
│   │   ├── User.java                    # UUID id, username, passwordHash, createdAt
│   │   ├── Record.java                  # 记录实体（UUID userId）
│   │   ├── Tag.java                     # 标签实体
│   │   └── UserSettings.java            # 用户配置实体（LLM/Embedding 配置）
│   ├── DTO/
│   │   ├── UserLoginDTO.java
│   │   ├── UserRegisterDTO.java
│   │   ├── RecordDTO.java               # 记录创建/更新 DTO
│   │   ├── RecordQueryDTO.java          # 记录查询 DTO（日期范围）
│   │   └── SettingsDTO.java             # 配置更新 DTO
│   └── VO/
│       ├── LoginVO.java
│       ├── UserVO.java
│       ├── RecordVO.java                # 记录视图对象
│       └── SettingsVO.java              # 配置视图对象（API Key 脱敏）
├── service/
│   ├── AuthService.java
│   ├── RecordService.java               # 记录服务接口
│   ├── SettingsService.java             # 配置服务接口
│   └── impl/
│       ├── AuthServiceImpl.java
│       ├── RecordServiceImpl.java       # 记录服务实现
│       └── SettingsServiceImpl.java     # 配置服务实现
├── grpc/
│   └── AiGrpcClient.java               # AI gRPC 客户端封装
└── pipeline/                            # 数据管道框架
    ├── RecordProcessor.java
    ├── RecordPipeline.java
    └── event/
        ├── RecordCreatedEvent.java
        └── RecordEventListener.java
```

---

## 四、数据库状态

### 已创建的表

```sql
-- users 表
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_users_username ON users(username);

-- records 表（已实现）
CREATE TABLE records (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    content TEXT NOT NULL,
    title VARCHAR(200),
    summary VARCHAR(500),
    content_type VARCHAR(20),
    mood JSONB DEFAULT '[]',
    status VARCHAR(20) DEFAULT 'processing',
    user_reviewed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ  -- 软删除字段
);
CREATE INDEX idx_records_user_id ON records(user_id);
CREATE INDEX idx_records_created_at ON records(created_at);
CREATE INDEX idx_records_deleted_at ON records(deleted_at);

-- tags 表（已实现）
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    record_id BIGINT NOT NULL REFERENCES records(id),
    keyword VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_tags_record_id ON tags(record_id);
```

### 已创建的表（新增）

```sql
-- user_settings 表（用户配置）
CREATE TABLE IF NOT EXISTS user_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE NOT NULL REFERENCES users(id),
    ai_provider VARCHAR(50),         -- AI 提供商：openai/zhipu/qwen
    ai_api_key TEXT,                 -- API Key（加密存储）
    ai_base_url TEXT,                -- API 地址
    ai_model VARCHAR(100),           -- 模型名称
    embedding_source VARCHAR(20) DEFAULT 'local', -- local / api
    embedding_api_key TEXT,          -- Embedding API Key（加密）
    embedding_model VARCHAR(100),    -- Embedding 模型名
    review_mode VARCHAR(20) DEFAULT 'manual', -- manual / auto
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_settings_user_id ON user_settings(user_id);
```

### 待创建的表（设计文档中已定义）

- `chunks` — pgvector 向量存储（embedding + metadata）
- `daily_summaries` — 每日摘要
- `mirror_profiles` — AI 生成的用户画像（JSONB）
- `conversation_history` — 对话历史
- `chat_sessions` — 对话会话

---

## 五、已有 API 端点

### 认证模块

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/auth/status` | 公开 | 健康检查 |
| POST | `/api/auth/register` | 公开 | 用户注册 |
| POST | `/api/auth/login` | 公开 | 用户登录，返回 JWT |
| GET | `/api/auth/me` | JWT | 获取当前用户信息 |

### 记录模块

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/records` | JWT | 创建记录（提交内容，触发AI处理） |
| GET | `/api/records` | JWT | 查询记录列表（支持日期范围筛选，默认查今天） |
| GET | `/api/records/{id}` | JWT | 获取单条记录详情 |
| PUT | `/api/records/{id}` | JWT | 更新记录（仅REVIEWING状态允许） |
| PUT | `/api/records/{id}/confirm` | JWT | 确认审查完成（REVIEWING → DONE） |
| DELETE | `/api/records/{id}` | JWT | 软删除记录（仅REVIEWING/FAILED状态允许） |
| GET | `/api/records/calendar` | JWT | 日历标记（返回指定月份每天有效记录数） |

### 配置模块

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/settings` | JWT | 获取当前用户配置（API Key 脱敏） |
| PUT | `/api/settings` | JWT | 更新配置（部分更新，API Key 加密存储） |
| POST | `/api/settings/test-ai` | JWT | 测试 AI 连接 |
| POST | `/api/settings/test-db` | JWT | 测试数据库连接 |

### 记录状态流转

```
PROCESSING → REVIEWING → DONE
    ↓
  FAILED
```

- **PROCESSING**：AI 正在处理（前端显示转圈动画）
- **REVIEWING**：人工审查中（用户可修改标签）
- **DONE**：已完成（用户已确认）
- **FAILED**：处理失败

---

## 六、关键设计文档索引

| 文件 | 内容 |
|------|------|
| `docs/2026-07-23-ai-diary-mirror-design.md` | 主设计文档 v1.1（功能、数据库、API、提示词模板） |
| `docs/2026-07-23-mirror-implementation.md` | 实现文档 v0.3（技术选型、项目结构、核心代码模式） |
| `docs/2026-08-04-ai-service-design.md` | AI 服务设计 v0.2（Proto 定义、Python 模块、部署方案） |

---

## 七、下一步计划

**当前目标：对接 AI 服务**

1. ~~搭建 gRPC 基础设施~~ ✅ 已完成
2. ~~分类层接入 gRPC~~ ✅ 已完成
3. Python 端实现真实的 Classify（接 LLM，返回真实标签）
4. 实现 EmbedProcessor 向量化（用户审核后执行 Embedding → 存 chunks 表）
5. 实现向量存储（chunks 表 + pgvector）
6. 实现镜子/画像模块（用户画像生成）

**后续目标：**

5. 对话模块（基于 RAG 的 AI 对话）
6. 每日摘要生成
7. 前端开发（Vue 3）

---

## 八、开发约定

- **实体命名**：DO 类用数据库表名对应，DTO 用于输入，VO 用于输出
- **响应格式**：统一使用 `R<T>` 包装
- **主键**：
  - users 表：UUID，由数据库 `gen_random_uuid()` 生成
  - records/tags 表：BIGSERIAL 自增
- **UUID 映射**：全局注册 `type-handlers-package`，所有 UUID 字段自动使用 UuidTypeHandler
- **时间**：使用 `TIMESTAMPTZ`，应用层用 `OffsetDateTime`
- **密码**：BCrypt 哈希存储
- **JWT**：24 小时过期，Bearer Token 放 Authorization 头
- **软删除**：使用 `deleted_at` 字段，查询时自动过滤
- **API 文档**：Knife4j，地址 `/api/doc.html`，所有接口有详细注解

---

## 九、更新记录（2026-08-12）

### 日历导航接口

1. **新增 `GET /api/records/calendar` 接口**
   - 参数：`month`（格式 `2026-08`）
   - 返回：`Map<String, Integer>`（日期 → 有效记录数）
   - 只统计未删除且非 failed 的记录
   - 时区按 `Asia/Shanghai` 转换
   - 涉及文件：`RecordMapper.java`（SQL）、`RecordService.java`（接口）、`RecordServiceImpl.java`（实现）、`RecordController.java`（端点）

2. **软删除权限调整**
   - 之前：只有 REVIEWING 状态才能删除
   - 现在：REVIEWING 和 FAILED 状态都允许删除

3. **gRPC Classify 超时调整**
   - 从 30 秒改为 180 秒（3 分钟），适应 LLM 处理耗时

### 文档与代码对齐

1. **三份设计文档全面修正**
   - 修正数据库表结构（users/records/tags/user_settings）与实际 schema 一致
   - 认证系统从"简单密码保护"改为 JWT 注册/登录
   - API 端点与实际 Controller 一致
   - 记录状态值统一：`pending_review` → `reviewing`，移除 `rejected`
   - 模块结构更新：包名 `com/mirror` → `org/xianshen/mumirrorb`
   - Proto 定义同步：`LlmConfig` 新增 `AiProtocol protocol` 字段
   - 移除未实现的 RPC：`Split`、`EmbedBatch`
   - LLM 工厂函数更新：按 `protocol` 路由（openai/anthropic）
   - AiGrpcClient 代码示例更新（UUID userId, SettingsMapper, CryptoUtils）

2. **发现并记录 Proto 同步问题**
   - Python 端 `common_pb2.py` 过时，字段定义与 Java 端不一致
   - 导致 gRPC 传输时字段错位（api_key/base_url/model 全部串位）
   - 修复方法：在 Python 项目执行 `python generate_proto.py` 重新编译
   - 同时 `record_processor.py` 中 `llm_config.ai_protocol` 需改为 `llm_config.protocol`

---

## 十、更新记录（2026-08-11）

### 分类层接入 gRPC + 配置传递

1. **删除测试代码**
   - 删除 `GrpcTestController.java`（临时测试端点）
   - 清理 `SecurityConfig.java` 中的测试端点公开访问

2. **ClassifyProcessor 接入真实 gRPC**
   - 从桩实现改为调用 `aiGrpcClient.classify(userId, content)`
   - 将 AI 返回的 title/summary/contentType/mood/keywords 写入 record
   - AI 判定无意义内容（skip=true）时抛异常，管道中断

3. **AiGrpcClient 改造**
   - classify/embed 方法新增 userId 参数
   - 从 `user_settings` 表读取用户配置，构建 `LlmConfig`/`EmbeddingConfig` 传给 Python
   - 注入 `SettingsMapper` 读取配置
   - API Key 解密后传给 Python

4. **Proto 更新**
   - `common.proto` 新增 `AiProtocol` 枚举（OPENAI/ANTHROPIC）+ `LlmConfig` + `EmbeddingConfig` 消息
   - `record_processor.proto` 的 `ClassifyRequest` 新增 `llm_config` 字段
   - `embedding.proto` 的 `EmbedRequest` 新增 `embedding_config` 字段
   - `embedding.proto` 补充 `import "common.proto"`

5. **用户配置新增 aiProtocol 字段**
   - `UserSettings.java`、`SettingsDTO.java`、`SettingsVO.java` 新增 `aiProtocol`
   - `SettingsServiceImpl.java` 更新逻辑 + 默认值 `"anthropic"`
   - 数据库：`ALTER TABLE user_settings ADD COLUMN ai_protocol VARCHAR(20) DEFAULT 'anthropic'`

6. **事件机制修复**
   - `RecordCreatedEvent` userId 从 String 改为 UUID
   - `RecordServiceImpl` 启用事件发布（之前被注释）
   - `RecordEventListener` 管道完成后状态改为 REVIEWING（等用户审核）

7. **踩坑记录**
   - **protobuf 消息类是内部类**：`ClassifyResponse` 要用 `RecordProcessorProto.ClassifyResponse`
   - **枚举在 common.proto**：`ContentType`/`MoodType`/`TaskStatus` 在 `CommonProto` 里，不在 `RecordProcessorProto`
   - **Spring Boot 3 + gRPC javax 注解**：需手动加 `javax.annotation-api` 依赖
   - **proto import 缺失**：`embedding.proto` 使用 `EmbeddingConfig` 但没 import `common.proto`
   - **Python proto 需同步重新编译**：Java 端加了新消息后 Python 必须重新生成 pb2 文件，否则字段读串

---

## 十、历史更新记录（2026-08-10）

### gRPC 链路测试

1. **完成 gRPC 基础设施搭建**
   - pom.xml 添加 gRPC 依赖 + protobuf-maven-plugin
   - 新增 `GrpcClientConfig.java`、`AiGrpcClient.java`
   - Java ↔ Python gRPC 链路已通

### Settings 模块（下午）
   - 完整 CRUD：获取配置、更新配置（部分更新）
   - 测试连接：AI 连接测试、数据库连接测试
   - API Key 加密存储：使用 AES-256-GCM 加密，返回时脱敏
   - 注册自动创建：用户注册时自动创建空配置

2. **新增文件**
   - `UserSettings.java` — 配置实体
   - `SettingsDTO.java` — 配置更新 DTO
   - `SettingsVO.java` — 配置视图对象（API Key 脱敏）
   - `SettingsMapper.java` — MyBatis-Plus Mapper
   - `SettingsService.java` — 配置服务接口
   - `SettingsServiceImpl.java` — 配置服务实现
   - `SettingsController.java` — 配置控制器（4 个端点）
   - `CryptoUtils.java` — AES-256-GCM 加密工具类

3. **修改文件**
   - `AuthServiceImpl.java` — 注册时自动创建空配置
   - `schema.sql` — 新增 user_settings 表定义
   - `application.yml` — 新增 `type-handlers-package` 全局注册 TypeHandler

### 技术决策

- **加密方案**：AES-256-GCM，每次加密生成随机 IV，安全性较高
- **脱敏策略**：API Key 只显示前 3 位 + `***`（如 `sk-***`）
- **部分更新**：PUT 接口只更新非 null 的字段，避免覆盖已有配置
- **UUID TypeHandler**：全局注册 `type-handlers-package`，所有 UUID 字段自动使用 UuidTypeHandler，无需逐字段注解

### UUID 映射踩坑记录

PostgreSQL UUID 列与 Java UUID 字段的映射需要 TypeHandler，踩了以下坑：

1. `@TableId` 字段不支持 `@TableField(typeHandler=...)` — 需要全局注册
2. Java String 不能直接与 PostgreSQL UUID 列比较 — 必须用 UUID 类型
3. 最终方案：`application.yml` 配置 `type-handlers-package`，所有 UUID 字段自动映射

### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/settings` | 获取配置（API Key 脱敏） |
| PUT | `/api/settings` | 更新配置（部分更新） |
| POST | `/api/settings/test-ai` | 测试 AI 连接 |
| POST | `/api/settings/test-db` | 测试数据库连接 |

---

## 十、历史更新记录（2026-08-07）

### 完成功能

1. **用户配置模块（Settings）**
   - 完整 CRUD：获取配置、更新配置
   - 测试连接：AI 连接测试、数据库连接测试
   - API Key 加密存储：使用 AES-256-GCM 加密，返回时脱敏
   - 注册自动创建：用户注册时自动创建空配置

2. **新增文件**
   - `UserSettings.java` — 配置实体
   - `SettingsDTO.java` — 配置更新 DTO
   - `SettingsVO.java` — 配置视图对象（API Key 脱敏）
   - `SettingsMapper.java` — MyBatis-Plus Mapper
   - `SettingsService.java` — 配置服务接口
   - `SettingsServiceImpl.java` — 配置服务实现
   - `SettingsController.java` — 配置控制器（4 个端点）
   - `CryptoUtils.java` — AES-GCM 加密工具类

3. **修改文件**
   - `AuthServiceImpl.java` — 注册时自动创建空配置
   - `schema.sql` — 新增 user_settings 表定义

### 技术决策

- **加密方案**：AES-256-GCM，每次加密生成随机 IV，安全性较高
- **脱敏策略**：API Key 只显示前 3 位 + `***`（如 `sk-***`）
- **部分更新**：PUT 接口只更新非 null 的字段，避免覆盖已有配置
- **UUID TypeHandler**：user_settings 表的 UUID 字段需要显式指定 TypeHandler

### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/settings` | 获取配置（API Key 脱敏） |
| PUT | `/api/settings` | 更新配置（部分更新） |
| POST | `/api/settings/test-ai` | 测试 AI 连接 |
| POST | `/api/settings/test-db` | 测试数据库连接 |

---

## 十、历史更新记录（2026-08-07）

### 完成功能

1. **记录模块完整 CRUD**
   - 创建、查询列表、详情、更新、软删除、确认审查完成
   - 支持按日期范围查询（默认查今天）

2. **软删除功能**
   - 新增 `deleted_at` 字段
   - 查询时自动过滤已删除记录
   - 只有 REVIEWING 状态才能删除

3. **人工审查状态**
   - 新增 REVIEWING 状态（AI处理完成，等待用户审核）
   - 只有 REVIEWING 状态才能修改和删除
   - 用户确认后状态变为 DONE

4. **API 文档注解**
   - 所有接口添加 Knife4j/Swagger 注解
   - 详细的中文说明、参数示例、响应状态码

5. **UUID 类型改造**
   - userId 从 String 改为 UUID 类型
   - 新增 UuidTypeHandler 处理 UUID ↔ PostgreSQL UUID 映射
   - 解决 LambdaQueryWrapper 查询 UUID 类型不匹配问题

### 技术决策

- **UUID vs String**：选择使用 UUID 类型，避免每次查询都指定 TypeHandler
- **软删除方案**：使用 `deleted_at` 时间戳字段，不真正删除数据
- **状态设计**：4 种状态（PROCESSING → REVIEWING → DONE / FAILED）
