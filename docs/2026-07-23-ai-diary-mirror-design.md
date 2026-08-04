# AI 日记"镜子"系统 — 设计文档

> 版本：v1.1（审查修正）
> 日期：2026-07-23（初版） / 2026-08-04（架构更新）
> 状态：设计中，持续完善

**关联文档：**
- [落地实现文档](2026-07-23-mirror-implementation.md) — 技术栈、项目结构、核心代码思路
- [AI 服务层设计文档](2026-08-04-ai-service-design.md) — Python gRPC 服务详细设计

---

## 一、项目概述

### 1.1 项目定位

一个 AI 驱动的个人记录与自我认知平台。用户可以随手记录日常内容，AI 自动整理、分析，最终生成一份"用户画像"（称为"镜子"），帮助用户更好地认识自己。

### 1.2 核心理念

- **记录零负担**：用户想记就记，不强制输入格式
- **AI 做整理，用户做决策**：AI 辅助分类、标签、总结，但用户可以纠正
- **隐私优先**：用户自行配置 AI 模型和数据库，数据不出自己的服务器
- **完整 AI 应用模式**：展示 RAG、向量检索、定时任务、多轮对话等完整链路

### 1.3 技术栈

| 组件 | 技术选型 | 说明 |
|------|----------|------|
| 前端 | Vue 3 + TypeScript | 用户界面 |
| 后端 | Spring Boot | 核心业务服务（CRUD、认证、定时任务、数据库操作） |
| AI 服务 | Python + gRPC | 独立的 AI 推理服务（分类、Embedding、对话、画像生成） |
| 数据库 | PostgreSQL + pgvector | 关系数据 + 向量存储（用户可配置） |
| LLM | 用户自配模型 API | 支持 OpenAI / 通义千问 / 智谱等 |
| Embedding | BGE-m3（本地）/ API | 可配置切换，本地优先 |
| 定时任务 | Spring Scheduler | 每日总结生成 |
| 部署 | Docker Compose | 一键部署，包含 Java + Python + PostgreSQL 三个服务 |

> **架构说明**：Java 负责业务逻辑和数据持久化，Python 负责纯 AI 推理，两者通过 gRPC 通信。详见 [AI 服务层设计文档](2026-08-04-ai-service-design.md)。

---

## 二、功能设计

### 2.1 功能清单

| 功能模块 | 优先级 | 说明 |
|----------|--------|------|
| 📝 随手记 | P0 | 用户随时记录，AI 自动处理 |
| 🪞 镜子（用户画像） | P0 | AI 分析全部记录，生成画像 |
| 💬 向镜子提问 | P0 | 用户主动向镜子提问，支持多轮对话 |
| ⚙️ 模型配置 | P0 | 用户配置自己的 AI 模型 |
| 🗄️ 数据库配置 | P0 | 用户配置自己的数据库地址 |
| 🏷️ 标签审核 | P0 | AI 处理后用户可审核/修改标签 |
| 📋 每日总结 | P1 | 凌晨1点自动生成昨日总结 |
| 💡 写作灵感 | P1 | 用户卡住时，从 RAG 检索相关上下文给提示 |
| 📊 活动统计 | P2 | 记录频率、时间分布等数据可视化 |

### 2.2 核心功能详细设计

#### 2.2.1 随手记

**用户流程：**
1. 用户输入一段文字（任意长度）
2. 点击提交
3. Java 后台异步处理：
   - **gRPC → Python AI 服务**：提取标题、生成摘要、打标签（类型/情绪/状态/关键词）
   - **Java**：保存 records 表
   - **gRPC → Python AI 服务**：文本转向量（Embedding）
   - **Java**：保存 chunks 表（pgvector 向量存储）
4. 处理完成后展示给用户审核（除非用户开启了自动审核）

> **分工原则**：Java 管数据，Python 管推理。详见 [AI 服务层设计文档](2026-08-04-ai-service-design.md) 第一章。

**AI 处理示例：**
```
用户输入："今天下午学了 Spring Security 的认证流程，
          感觉有点难但总算搞懂了，晚上打算继续看授权部分"

AI 处理结果：
├── 标题：学习 Spring Security 认证流程
├── 摘要：学习了 Spring Security 认证流程，觉得有难度但已理解，
│         计划继续学习授权部分
├── 标签：
│   ├── 类型：learning
│   ├── 情绪：[satisfied, calm]
│   ├── 状态：in_progress
│   └── 关键词：Spring Security, 认证, 授权
└── 整条记录作为一个 chunk，直接向量化
```

#### 2.2.2 镜子（用户画像）

**触发方式：** 用户主动点击"查看镜子"按钮

**画像生成流程：**
1. **Java**：用 SQL 统计各维度数据（待办列表、学习记录、情绪分布、关键词汇总、活跃时段）
2. **gRPC → Python**：将统计数据传给 AI 服务，生成各维度分析文本
3. **Java**：保存画像，返回前端

> **注意**：画像用 SQL 统计 + AI 生成分析，不走 RAG 检索。

**画像维度：**

| 维度 | 数据来源 | 生成内容 |
|------|----------|----------|
| 📌 未完成的事 | content_type=todo, status!=completed | 列出所有未完成的待办 |
| 📚 最近在学什么 | content_type=learning, 时间=最近30天 | 学习主题、进度、难点 |
| 💭 情绪状态 | 所有记录的 mood 标签 | 情绪分布、变化趋势 |
| 🏷️ 个人标签 | 所有记录的关键词汇总 | AI 生成的用户特征标签 |
| 📅 生活节奏 | 记录时间分布 | 活跃时段、记录频率 |

**画像展示形式：**
```
┌─────────────────────────────────────────┐
│           🪞 我的镜子                    │
│                                         │
│  📌 未完成的事（content_type=todo）       │
│  ├── 继续学习 Spring Security 授权      │
│  ├── 完成项目数据库设计                  │
│  └── 周末去图书馆还书                    │
│                                         │
│  📚 最近在学（content_type=learning）     │
│  Spring Security（认证/授权）            │
│  数据库设计                              │
│                                         │
│  💭 情绪状态                             │
│  ████████░░ calm/平静 60%               │
│  ██░░░░░░░░ happy/开心 20%              │
│  ██░░░░░░░░ anxious/焦虑 20%            │
│                                         │
│  🏷️ 标签                                │
│  [技术学习] [夜猫子] [计划型] [认证方向]  │
│                                         │
│  📅 活跃时段                             │
│  最近7天写了 5 条记录                    │
│  最活跃时段：晚上 22:00-24:00            │
└─────────────────────────────────────────┘
```

#### 2.2.3 向镜子提问

**功能定位：** 用户可以主动向镜子提问，了解自己的记录和状态。镜子基于 RAG 检索相关记录回答问题。

**与画像的区别：**

| 画像（被动展示） | 提问（主动询问） |
|------------------|------------------|
| AI 自动生成，定时更新 | 用户主动提问，实时回答 |
| 展示全局画像 | 针对具体问题 |
| 不需要用户交互 | 支持多轮对话 |

**支持的问题类型：**

| 类型 | 示例 |
|------|------|
| 查询类 | "我最近学了什么？" |
| 统计类 | "我上周心情怎么样？" |
| 追踪类 | "我有什么待办没完成？" |
| 分析类 | "帮我分析一下最近的情绪变化" |
| 追问类 | "第一个学得怎么样？"（支持上下文） |

**多轮对话支持：**
```
用户：我最近学了什么？
镜子：你最近学了 springboot 和 Python...
      来源：[8月15日] [8月18日]

用户：第一个学得怎么样？
镜子：你学 springboot 时心情很好，觉得很有收获...
      来源：[8月15日]

用户：有没有遇到困难？
镜子：没有记录到困难，你学得很顺利...
```

**处理流程：**
1. 用户输入问题
2. **gRPC → Python**：提取意图、过滤条件（content_type, mood 等），改写检索 query
3. **Java**：执行 pgvector 向量检索 + 元数据过滤，获取相关 chunks
4. **Java**：加载对话历史作为上下文
5. **gRPC → Python**：传入问题 + 历史 + 检索结果，LLM 流式生成回答
6. **Java**：保存对话历史

> **关键**：向量检索在 Java 端完成（pgvector SQL 查询），Python 只负责意图理解和回答生成。

**来源追溯：**
每个回答都附带来源记录，用户点击查看原文：
```
镜子：你最近学了 springboot...
      📝 来源：
      ├── [8月15日] 今天学了 springboot，感觉很有收获...
      └── [8月18日] 继续学 springboot，终于搞懂了 IOC...
```

**API 设计：**
```java
// 发送消息（支持多轮）
@PostMapping("/api/mirror/chat")
public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request);

// 获取历史会话列表
@GetMapping("/api/mirror/sessions")
public ResponseEntity<List<Session>> getSessions();

// 获取某个会话的历史
@GetMapping("/api/mirror/sessions/{sessionId}")
public ResponseEntity<List<Message>> getSessionHistory(@PathVariable UUID sessionId);

// 删除会话
@DeleteMapping("/api/mirror/sessions/{sessionId}")
public ResponseEntity<Void> deleteSession(@PathVariable UUID sessionId);
```

**前端展示：**
```
┌────────────────────────────────────┐
│ 🪞 和镜子对话                       │
├────────────────────────────────────┤
│                                    │
│ 💬 我最近学了什么？                  │
│                                    │
│ 🪞 你最近学了：                     │
│ 1. Springboot（8月15日）           │
│ 2. Python（8月18日）               │
│ [查看来源]                          │
│                                    │
│ 💬 第一个学得怎么样？                │
│                                    │
│ 🪞 你学 springboot 时心情很好...    │
│ [查看来源]                          │
│                                    │
├────────────────────────────────────┤
│ ┌──────────────────────────────┐  │
│ │ 输入你的问题...               │  │
│ └──────────────────────────────┘  │
└────────────────────────────────────┘
```

#### 2.2.4 每日总结

**触发方式：** 每天凌晨 1:00 自动执行（Spring Scheduler）

**处理流程：**
1. **Java**：SQL 查询昨天所有记录的统计数据
2. **gRPC → Python**：调用 `MirrorChat.Chat`，传入统计数据，生成总结文本（不新增 Proto）
3. **Java**：存储总结，进 RAG（可被后续检索）

> **实现方式**：复用现有 Chat 服务，不新增 Proto。总结本质是"根据数据生成文本"，和对话用同一个 LLM。

**总结内容：**
```
📋 2026-07-22 日报

今日记录 4 条：
- 学习了 Spring Security 认证流程
- 完成了数据库概念设计
- 晚上和朋友吃饭，聊了毕业设计
- 记录了一个新的项目想法

情绪分布：calm ×3, happy ×1
活跃时段：下午 14:00-16:00, 晚上 20:00-23:00

💡 明天可以继续：Spring Security 授权部分
```

#### 2.2.5 写作灵感

**触发方式：** 用户写到一半停顿超过 30 秒

**处理流程：**
1. **Java**：获取用户当前输入的前几句话
2. **gRPC → Python**：调用 `EmbeddingService.Embed`，将当前输入转向量
3. **Java**：pgvector 检索相关历史记录
4. **gRPC → Python**：调用 `MirrorChat.Chat`，基于上下文生成灵感提示
5. 临时内容，不存储

> **实现方式**：复用 `Embed` + `Chat` 组合，不新增 Proto。

**示例：**
```
用户写："最近感觉学习效率..."

💡 灵感提示：
- 你上周也提到过效率问题，当时是因为任务太多
- 你最近在学 Spring Security，是不是这部分比较难？
- 要不要先写下今天完成了什么，再想想哪里卡住了？
```

---

## 三、数据管道设计

### 3.0 输入层详细设计

**输入层包含三种数据来源：**

| 来源 | 说明 | 后续处理 |
|------|------|----------|
| 用户原始输入 | 用户手动输入的文本 | AI 处理 → 用户审核 → 进入清洗层 |
| AI 处理结果 | AI 生成的标题、摘要、标签 | 用户审核确认后存储 |
| AI 自动生成内容 | 每日/每周总结、镜子画像 | SQL 统计 + AI 洞察，总结进 RAG |

#### 3.0.1 用户原始输入

**输入形式：** 纯文本框，用户想写什么就写什么，不强制选标签

**标签体系（定死，不支持用户自定义）：**

数据库和 Proto 统一存储英文小写，前端显示中文。

| 维度 | 存储值 | 中文显示 | 说明 |
|------|--------|----------|------|
| 内容类型 | todo / thought / learning / plan / note / work / social / health | 待办 / 感想 / 学习 / 计划 / 随记 / 工作 / 社交 / 健康 | AI 自动分类，8个选项 |
| 情绪 | happy / excited / satisfied / grateful / expecting / calm / bored / confused / anxious / sad / angry / exhausted / stressed | 开心 / 兴奋 / 满足 / 感恩 / 期待 / 平静 / 无聊 / 困惑 / 焦虑 / 难过 / 愤怒 / 疲惫 / 压力 | AI 自动分类，支持多选（13个选项） |
| 状态 | not_started / in_progress / completed | 未开始 / 进行中 / 已完成 | 仅对待办/计划类有效 |

**情绪标签特殊规则：**
- 支持多选：一条记录可以打多个情绪标签
- 示例："开心但是累" → mood: ["happy", "exhausted"]（前端显示：开心、疲惫）
- AI 会判断内容是否有意义，无意义内容（如"???"）跳过分类

**情绪标签特殊规则：**
- 支持多选：一条记录可以打多个情绪标签
- 示例："开心但是累" → mood: ["开心", "疲惫"]
- AI 会判断内容是否有意义，无意义内容（如"???"）跳过分类

**内容长度限制：**

| 长度 | 处理方式 |
|------|----------|
| ≤ 500 字 | 正常处理 |
| > 500 字 | 提示用户拆分（可选择自动拆分） |

**特殊情况处理：**

| 场景 | 处理方式 |
|------|----------|
| 多件事混合 | AI 自动拆分成多条，用户确认 |
| 纯情绪输出（如"啊啊啊啊"） | 只打情绪标签（如 anxious），跳过标题/摘要/RAG |
| 超长内容（> 500字） | 提示拆分，可自动按语义段落拆分 |

#### 3.0.2 AI 处理用户输入

**处理流程：**
```
用户输入文本
    ↓
Java：长度检测
    ├── > 500 字 → 提示拆分（自动/手动）
    ├── 纯情绪 → 标记为"情绪表达"，只打情绪标签
    └── 正常内容 → 继续
    ↓
Java：gRPC 调用 Python AI 服务
    ├── Classify：生成标题/摘要/标签（一次调用完成）
    ├── Split（如需要）：判断是否拆分 + 返回拆分结果
    └── 返回处理结果
    ↓
Java：保存 records 表
    ↓
Java：gRPC 调用 Python AI 服务
    └── Embed：文本转向量
    ↓
Java：保存 chunks 表（pgvector）
    ↓
用户审核（半自动模式下）
    ├── 确认 → 完成
    ├── 修改 → 修改后重新保存
    └── 全局开关：自动审核模式
```

**AI 处理示例：**
```
用户输入："今天下午学了 Spring Security 的认证流程，
          感觉有点难但总算搞懂了，晚上打算继续看授权部分"

AI 处理结果：
├── 标题：学习 Spring Security 认证流程
├── 摘要：学习了认证流程，觉得有难度但已理解，计划继续学习授权
├── 标签：
│   ├── 类型：learning
│   ├── 情绪：[satisfied, calm]
│   ├── 状态：in_progress
│   └── 关键词：Spring Security, 认证, 授权
└── 整条记录作为一个 chunk，直接向量化
```

#### 3.0.3 AI 自动生成内容

**每日总结（凌晨1点自动生成）：**
- 由 SQL 查询昨天所有记录的统计数据
- 由 AI 生成洞察（模式发现、关联分析）
- 存储后进 RAG（可被后续检索）

**每周总结（每周日凌晨自动生成）：**
```
📋 2026年第30周总结（7.22 - 7.28）

📊 数据概览（SQL 生成）
├── 记录：12 条 | 日均 1.7 条
├── 情绪：calm 50%，happy 25%，anxious 17%，sad 8%
├── 待办完成率：33%（1/3）
└── 最活跃时段：晚上 22:00-24:00

🔍 AI 洞察（AI 生成）
├── 你周三晚上压力比较大，当天写了 4 条记录，
│   其中 3 条 anxious，都在学 Spring Security 认证部分。
├── 这周情绪整体比上周好，anxious 少了 1 次，
│   多了 2 条 happy 的记录。
├── 你有 2 条待办超过一周未完成：
│   "完成数据库设计"和"去图书馆还书"。
└── 你最近的学习重心从数据库转向了安全方向。
```

**每月总结（每月1日凌晨自动生成）：**
- 与周总结类似，但包含更多趋势对比
- 对比上月数据，展示月度变化

**镜子画像（用户主动触发）：**
- 由 AI 分析所有记录生成
- 不进 RAG（本身是输出，不需要被检索）

**写作灵感（用户卡住时触发）：**
- 从 RAG 检索相关历史记录
- 临时内容，不存储

### 3.1 五层数据管道

```
用户输入
   ↓
┌─────────────────────────────────────────┐
│ 第 1 层：输入层（Input）                  │
│ - 长度检测（≤500字正常，>500字提示拆分）   │
│ - 内容类型判断（正常/纯情绪/多事件）       │
│ - 多事件自动拆分 + 用户确认               │
│ - AI 生成标题/摘要/标签（一次性完成）      │
└─────────────────┬───────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ 第 2 层：清洗层（Clean）                  │
│ - 文本规范化（空白、换行、控制字符）        │
│ - 日期标准化（"昨天"→具体日期）           │
│ - 内容有效性检测（空内容、纯表情）          │
│ - 特殊内容标记（URL、代码块）              │
│ - 重复提交检测                            │
└─────────────────┬───────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ 第 3 层：分类层（Classify）               │
│ 【gRPC → Python AI 服务】                │
│ AI 自动打标签：                           │
│ - 内容类型：8个选项                       │
│ - 情绪：12个选项，支持多选                │
│ - 状态：未开始/进行中/已完成（待办/计划类）│
│ - 主题关键词：3-5 个                      │
│ 特殊处理：                               │
│ - 纯情绪/无意义内容 → 跳过分类            │
│ - 多事件内容 → 拆分后每条单独分类          │
└─────────────────┬───────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ 第 4 层：向量化层（Embed）                │
│ 【gRPC → Python AI 服务】                │
│ - 调用 embedding 模型                    │
│   - 本地：BGE-m3（可配置）               │
│   - API：OpenAI/智谱/通义千问（可配置）   │
│ - 文本 → 向量（维度取决于模型）           │
│ - 向量返回 Java 端                       │
└─────────────────┬───────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ 第 5 层：存储层（Store）                  │
│ 【Java 端完成】                          │
│ - 原始文本 + 标签 → records 表           │
│ - 向量 + 元数据 → chunks 表（pgvector）  │
│ - 一条记录 = 一个 chunk                  │
└─────────────────────────────────────────┘
```

### 3.2 清洗层详细设计

**核心原则：** 纯代码清洗，不调 AI，速度快、成本零

**清洗任务清单：**

| # | 功能 | 实现方式 | 说明 |
|---|------|----------|------|
| 1 | 去除首尾空白 | trim() | 基础清洗 |
| 2 | 去除多余换行 | 正则替换 | 连续空行合并 |
| 3 | 去除特殊控制字符 | 正则过滤 | 保留可读字符 |
| 4 | 日期标准化 | 规则替换 | "昨天"→"2026-07-22" |
| 5 | 空内容检测 | 长度判断 | 只有空白/标点 → 丢弃 |
| 6 | 纯表情检测 | 正则匹配 | 只有 emoji → 标记"情绪表达" |
| 7 | URL 保留 | 正则匹配 | 标记 has_url |
| 8 | 代码块处理 | 检测 ``` | 标记 has_code |
| 9 | 重复提交检测 | MD5 hash | 5 分钟内相同内容 → 拒绝 |

**扩展性设计：内容块模型**

每条记录由多个"内容块"组成，支持未来扩展：

| 内容块类型 | 第一版支持 | 说明 |
|------------|------------|------|
| text | ✅ | 纯文本 |
| code | ✅ | 代码块（带语言标记） |
| link | ✅ | 链接（URL） |
| image | ❌ | 结构预留，后续加 |
| file | ❌ | 结构预留，后续加 |

**示例：**
```json
{
  "blocks": [
    {"type": "text", "content": "今天学了 Python"},
    {"type": "code", "content": "[x**2 for x in range(10)]", "language": "python"},
    {"type": "link", "url": "https://example.com"}
  ]
}
```

**和输入层的边界：**

| 输入层负责 | 清洗层负责 |
|------------|------------|
| 业务逻辑（拆分、审核） | 数据卫生（清洗、标准化） |
| 长度限制（500字） | 空内容检测 |
| 多事件拆分 | URL/代码块标记 |
| AI 生成标题/摘要/标签 | 重复提交检测 |
| 用户审核交互 | 内容块解析 |

**注意：** 句子边界检测不在清洗层（没标点的文本检测不准），由切片层的 AI 处理。

### 3.3 标签审核机制

**三种审核模式：**

| 模式 | 适合谁 | 行为 |
|------|--------|------|
| **全手动** | 强迫症用户 | 每条都审核，每个标签都确认 |
| **半自动**（默认） | 大多数人 | AI 处理完展示结果，用户确认或修改 |
| **全自动** | 懒人 | AI 直接保存，不问 |

**审核界面：**
```
┌─────────────────────────────────────┐
│ 📝 今天学了 Spring Security        │
│                                     │
│ AI 生成的结果：                      │
│ 标题：[学习 Spring Security]  ✏️    │
│ 摘要：[学习了认证流程，感觉有难度]   │
│                                     │
│ 标签：                              │
│ 类型：[learning ✓]  情绪：[calm ✓]  │
│ 状态：[in_progress ✓] 关键词：[安全,认证]│
│                                     │
│ [确认并保存]  [修改后保存]           │
└─────────────────────────────────────┘
```

**全局开关（设置页面）：**
```
⚙️ AI 处理设置

☑ 自动审核（跳过人工确认）
  开启后 AI 直接保存，无需确认

☑ 自动标签（AI 自动打标签）
  关闭后需要手动选择标签
```

---

## 四、系统架构

### 4.1 整体架构图

```
┌─────────────────────────────────────────────────┐
│                 Vue 3 前端                       │
│  ┌──────────┐ ┌──────────┐ ┌──────────────────┐ │
│  │ 记录页面  │ │ 镜子页面  │ │ 设置页面         │ │
│  │          │ │          │ │ - 模型配置       │ │
│  │ - 输入框  │ │ - 画像展示│ │ - 数据库配置     │ │
│  │ - 标签审核│ │ - 维度切换│ │ - 审核模式       │ │
│  │ - 历史列表│ │          │ │                  │ │
│  └──────────┘ └──────────┘ └──────────────────┘ │
└──────────────────────┬──────────────────────────┘
                       │ HTTP / SSE
┌──────────────────────▼──────────────────────────┐
│              Spring Boot 后端                    │
│                                                 │
│  ┌─────────────────────────────────────────┐    │
│  │           API 层（Controller）            │    │
│  │  记录 CRUD │ 对话 │ 画像 │ 配置管理       │    │
│  └─────────────────┬───────────────────────┘    │
│                    ↓                             │
│  ┌─────────────────────────────────────────┐    │
│  │           服务层（Service）               │    │
│  │  数据管道编排 │ pgvector 检索 │ 定时任务   │    │
│  └─────────────────┬───────────────────────┘    │
│                    ↓                             │
│  ┌─────────────────────────────────────────┐    │
│  │         gRPC 客户端（AiGrpcClient）       │    │
│  │  分类 │ Embedding │ 对话 │ 画像生成       │    │
│  └─────────────────┬───────────────────────┘    │
│                    ↓                             │
│  ┌─────────────────────────────────────────┐    │
│  │           数据层                          │    │
│  │  PostgreSQL + pgvector（用户自配地址）     │    │
│  └─────────────────────────────────────────┘    │
└────────────────────────┬────────────────────────┘
                         │ gRPC
┌────────────────────────▼────────────────────────┐
│           Python AI 服务（无状态）               │
│                                                 │
│  ┌─────────────────────────────────────────┐    │
│  │         gRPC 服务端                       │    │
│  │  RecordProcessor │ EmbeddingService      │    │
│  │  MirrorChat │ MirrorProfile              │    │
│  └─────────────────┬───────────────────────┘    │
│                    ↓                             │
│  ┌──────────────────┐ ┌────────────────────┐    │
│  │  Embedding 引擎   │ │  LLM 调用层        │    │
│  │  本地 BGE-m3     │ │  OpenAI/通义/智谱   │    │
│  │  或 API 切换     │ │  用户自配           │    │
│  └──────────────────┘ └────────────────────┘    │
└─────────────────────────────────────────────────┘
```

> **架构说明**：Java 负责业务逻辑和数据持久化，Python 负责纯 AI 推理。两者职责分明，通过 gRPC 通信。详见 [AI 服务层设计文档](2026-08-04-ai-service-design.md)。

### 4.2 模块划分

**Java 端（mirror-backend）：**
```
src/main/java/com/mirror/
├── config/                        # 配置类
│   ├── AiModelConfig.java         # AI 模型配置（gRPC 地址等）
│   └── DataSourceConfig.java      # 数据库配置
├── controller/                    # API 控制器
│   ├── RecordController.java
│   ├── MirrorController.java
│   ├── ChatController.java
│   └── SettingsController.java
├── service/                       # 业务逻辑
│   ├── RecordService.java         # 记录处理（编排 gRPC 调用 + 数据库写入）
│   ├── RagService.java            # RAG 检索（pgvector SQL 查询）
│   ├── MirrorService.java         # 画像生成（查数据 → gRPC → 保存）
│   ├── ChatService.java           # 对话服务（意图提取 → 检索 → 生成）
│   └── SchedulerService.java      # 定时任务
├── grpc/                          # gRPC 客户端
│   ├── GrpcClientConfig.java      # 连接配置
│   └── AiGrpcClient.java          # 统一封装（classify/embed/chat/profile）
├── pipeline/                      # 数据管道（Java 编排，AI 调用走 gRPC）
│   ├── CleanLayer.java            # 纯代码清洗，不调 AI
│   └── ProcessPipeline.java       # 管道编排
├── model/                         # 数据模型
│   ├── Record.java
│   ├── Tag.java
│   ├── Chunk.java
│   └── Mirror.java
└── repository/                    # 数据访问（含 pgvector 向量查询）
    ├── RecordRepository.java
    └── ChunkRepository.java
```

**Python 端（mirror-ai）：**
```
mirror-ai/
├── server.py                      # gRPC 服务启动入口
├── config.py                      # 配置管理
├── services/                      # gRPC 服务实现
│   ├── record_processor.py        # 记录分类
│   ├── embedding_service.py       # Embedding（本地/API 切换）
│   ├── chat_service.py            # 对话生成
│   └── profile_service.py         # 画像生成
├── llm/                           # LLM 统一接口 + 多厂商实现
│   ├── base.py
│   ├── openai_llm.py
│   ├── qwen_llm.py
│   └── zhipu_llm.py
├── embedding/                     # Embedding 统一接口 + 本地/API 实现
│   ├── base.py
│   ├── local_embedder.py
│   └── api_embedder.py
├── prompts/                       # Prompt 模板
├── config.example.yml
├── requirements.txt
└── Dockerfile
```

> 详细 proto 定义和 Python 模块设计见 [AI 服务层设计文档](2026-08-04-ai-service-design.md)。

---

## 五、数据库设计

### 5.1 核心表

```sql
-- 记录表
CREATE TABLE records (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,           -- 原始内容
    title VARCHAR(200),             -- AI 生成的标题
    summary TEXT,                    -- AI 生成的摘要
    content_type VARCHAR(20),        -- 类型：todo/thought/learning/plan/note/work/social/health
    mood JSONB,                      -- 情绪：支持多选 ["happy", "exhausted"]
    status VARCHAR(20),              -- 状态：not_started/in_progress/completed
    user_reviewed BOOLEAN DEFAULT FALSE, -- 是否经过用户审核
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 标签表
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    record_id BIGINT REFERENCES records(id),
    keyword VARCHAR(50),             -- 关键词标签
    created_at TIMESTAMP DEFAULT NOW()
);

-- 向量块表（pgvector）
-- 注意：向量维度取决于 embedding 模型
--   BGE-m3 本地 → 1024 维
--   OpenAI text-embedding-3-small → 1536 维
--   建议初始化时按实际模型设置，或使用 vector(2048) 兼容大多数模型
CREATE TABLE chunks (
    id BIGSERIAL PRIMARY KEY,
    record_id BIGINT REFERENCES records(id),
    content TEXT NOT NULL,           -- 切片内容
    metadata JSONB,                 -- 元数据（类型、情绪、时间等）
    embedding vector(1024),         -- BGE-m3 默认 1024 维，按实际模型调整
    created_at TIMESTAMP DEFAULT NOW()
);

-- 每日总结表
CREATE TABLE daily_summaries (
    id BIGSERIAL PRIMARY KEY,
    summary_date DATE UNIQUE,       -- 总结日期
    content TEXT,                    -- 总结内容
    record_count INT,               -- 当日记录数
    created_at TIMESTAMP DEFAULT NOW()
);

-- 画像表
CREATE TABLE mirror_profiles (
    id BIGSERIAL PRIMARY KEY,
    profile_json JSONB,             -- 画像数据（JSON 格式）
    generated_at TIMESTAMP DEFAULT NOW()
);

-- 对话历史表（支持多轮对话）
CREATE TABLE conversation_history (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL,       -- 会话ID（一组对话）
    role VARCHAR(20) NOT NULL,      -- 角色：user / assistant
    content TEXT NOT NULL,          -- 问题或回答
    sources JSONB,                  -- 来源记录 [{record_id, quote, date}]
    created_at TIMESTAMP DEFAULT NOW()
);

-- 会话表
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY,            -- 会话ID
    title VARCHAR(200),             -- 会话标题（AI生成）
    last_message_at TIMESTAMP,      -- 最后消息时间
    created_at TIMESTAMP DEFAULT NOW()
);

-- 用户配置表（合并认证和AI配置）
-- 注意：此表在第十章认证设计中不再重复定义
CREATE TABLE user_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    -- 认证相关
    access_password VARCHAR(255),    -- 访问密码（加密），可为空
    -- LLM 配置
    ai_provider VARCHAR(50),         -- AI 提供商：openai/zhipu/qwen
    ai_api_key TEXT,                 -- API Key（加密存储）
    ai_base_url TEXT,                -- API 地址
    ai_model VARCHAR(100),           -- 模型名称
    -- Embedding 配置
    embedding_source VARCHAR(20) DEFAULT 'local', -- local / api
    embedding_api_key TEXT,          -- Embedding API Key（加密）
    embedding_model VARCHAR(100),    -- Embedding 模型名
    -- 其他
    db_url TEXT,                     -- 数据库地址
    review_mode VARCHAR(20) DEFAULT 'semi_auto', -- 审核模式
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 六、API 设计

### 6.1 记录相关

```
POST   /api/records           # 创建记录
GET    /api/records           # 获取记录列表
GET    /api/records/{id}      # 获取单条记录
PUT    /api/records/{id}      # 更新记录
DELETE /api/records/{id}      # 删除记录
POST   /api/records/{id}/review  # 审核标签
```

### 6.2 画像相关

```
GET    /api/mirror            # 获取当前画像
POST   /api/mirror/generate   # 重新生成画像
GET    /api/mirror/dimension/{dim}  # 获取单维度画像
```

### 6.3 对话相关

```
POST   /api/mirror/chat       # 发送消息（支持多轮）
GET    /api/mirror/sessions   # 获取历史会话列表
GET    /api/mirror/sessions/{sessionId}  # 获取某个会话历史
DELETE /api/mirror/sessions/{sessionId}  # 删除会话
```

### 6.4 总结相关

```
GET    /api/summaries         # 获取总结列表
GET    /api/summaries/{date}  # 获取指定日期总结
POST   /api/summaries/generate/{date}  # 手动生成总结
```

### 6.5 配置相关

```
GET    /api/settings          # 获取配置
PUT    /api/settings          # 更新配置
POST   /api/settings/test-ai  # 测试 AI 连接
POST   /api/settings/test-db  # 测试数据库连接
```

### 6.6 灵感相关

```
POST   /api/inspiration       # 获取写作灵感
```

---

## 七、AI Prompt 设计

> **归属**：Prompt 模板存放在 Python AI 服务的 `prompts/` 目录下，由 Python 端加载和使用。此处记录设计意图和模板内容，便于前后端开发时参考。

### 7.1 记录处理 Prompt

```
你是一个个人记录助手。用户会输入一段日常记录，请完成以下任务：

1. 判断内容是否有意义：无意义内容（如"???"、纯符号）返回 skip: true
2. 生成标题：简短概括内容（10字以内）
3. 生成摘要：一句话总结（30字以内）
4. 打标签（返回英文小写值）：
   - 内容类型：todo/thought/learning/plan/note/work/social/health
   - 情绪：支持多选，可选值：happy/excited/satisfied/grateful/expecting/calm/bored/confused/anxious/sad/angry/exhausted/stressed
   - 状态（仅待办/计划类）：not_started/in_progress/completed
   - 关键词：3-5 个

请以 JSON 格式返回：
{
  "skip": false,
  "skip_reason": "",
  "title": "...",
  "summary": "...",
  "content_type": "learning",
  "mood": ["satisfied", "calm"],
  "status": "in_progress",
  "keywords": ["...", "..."]
}
```

### 7.2 画像生成 Prompt

```
你是一个自我认知助手。请根据以下用户记录数据，生成一份用户画像。

数据维度：
- 未完成的待办：{todo_list}
- 最近学习内容：{learning_list}
- 情绪记录：{mood_list}
- 关键词汇总：{keyword_list}
- 活跃时段：{active_time}

请从以下维度分析：
1. 未完成的事：列出所有未完成的待办
2. 最近在学什么：总结学习主题和进度
3. 情绪状态：基于情绪标签给出分布
4. 个人标签：基于关键词生成 5-8 个特征标签
5. 生活节奏：基于活跃时段描述作息模式

注意：只做事实陈述，不做主观判断。不要说"用户状态不好"，
而要说"用户标记了 3 次焦虑"。
```

### 7.3 灵感生成 Prompt

```
用户正在写日记，但似乎卡住了。请根据以下信息给出写作灵感：

用户当前输入：{current_input}
相关历史记录：{related_records}

请给出 2-3 个写作方向的提示，帮助用户继续写下去。
提示应该基于用户的历史记录，但不要直接复制。
```

### 7.4 对话意图识别 Prompt

```
分析以下用户查询，提取过滤条件。

用户查询：{query}

可选的内容类型：todo, thought, learning, plan, note, work, social, health
可选的情绪：happy, excited, satisfied, grateful, expecting, calm, bored, confused, anxious, sad, angry, exhausted, stressed

请返回 JSON 格式（使用英文小写值）：
{"content_type": "learning|null", "mood": ["happy", "calm"]|null, "time_range": "最近7天|null", "rewritten_query": "改写后的检索query"}
```

### 7.5 对话回答 Prompt

```
你是一个人生教练"镜子"，根据用户的日记记录回答问题。

{对话历史}

用户问：{user_message}

检索到的相关记录：
{retrieved_chunks}

请基于以上记录回答用户的问题。
要求：
1. 只做事实陈述，不做主观判断
2. 每个结论都要引用来源记录
3. 如果检索结果不足以回答，诚实说明
4. 回答要简洁，3-5 句话

请以 JSON 格式返回：
{
  "answer": "你的回答...",
  "sources": [
    {"record_id": "xxx", "quote": "引用的原文", "date": "2026-08-15"}
  ]
}
```

---

## 八、异常机制与兜底设计

### 8.1 核心原则

- **原子性**：AI 分类 + Embedding 必须全部成功或全部失败，不存在部分成功
- **降级优先**：AI 不可用时用默认值，不阻塞用户
- **用户可控**：失败时提供"重新尝试"按钮，用户可手动恢复
- **数据不丢**：即使失败，原始文本数据已保存
- **配置统一**：LLM/Embedding 配置以 Java 数据库（user_settings）为准，Python 通过 gRPC 热更新

### 8.1.1 异步处理说明

前端提交记录后立即返回 `status=processing`，Java 后台线程同步调用 gRPC（blocking stub），处理完成后更新状态。不是异步 gRPC，是"同步调用在后台线程"。

### 8.1.2 Embedding 模型切换

切换 Embedding 模型时（如从本地 BGE-m3 切到 API），如果向量维度不同，需要**重建向量索引**。在设置页面提示用户："切换模型需要重新生成向量，已有记录的向量将被清除。"

### 8.2 记录处理状态流转

```
用户提交日记
    ↓
status = processing（处理中）
    ↓
┌─────────────────────────────────────┐
│ 1. AI分类处理                        │
│    ├─ 失败 → status=failed，结束     │
│    └─ 成功 → 继续                    │
│                                     │
│ 2. Embedding处理                     │
│    ├─ 失败 → status=failed，结束     │
│    └─ 成功 → 继续                    │
│                                     │
│ 3. 保存所有结果（标签+向量）          │
│    └─ status=done                    │
└─────────────────────────────────────┘
```

**状态定义：**

| 状态 | 含义 | 前端展示 |
|------|------|----------|
| processing | AI正在处理 | 转圈动画 + "AI整理中" |
| done | 全部完成 | 正常显示标签 |
| failed | 处理失败 | 显示错误 + "重新尝试"按钮 |

### 8.3 失败处理流程

```
处理失败
    ↓
status = failed
    ↓
用户看到：
┌────────────────────────────────┐
│ ⚠️ 处理失败                    │
│ 2小时前                        │
│                                │
│ 今天学了springboot...          │
│                                │
│ [重新尝试]  [删除]             │
└────────────────────────────────┘
    ↓
用户点击"重新尝试"
    ↓
重新执行整个流程
    ├─ 成功 → status=done
    └─ 失败 → status=failed，提示"仍然失败"
```

### 8.4 gRPC 通信异常处理

Java 端通过 gRPC 调用 Python AI 服务时的异常处理：

| gRPC 状态码 | 原因 | Java 端处理 | 用户看到 |
|---|---|---|---|
| `DEADLINE_EXCEEDED` | Python 端处理超时 | 标记 failed，可重试 | "处理超时，请重试" |
| `UNAVAILABLE` | Python 服务未启动/挂了 | 重试1次 | "AI 服务暂时不可用" |
| `INVALID_ARGUMENT` | 输入参数错误 | 记录日志 | "输入内容有误" |
| `INTERNAL` | Python 端内部异常 | 标记 failed | "处理失败，请重试" |

**超时设置：**

| RPC 方法 | 超时时间 | 说明 |
|---|---|---|
| Classify | 30s | LLM 调用较慢 |
| Embed | 10s | 本地模型快，API 稍慢 |
| Chat | 60s | 流式生成，允许更长 |
| ExtractIntent | 15s | 简单意图识别 |
| GenerateProfile | 60s | 多维度分析 |

### 8.5 AI 调用异常处理（Python 端 → LLM/Embedding）

| 异常类型 | 处理方式 | 用户看到 |
|---------|---------|---------|
| LLM 超时 | Python 抛 DEADLINE_EXCEEDED | "处理超时，请重试" |
| LLM API 限流 | Python 抛 INTERNAL + 信息 | "请求过多，请稍后" |
| LLM 内容被拒 | Python 抛 INVALID_ARGUMENT | "内容不合规" |
| LLM 返回格式错误 | Python 尝试修复，失败抛异常 | "处理失败，请重试" |
| Embedding 模型不可用 | Python 抛 UNAVAILABLE | "处理失败，请重试" |
| Embedding 内容过长 | Python 截断后重试 | 正常处理 |

**关键：Embedding 失败时，Java 端不会写入向量数据库，只记录 status=failed**

### 8.6 数据库异常处理

| 异常类型 | 处理方式 | 用户看到 |
|---------|---------|---------|
| 连接失败 | 返回错误 | "服务暂时不可用" |
| 写入失败 | 重试1次 | "保存失败，请重试" |
| 查询失败 | 返回空结果 | 正常展示 |

### 8.7 镜子/对话异常处理

| 场景 | 异常 | 兜底方案 |
|------|------|----------|
| 镜子生成 | AI调用失败 | 显示上次缓存的画像 |
| 镜子生成 | 无缓存 | "正在生成中，请稍后再试" |
| 对话 | AI调用失败 | "抱歉，我暂时无法回答" |
| 对话 | RAG检索为空 | "我没有找到相关记录" |

### 8.8 重新尝试机制

```java
@Service
public class RecordService {

    @Autowired
    private AiGrpcClient aiGrpcClient;  // gRPC 客户端

    // 重新处理记录（原子操作）
    public boolean retryProcessing(Long recordId) {
        Record record = recordMapper.selectById(recordId);
        if (!record.getStatus().equals("failed")) {
            return false; // 只有 failed 状态才能重试
        }

        try {
            // 1. gRPC → Python：AI 分类
            ClassifyResponse classifyResult = aiGrpcClient.classify(record.getContent());

            // 2. gRPC → Python：Embedding
            EmbedResponse embedResult = aiGrpcClient.embed(record.getContent());

            // 3. 全部成功，一起保存（Java 端写数据库）
            record.setStatus("done");
            record.setContentType(classifyResult.getContentType());
            record.setMood(classifyResult.getMoodsList());
            record.setKeywords(classifyResult.getKeywordsList());
            recordMapper.updateById(record);

            // 4. 保存到向量库（pgvector）
            Chunk chunk = Chunk.builder()
                .recordId(record.getId())
                .content(record.getContent())
                .embedding(embedResult.getVectorList())
                .build();
            chunkMapper.insert(chunk);

            return true;

        } catch (StatusRuntimeException e) {
            // gRPC 调用失败，保持 failed 状态
            log.error("Record retry failed (gRPC): " + recordId, e);
            return false;
        } catch (Exception e) {
            log.error("Record retry failed: " + recordId, e);
            return false;
        }
    }
}
```

### 8.9 设计总结

| 原则 | 实现 |
|------|------|
| 原子性 | AI 分类 + Embedding 作为整体，一起成功或失败 |
| 降级优先 | 失败时用默认值，不阻塞用户 |
| 用户可控 | 提供"重新尝试"按钮，用户可手动恢复 |
| 数据不丢 | 原始文本始终保存，失败只影响标签和向量 |
| 可追溯 | 所有异常记录日志，方便排查 |
| 职责分离 | gRPC 通信异常由 Java 端捕获，AI 推理异常由 Python 端转为 gRPC 错误码 |
| 配置统一 | Java 数据库为配置源，Python 通过 gRPC 热更新 |
| 标签英文存储 | 数据库存英文小写，前端显示中文，Proto 枚举对应英文 |

---

## 九、开发计划

### 9.1 阶段划分

| 阶段 | 时间 | 内容 | 交付物 |
|------|------|------|--------|
| **设计阶段** | 8月 | 系统设计、技术选型、Proto 定义 | 设计文档、Proto 文件 |
| **基础开发** | 9月 | Java 后端骨架 + Python AI 服务骨架 + 前端基础 | 可运行的框架（三服务联调） |
| **AI 集成** | 10月 | gRPC 联调 + 数据管道 + RAG | AI 处理流程跑通 |
| **核心功能** | 11月 | 随手记 + 镜子 + 对话 | MVP |
| **完善功能** | 12月 | 总结 + 灵感 + 优化 + Embedding API 模式 | 完整功能 |
| **测试阶段** | 1月 | 系统测试 + Bug 修复 | 测试报告 |
| **论文撰写** | 2月 | 毕业论文 | 论文初稿 |
| **答辩准备** | 3-4月 | 答辩材料 + 演示 | 答辩通过 |

### 9.2 MVP 范围（保底能交）

- ✅ 随手记功能
- ✅ AI 自动处理（标题、摘要、标签）
- ✅ 标签审核机制
- ✅ 基础 RAG 存储
- ✅ 镜子画像（基础版）
- ✅ 模型配置

### 9.3 加分项（有时间再做）

- 📋 每日总结
- 💡 写作灵感
- 📊 数据可视化
- 🎨 UI 美化

---

## 十、认证与访问控制

### 10.1 设计原则

- **简单优先**：第一版只做简单密码保护，不设计复杂登录
- **可选密码**：密码保护可选，不设置也能使用
- **保留扩展**：数据库预留 user_id 字段，未来可扩展多用户
- **隐私优先**：密码本地存储，不上传任何服务

### 10.2 第一版：单用户密码保护

**使用流程：**

```
首次使用：
┌─────────────────────────────┐
│ 欢迎使用 Mirror             │
│                             │
│ 设置访问密码（可选）：       │
│ [________________]          │
│                             │
│ 确认密码：                  │
│ [________________]          │
│                             │
│ [跳过]  [开始使用]          │
└─────────────────────────────┘

后续访问（设置了密码）：
┌─────────────────────────────┐
│ 🔒 Mirror                   │
│                             │
│ 请输入密码：                │
│ [________________]          │
│                             │
│ ☑ 记住密码（7天）           │
│                             │
│ [解锁]                      │
└─────────────────────────────┘

后续访问（未设置密码）：
└─ 直接进入主页
```

### 10.3 数据库设计

```sql
-- users 表（保留扩展性）
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) DEFAULT 'user',
    password_hash VARCHAR(255),  -- 加密存储，可为空
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- user_settings 表：见第五章核心表定义（已合并认证和AI配置字段）
```

### 10.4 API 设计

```
GET    /api/auth/status    # 检查是否需要密码
POST   /api/auth/verify    # 验证密码
POST   /api/auth/setup     # 设置密码（首次）
POST   /api/auth/change    # 修改密码
POST   /api/auth/clear     # 清除密码
```

**请求/响应示例：**

```json
// GET /api/auth/status
{
  "needPassword": true,
  "hasUser": true
}

// POST /api/auth/verify
// 请求
{ "password": "123456" }
// 响应
{ "success": true, "token": "xxx", "expiresIn": 604800 }

// POST /api/auth/setup
// 请求
{ "password": "123456" }
// 响应
{ "success": true }
```

### 10.5 实现逻辑

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    @GetMapping("/status")
    public ResponseEntity<AuthStatus> status() {
        boolean hasUser = userService.hasUser();
        boolean needPassword = hasUser && userService.needPassword();
        return ResponseEntity.ok(new AuthStatus(needPassword, hasUser));
    }
    
    @PostMapping("/verify")
    public ResponseEntity<AuthResult> verify(@RequestBody AuthRequest request) {
        boolean valid = authService.verifyPassword(request.getPassword());
        if (valid) {
            String token = authService.generateToken();
            return ResponseEntity.ok(new AuthResult(true, token, 604800));
        }
        return ResponseEntity.ok(new AuthResult(false, null, 0));
    }
    
    @PostMapping("/setup")
    public ResponseEntity<Void> setup(@RequestBody AuthRequest request) {
        authService.setupPassword(request.getPassword());
        return ResponseEntity.ok().build();
    }
}
```

### 10.6 前端流程

```javascript
// App 启动
async function init() {
  const status = await api.get('/api/auth/status');
  
  if (!status.hasUser) {
    // 首次使用，显示欢迎页
    showWelcomePage();
  } else if (status.needPassword) {
    // 需要密码，显示解锁页
    showUnlockPage();
  } else {
    // 直接进入主页
    showMainPage();
  }
}

// 登录成功后
function onLoginSuccess(token, expiresIn) {
  // 存储 token
  localStorage.setItem('mirror_token', token);
  localStorage.setItem('mirror_token_expires', Date.now() + expiresIn * 1000);
  showMainPage();
}
```

### 10.7 Token 管理

```javascript
// 检查 token 是否有效
function isTokenValid() {
  const token = localStorage.getItem('mirror_token');
  const expires = localStorage.getItem('mirror_token_expires');
  
  if (!token || !expires) return false;
  return Date.now() < parseInt(expires);
}

// 清除 token
function clearToken() {
  localStorage.removeItem('mirror_token');
  localStorage.removeItem('mirror_token_expires');
}
```

### 10.8 设计总结

| 功能 | 第一版 | 未来扩展 |
|------|--------|----------|
| 多用户 | ❌ 单用户 | ✅ 预留 user_id |
| 注册 | ❌ 不需要 | ✅ 可加 |
| 登录 | 简单密码保护 | ✅ 可升级 |
| 密码保护 | 可选 | ✅ 必选 |
| 记住密码 | 7天免密 | ✅ 可调整 |
| 数据隔离 | 单用户 | ✅ user_id 字段 |

---

## 十一、待完善问题

- [ ] Docker Compose 配置细化（三服务编排、环境变量、健康检查）
- [ ] 安全性设计（API Key 加密存储、SQL 注入防护、gRPC TLS）
- [ ] 前端 UI 具体设计稿（原型已基本完成）
- [ ] 测试用例设计
- [ ] 性能优化（并发处理、缓存策略、gRPC 连接池）
- [ ] 搜索优化策略（元数据过滤已确定，混合检索/重排序待定）
- [ ] Python AI 服务监控（健康检查、模型加载状态）

---

## 十二、变更记录

| 日期 | 版本 | 变更内容 |
|------|------|----------|
| 2026-07-23 | v0.1 | 初始草案，确定核心功能和技术方案 |
| 2026-07-23 | v0.2 | 细化输入层设计：标签体系、长度限制、多事件拆分、纯情绪处理 |
| 2026-07-23 | v0.3 | 输入层定稿：三种数据来源、AI处理流程、每周/每月总结设计 |
| 2026-07-23 | v0.4 | 清洗层定稿：纯代码清洗、内容块模型、扩展性设计 |
| 2026-07-23 | v0.5 | 对话模块定稿：多轮对话、来源追溯、历史记录、意图识别 |
| 2026-07-23 | v0.6 | 标签体系扩展：情绪12个（支持多选）、内容类型8个、AI跳过无意义内容 |
| 2026-07-23 | v0.7 | 管道简化：去掉切片层，一条记录=一个chunk，输入层AI一次性完成拆分+打标签 |
| 2026-07-23 | v0.8 | 异常机制：原子性处理（AI分类+Embedding）、失败状态管理、重新尝试机制 |
| 2026-07-23 | v0.9 | 认证设计：简单密码保护（可选）、Token管理、预留多用户扩展 |
| 2026-08-04 | v1.0 | 架构调整：AI 推理拆分为独立 Python gRPC 服务，Java 负责业务+数据库，职责分离。同步更新架构图、模块划分、数据管道、异常机制。详见 [AI 服务层设计文档](2026-08-04-ai-service-design.md) |
| 2026-08-04 | v1.1 | 文档审查修正：标签统一英文存储、合并 user_settings 表、画像改用 SQL 统计、每日/写作灵感复用 Chat 服务、配置以 Java 数据库为准、Embedding 切换需重建向量、明确异步处理方式 |
