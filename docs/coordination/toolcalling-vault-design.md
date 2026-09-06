# Toolcalling + Vault Sprint 设计稿 v1.0

> 2026-09-05 总指挥与用户逐项对齐定稿。词典 sprint 收尾后开工的唯一蓝本。
> 哲学锚点：镜子不是通用助手，是**拿着你的证据和你对话的镜子**；词表/工具是加权不是替换，错了退化不是灾难。

## 0. 架构裁决（不可违背）

1. **工具执行权在 Java**（Python 无状态不连库）；LLM 决策在 Python
2. **方案 A Planner-Executor 先行**，方案 B 原生循环预留（执行层复用 A 的注册表）
3. 任意 OpenAI 兼容 provider 都能吃——不依赖 tools API；桩 LLM 可测全链路
4. 失败/超时/解析不出 → 跳过工具走现有 RAG，**零回归**
5. Python 无状态铁律、不动存量向量（裁决 #18）延续

## 1. PlanTools 流程

```
用户提问
  → B 调 Python 新 RPC PlanTools(question, glossary, context_hint, llm_config)
  → LLM 按 JSON 约定输出 {"tools":[{"tool":"search_records","args":{...}}]}（≤2 步）
  → B 执行工具（查库，纯 Java，AuditService 落 tool_calls）
  → 结果塞 ChatRequest.tool_results（proto 新字段）
  → 正常 Chat 流式，Python 把工具结果渲染成上下文块
PlanTools 失败/超时(3s)/空计划 → 跳过，走现有链路
```

- prompt 第 8 套 prompts/plan_tools.txt（工具注册表 + few-shot 2 例）
- meta SSE 事件带 `tools_used: ["search_records:12条"]` → F 展示工具轨迹芯片

## 2. 工具注册表（首批）

| 工具 | 签名 | 数据源 | 输出摘要 |
|---|---|---|---|
| search_records | {query?, days?, moods?, content_type?, limit≤20} | 四路检索 SQL | chunk 列表（record_id/title/quote/date） |
| get_stats | {days?=30} | stats mapper 复用 | 记录数/情绪分布/待办剩余 |
| get_profile | {} | 最新快照 | 五维+上次快照时间 |
| get_glossary | {term?} | user_terms | 词条解释列表 |
| compare_snapshots | {a_id?, b_id?}（缺省=最近两份） | 快照历史 | Δ 摘要（彩蛋） |
| save_item | {vault_item_id}（文件已上传后关联描述） | vault | 保存确认 |
| find_item | {query, type?, days?} | vault 三层检索 | 文件卡列表（引用强度分档） |
| recall_item | {vault_item_id} | vault+digest | 文件详情+引用摘录 |

- 写操作红线：上传=显式动作免确认；对话内删除/覆盖必须确认卡（二期 create_todo 复用此模式）
- AuditService：每次执行落 tool_calls（tool/args/summary/success/latency）——论文三档消融+频率/成功率/延迟图表素材

## 3. Vault 规格（C 组定稿）

### 3.1 存储与限制（全配置化）
- **Postgres BYTEA 直存**：vault_items 元数据 + vault_blobs 本体分表（列表永不拉 blob）
- 单文件 **20MB**、每用户总配额 **500MB**、**SHA-256 去重**（同用户同内容拒绝+提示）——全部进 config.yml
- 类型白名单：pdf/docx/txt/md/csv/jpg/png/webp/gif/mp3/wav/m4a；**exe/zip/svg/视频一律拒**（svg 防 XSS）
- **magic bytes 校验**（不信扩展名）；original_name 清洗（路径符号/控制字符/255 截断）
- 非图片类下载强制 `Content-Disposition: attachment`
- **用户隔离**：全接口 JWT + ownership 校验，非本人资源 404（不暴露存在性）
- 硬删除 + 确认交互；删除时级联清关联 chunks（向量库无孤儿）
- deleted_at 留审计位（删除瞬间记录，非软删恢复）

### 3.2 Key 三层渐进生成（随机文件名方案）
1. **元数据榨取**（上传瞬间，零 LLM）：PDF Title+首页文本 / docx 属性+首段 / mp3 ID3 / 图片 EXIF / txt 首行
2. **LLM 自动命名**（消化管道顺路）：description + category + 词典候选词条（vault×词典咬合点）
3. **用户补正**：上传卡可空描述框（placeholder"以后想怎么找到它？"）+ 消化回执三键（对的/改一改/删了）+ 资产页常改常删
- 兜底：精确层同时匹配 original_name+description+消化关键词；零 key 检索按时间+类型（"昨天传的图片"）

### 3.3 三档消化（2026-09-06 修订：确认门禁版）
- 文本/PDF/docx → 全消化：抽文本→现有 chunk 管道（挂 vault_item_id），digest_status=done；超 5 万 token 只索引前 N 章并告知
- 图片 → 半消化：用户描述+EXIF 进 embedding；视觉模型检测启用留 future work
- 音视频 → 零消化：只存元数据卡片（ID3/时长），digest_status=skipped
- 管道隔离：消化失败只影响该文件状态，不炸主服务

### 3.3b 确认门禁（2026-09-06 用户定稿：确认是 embed 的准入条件）
```
上传 → 自动提取（元数据榨取 + LLM key/description 尝试）
 ├─ 成功 → 回执卡展示 key+description → 用户确认/修改 → 才生成 key chunk 进 embedding
 └─ 失败 → 回执卡"未能识别" → key/description 留空待填 → 填完确认 → 才进 embedding
未确认：可下载预览（保管完整），检索不到（用户背书过的才进记忆）
```
- digest_status 五态：pending（排队）→ extracted（提取完待确认）→ confirmed（已确认+已 embed）→ skipped → failed
- 确认即触发 embed（异步，toast"已可检索"）——不进定时任务，显式动作即时反馈
- 资产页低信息置顶区 = 补确认入口；未确认文件灰标"未确认 · 检索不到"
- 哲学统一：与记录审核/词典确认同构（机器猜的不直接用）

### 3.3c key-embed（2026-09-06 用户定稿：Q3 裁决）
- 文件 key chunk：embed 文本 = key + description + 文件类型拼合（"RAG 毕业论文：一份关于 RAG 检索的开题报告，PDF 文档"），存为 contentType='note' 的特殊 chunk 挂 vault_item_id——天然进通用检索，零新链路
- 命中行为：sources 正常引用 + vault_refs 文件卡；**不把文件全文喂进对话上下文**（内容问答走 recall_item 显式拉摘要）
- 删除文件时 key chunk 随既有级联清理走

### 3.3d 删除防误删三层（Q2 裁决：硬删保留）
1. 对话内删除：内联确认文案带文件名
2. 资产页删除：输入文件名后四位才能点删除
3. 删除 toast 5 秒撤销窗（前端暂存，真删推迟 5 秒执行）

### 3.4 检索三层漏斗（find_item）
① 精确：词典 term/别名 + 文件名 ILIKE → ② 语义：description+摘要 embedding → ③ 全文：消化 chunks 向量

## 4. 前端（F，着重项）

### 4.1 对话内文件卡（用户点名的重点）
- **强引用**（回答基于文件内容）：完整卡——类型 SVG 图标+显示名+大小/日期/消化状态+AI 引用摘录+[预览][下载]
- **弱引用**：行内芯片 `[PDF] 开题报告` 可点开
- **模糊提及**（"昨天传的图片"）：芯片展示，用户点开确认
- 引用标记：AI 输出 `[n]` 复用现有 sources 语法，Java 解析后 SSE `vault_refs` 事件（字段：n/vault_item_id/display_name/file_type/size/digest_status/quote）
- **边界**：文件已删→芯片置灰"文件已删除"不可点；消化中→"索引中…"可下载不可问答；同文件多引用→同气泡单卡
- 预览：图片/PDF 内嵌（/preview 流）、音视频播放器、docx 降级"下载查看"

### 4.2 上传卡（当场可改）
```
[PDF图标] 毕业论文-开题报告.pdf        ← 元数据榨取名（非原始文件名）
AI 已识别：开题报告文档 · 学习资料
一句话提示（可空）：[____]
[就这样存] [改个名]
```

### 4.3 消化回执卡（三键纠错，与词典确认同心智）
```
✓ 已消化：毕业论文-开题报告
我理解它是：RAG 检索方向的毕设开题报告 · 已提取 12 段可检索
[对的] [改一改] [不是这个，删了]
```

### 4.4 "我的资产"页
- 时间倒序文件卡列表（图标/LLM 起的名可改/类型/大小/日期/描述/消化状态透明）
- 类型筛选（文档/图片/音频）+ 配额可视化条（128MB/500MB）
- **低信息文件置顶区**（"未能识别，请描述一下"）——认知边界透明（B2）在 vault 的应用
- 删除二次确认弹窗

### 4.5 工具轨迹芯片（E6）
气泡上方 `查了 9 月记录 · 12 条`（风格同 sources），meta.tools_used 驱动

## 5. 创新场景接线（B 组首批三件）

### B1 事实核查 verify_claim
- PlanTools 判定用户陈述为"可核查自我评价"→ 计划里自动含 search_records（本周窗口）
- prompt 约束：**宁可少核查，不可错核查**；核查输出必须带记录引用
### B2 认知边界 coverage_check
- 新工具 get_coverage {query}：返回该主题在记录中的时间覆盖度（最早/最近提及、记录数、空白期）
- PlanTools 识别"我什么时候开始…/我了解你什么"类问题 → 注入 coverage 结果 → 回答声明"我知道多少、从哪天开始知道"
### B4 快照对谈 snapshot_persona（彩蛋）
- PlanTools 识别"用 8 月的我回答" → ChatRequest.tool_results 带 monthly 快照 persona 块
- prompt 指令：以"8 月的你"口吻回答；不新增工具，快照数据复用 get_profile 变体

## 6. proto 新增（shared-protocol 登记，B/AI 同步）

```protobuf
message ToolSpec { string name = 1; string description = 2; string args_schema = 3; }
message ToolResult { string tool = 1; string summary = 2; string payload_json = 3; bool success = 4; }

message PlanToolsRequest {
  string question = 1;
  repeated GlossaryTerm glossary = 2;
  repeated ToolSpec tools = 3;      // Java 侧注册表快照（Python 不硬编码工具清单）
  LlmConfig llm_config = 4;
}
message PlanToolsReply { repeated PlannedCall calls = 1; }
message PlannedCall { string tool = 1; string args_json = 2; }

// ChatRequest 加：repeated ToolResult tool_results = N;
rpc PlanTools(PlanToolsRequest) returns (PlanToolsReply);   // 并入 MirrorChat 服务
```

## 7. 分工与顺序（词典收尾后）

| 序 | Agent | 任务 | 依赖 |
|---|---|---|---|
| 0 | 总指挥代 DB | vault/tool_calls 建表 + SQL 同步 | ✅ 已完成（af60c45） |
| 1 | AI | PlanTools RPC + 第 8 套 prompt + tool_results 渲染 + verify_claim/coverage prompt 约束 | proto 登记 |
| 2 | B | VaultService（CRUD+三层检索+配额+去重+Digest 管道）+ ToolRegistry/Executor/AuditService + 8 工具实现 + vault REST + SSE vault_refs | DB ✅ |
| 3 | F | 对话文件卡三档 + 上传卡 + 消化回执 + 资产页 + 工具轨迹芯片 + 附件上传入口 | 2 接口（mock 先行可并行） |
| 4 | 联调 | otaku_it 传 PDF→问论文→文件卡+引用链路；断网/超时降级回归 | 全部 |

**并行策略**：B 与 F 可并行（F mock 先行）；AI 等词典轮交付后立即开工（避免同仓冲突）。
**提交纪律**：每 Agent 交付→总指挥验收（build/测试独立复跑）→一步一提交 ai 分支（Co-Authored-By）。

## 8. 验收口径

- 上传真 PDF（含随机文件名）→ 元数据榨取名 → 消化回执 → 问"我论文咋样了" → 回答带文件卡+原文摘录 → 下载原文件
- "我上个月都在焦虑什么" → 工具轨迹芯片 + 汇总型回答（非 RAG 碎片）
- "用 8 月的我回答…" → persona 回答
- 断 Python/超 LLM → 对话照常（无工具轨迹）
- tool_calls 表有审计记录；配额/去重/类型白名单/路径穿越/跨用户 404 安全用例全过
