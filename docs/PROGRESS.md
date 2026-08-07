# AI 日记镜子系统 - 开发进度

> 本文档用于跨对话快速跟踪项目进度，避免重复理解项目结构。
> 最后更新：2026-08-07（记录模块 CRUD 完成 + UUID 类型改造）

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
| P0 | 用户设置 (Settings) | ⬜ 未开始 | AI 模型配置等 |
| P1 | 每日摘要 (Daily Summary) | ⬜ 未开始 | 依赖 Records |
| P1 | 写作灵感 (Inspiration) | ⬜ 未开始 | 依赖 AI 服务 |
| P2 | 活动统计 (Activity) | ⬜ 未开始 | 依赖 Records |
| - | AI 服务 (Python gRPC) | ⬜ 未开始 | 独立服务，处理/分类/embedding/对话 |
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
│   └── utils/JwtUtils.java
├── config/
│   ├── SecurityConfig.java              # 路由权限配置
│   ├── Knife4jConfig.java               # API 文档
│   └── WebMvcConfig.java                # CORS
├── controller/
│   ├── AuthController.java              # 认证端点（4个）
│   └── RecordController.java            # 记录端点（6个）
├── mapper/
│   ├── UserMapper.java
│   ├── RecordMapper.java
│   └── TagMapper.java
├── pojo/
│   ├── R.java                           # 统一响应 {code, message, data, timestamp}
│   ├── DO/
│   │   ├── User.java                    # UUID id, username, passwordHash, createdAt
│   │   ├── Record.java                  # 记录实体（UUID userId）
│   │   └── Tag.java                     # 标签实体
│   ├── DTO/
│   │   ├── UserLoginDTO.java
│   │   ├── UserRegisterDTO.java
│   │   ├── RecordDTO.java               # 记录创建/更新 DTO
│   │   └── RecordQueryDTO.java          # 记录查询 DTO（日期范围）
│   └── VO/
│       ├── LoginVO.java
│       ├── UserVO.java
│       └── RecordVO.java                # 记录视图对象
├── service/
│   ├── AuthService.java
│   ├── RecordService.java               # 记录服务接口
│   └── impl/
│       ├── AuthServiceImpl.java
│       └── RecordServiceImpl.java       # 记录服务实现
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

### 待创建的表（设计文档中已定义）

- `chunks` — pgvector 向量存储（embedding + metadata）
- `daily_summaries` — 每日摘要
- `mirror_profiles` — AI 生成的用户画像（JSONB）
- `conversation_history` — 对话历史
- `chat_sessions` — 对话会话
- `user_settings` — 用户设置（认证 + AI 模型配置）

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
| DELETE | `/api/records/{id}` | JWT | 软删除记录（仅REVIEWING状态允许） |

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

1. 实现 Python gRPC AI 服务（内容分类、标签提取、embedding）
2. 完善 RecordPipeline 管道处理（ClassifyProcessor、EmbedProcessor）
3. 实现向量存储（chunks 表 + pgvector）
4. 实现镜子/画像模块（用户画像生成）

**后续目标：**

5. 对话模块（基于 RAG 的 AI 对话）
6. 每日摘要生成
7. 用户设置模块
8. 前端开发（Vue 3）

---

## 八、开发约定

- **实体命名**：DO 类用数据库表名对应，DTO 用于输入，VO 用于输出
- **响应格式**：统一使用 `R<T>` 包装
- **主键**：
  - users 表：UUID，由数据库 `gen_random_uuid()` 生成
  - records/tags 表：BIGSERIAL 自增
- **外键关联**：records.user_id 使用 UUID 类型，通过 UuidTypeHandler 映射
- **时间**：使用 `TIMESTAMPTZ`，应用层用 `OffsetDateTime`
- **密码**：BCrypt 哈希存储
- **JWT**：24 小时过期，Bearer Token 放 Authorization 头
- **软删除**：使用 `deleted_at` 字段，查询时自动过滤
- **API 文档**：Knife4j，地址 `/api/doc.html`，所有接口有详细注解

---

## 九、今日更新记录（2026-08-07）

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
