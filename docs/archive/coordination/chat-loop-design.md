# 对话 Agent 循环改造设计稿 v1.0

> 2026-09-20 与用户对齐定稿。落地 `toolcalling-vault-design.md` 裁决 0.2 预留的「原生循环」。
> 哲学锚点不变：镜子不是通用助手，是**拿着你的证据和你对话的镜子**。
> 本轮新增一条：**镜子的目的是帮用户了解自己，所以有证据支撑时可以给建议，且要有人情味。**

## 0. 架构裁决

延续 `toolcalling-vault-design.md` §0 全部五条，其中 0.2 具体化：

1. **工具执行权在 Java**（Python 无状态不连库）；LLM 决策在 Python —— 不变
2. **循环由 Java 驱动**（方案 1）。Python 每次只做「看着已有结果决定下一步」的单次决策，
   循环状态（步数、已执行结果、耗时预算）全部在 Java。
   **否决方案 2**（Python 内循环 + Java 暴露 `ExecuteTool` gRPC server）：需要 Java 从纯客户端
   变成服务端，且 Python 传 userId 由 Java 无条件采信 —— 这个越权信任边界不为循环而开。
3. **不依赖 provider 的 tools API**（仍走 JSON 约定输出）—— 不变，任意 OpenAI 兼容端点可跑
4. **零回归**：循环任一步失败/超时/解析不出 → 拿**已经拿到的结果**直接进 Chat；
   一步都没成功 → 退化成今天的纯 RAG 链路；配置开关可一键切回旧 `PlanTools`
5. **Python 无状态铁律** —— 不变（每次 `PlanNextStep` 请求自带全部上下文，服务端不存任何东西）

## 1. 循环流程

```
用户提问
  → ExtractIntent（不变）
  → 四路检索（不变，阈值 0.35 不动）
  → ┌─ 循环 step = 1..N ───────────────────────────────────┐
     │ B 调 PlanNextStep(question, history, previous_results,│
     │                   step, max_steps, has_retrieval)     │
     │   ← stream PlanStepChunk{thinking}  ── 逐块透传 SSE thinking（实时可见）
     │   ← PlanStepChunk{final, calls, done}                 │
     │ done=true 或 calls 空 → 退出循环                      │
     │ 否则 Java 执行 calls → AuditService 落 tool_calls      │
     │   → 累积 previous_results                             │
     │   → 发 meta{tools_used: 累积列表}（芯片实时增长）      │
     └───────────────────────────────────────────────────────┘
  → Chat(question, history, chunks, 全部 tool_results) 流式出答案（不变）
```

与今天的差别只有一处，但是本质的：**"材料够不够"由模型每一步自己判断，
而不是 `ChatServiceImpl:127` 在检索完那一刻一次性判死。**

~~顺带删掉一处硬编码：`ToolOrchestrator:98-103` 给 `recall_item` 手动接 `find_item` 命中 id 的补丁——循环里模型看得见上一步结果，自己填 id。~~
**2026-09-21 联调推翻**：prompt 明写"照抄 vault_item_id"，模型第 2 步仍只给 `{"query":"架构"}`，读正文失败。循环里已恢复 id 自动接续（见 `chat-loop-integration.md` §10 #9）。

## 2. 为什么今天是黑屏（用户体感的真因）

用户反馈「等到推理完了才能看到，所以体验不好」。查证结果比"慢"更具体：

`services/plan_service.py:144` 用的是 `llm.json_task()`，而该方法（`llm/openai_llm.py:121`）
**非流式 + 显式关思考**。代码注释自己写着：

> `json_task`：关思考（工具规划是 JSON 决策，不需要思维链）。实测 18s 级别的耗时主要烧在
> 思考 token 上，且它在"用户看到第一个字之前"串行执行。

所以那 18~20 秒**不是"思考没推给前端"，是根本没有可推的东西**：一次不流式的调用，
要么没返回、要么全返回。前端只能干等。

**改法**：规划步从 `json_task()` 换成已有的 `llm.chat_stream()`（`llm/openai_llm.py:73`），
它已经 yield `("thinking", text)` / `("content", text)` 二元组，Anthropic 的 `thinking_delta`
和 OpenAI 系的 `reasoning_content` 都已接好。思考重新打开，边想边推。

**前端零改动**，三件东西已经就位：
| 已有件 | 位置 | 作用 |
|---|---|---|
| `ThinkingPanel.vue` | `F/src/components/molecules/` | 折叠式思考面板，流式累积，done 时自动收起 |
| `thinking` 事件累加 | `F/src/stores/chat.js:268-271` | `aiMsg.thinking += payload.content` |
| `meta.tools_used` 覆盖式赋值 | `F/src/stores/chat.js:258` | 每步发**累积**列表即可，芯片自然增长 |

即：把规划阶段的思考流接进现有 `thinking` 通道，等待就从"黑屏 20s"变成"看它边想边查"。
SSE 事件契约（`meta`/`delta`/`sources`/`done`/`error`/`thinking`/`vault_refs`）一个字不改。

## 3. proto 改动（B/AI 双仓同步登记）

```protobuf
// 新增：流式「下一步决策」。旧 PlanTools 保留不删（配置开关可切回，零回归）
rpc PlanNextStep(PlanNextStepRequest) returns (stream PlanStepChunk);

message PlanNextStepRequest {
  string question = 1;
  repeated GlossaryTerm glossary = 2;
  repeated ToolSpec tools = 3;
  LlmConfig llm_config = 4;
  repeated ChatMessage history = 5;         // 新：多轮指代消解（"我焦虑怎么办"接得住上文）
  repeated ToolResult previous_results = 6; // 新：已执行步骤的结果（循环的核心输入）
  int32 step = 7;                           // 新：第几步（1-based）
  int32 max_steps = 8;                      // 新：预算，prompt 告知"还剩几步"
  bool has_retrieval = 9;                   // 新：RAG 是否有命中（让模型知道检索空了）
}

message PlanStepChunk {
  optional string thinking = 1;   // 思考增量 → B 透传 SSE thinking
  repeated PlannedCall calls = 2; // 仅终帧有效
  bool done = 3;                  // 仅终帧有效：true = 材料够了，别再循环
  bool final = 4;                 // 本块是终帧（calls/done 才可读）
}
```

`ChatMessage` / `PlannedCall` / `ToolResult` / `ToolSpec` 全部复用，不改。
**注意**：AI 仓 `upload/` 是 git 跟踪的逐字节部署镜像（`upload/services`、`upload/prompts`
与根目录当前完全一致），AI 侧每处改动都要落两遍。

## 4. Java 侧改动

### 4.1 `ToolOrchestrator`
新增 `loopAndExecute(userId, sessionId, question, history, hasRetrieval, StepListener)`，
`StepListener` 回调让 `ChatServiceImpl` 即时推 SSE。旧 `planAndExecute()` 保留（开关回退用）。

**终止条件（任一命中即停，缺一不可）**：
1. 终帧 `done == true`——**2026-09-21 联调后修订**：若同帧带 calls，先执行完这最后一批再收尾（原先丢弃 calls，模型只能再花一整轮 LLM 调用说"够了"，实测 ~30s）
2. 终帧 `calls` 为空
3. `step > mirror.chat.max-loop-steps`（默认 4）
4. 循环累计耗时 > `mirror.chat.loop-budget-ms`（默认 60000）
5. **同工具 + 同参数指纹重复** → 立即中断（防原地打转烧钱）
6. 单步流式中断/解析失败 → 不重试，带着已有结果退出循环

**保留一处窄口径兜底**（2026-09-21 追加裁定）：循环<b>正常收束</b>（终止条件 1 / 2）时，若本轮
有成功的 `find_item`、且<b>全程</b>没执行过 `recall_item`、且取得到 `vault_item_id`、且预算未超，
则补执行一次 `recall_item` 并落审计（args 带 `auto_backstop:true`、summary 前缀 `[auto-backstop] `），
补完即结束不回循环。理由：旧 `planAndExecute` 的同名兜底注释记的是**实测结论**「模型经常只规划
`find_item`」，用户于是拿到"我只有文件名和元信息"；循环把它交还给模型是用理论换实测，属行为回退。
步数/预算/打转/异常四种退出**都不补**（那些情况已跑飞）。将来实测确认模型会自己续则可删。

### 4.2 `ChatServiceImpl`
- `:127` 硬兜底：判断条件从"这次检索空且工具无料"改为"**循环跑完仍无任何材料**"
- **`HISTORY_ROUNDS` 保持 3，不改。** `:65-66` 有明确理由：「早期放到 20 轮（实际送 40 条）
  反而有害：模型会顺着上文的结论和措辞继续说，上一轮跑偏的因果会被'接着上文'放大；同时挤占
  prompt token」。`655e638`→`27e2d26` 的 20→3 是**有意回退**，不是误改。
- 改为**两个历史窗口分开配**：
  | 注入点 | 用途 | 窗口 | 理由 |
  |---|---|---|---|
  | `ChatRequest.history` | 生成答案 | `HISTORY_ROUNDS = 3`（不动） | 长窗口会让模型顺着上文措辞跑偏 |
  | `PlanNextStepRequest.history` | 规划决策 | `PLAN_HISTORY_ROUNDS = 6`（新） | 规划器输出 JSON 不输出散文，"顺着措辞说"风险不成立；但指代消解需要上文 |
- `finishWithFallback()` 兜底文案分三档（见 §6）

### 4.3 三层超时对齐（**现在是打架的，必须先修**）
| 层 | 现值 | 位置 | 改成 |
|---|---|---|---|
| `SseEmitter` | 120s | `MirrorController.java:226` | ≥ 循环预算 + Chat 流 + 余量 |
| `plan-tools-timeout-ms` | **135s** | `application.yml:103` | 降为**单步**上限，必须 < SSE |
| `chatStream` deadline | 60s | `AiGrpcClient.java:398` | 保持 |

现状 135s > 120s：单次规划真跑满，SSE 连接会先断。这是循环化之前就存在的隐患，
循环会把它放大成常态。

## 5. prompt 改动（AI 侧两个）

### 5.1 `prompts/plan_tools.txt` → 循环版
- **删掉规则 1 里的「情绪安慰」**。现在原文是「闲聊、追问、**情绪安慰**、对上文的确认 →
  必须返回空计划」——这就是「我最近特别焦虑怎么办」拿不到任何工具数据的直接原因，
  规划器不是判断失误，是在严格执行指令。
- 新增循环语境渲染位：`{previous_results}` / `{step}` / `{max_steps}` / `{has_retrieval}`
- 新增**自评环节**（这就是"自己反问自己"落到 prompt 上的样子）：每步先判断
  "现有材料够不够回答这个问题"，够了输出 `{"calls":[],"done":true}`，不够才出下一步
- 新增规则（与旧规则 1 方向相反）：**检索为空 + 情绪/求助类问题 → 必须先 `get_stats` 摸底**，
  纯 SQL 聚合，只要用户写过记录必有数据；拿到情绪分布后再决定要不要 `search_records`
- few-shot 补两个**多步**例子（含"第 2 步看着第 1 步结果收敛"的样例）
- 保留「宁可少规划，不可错规划」的总基调和"注册表外工具名禁止编造"

### 5.2 `prompts/chat.txt` → 人情味 + 有证据的建议
用户定调：**镜子的设计目的就是帮人了解自己，适当给建议是要的，尽量有人情味。**
- 红线「**不做人生导师式说教（"你应该…"、"建议你…"）**」从无条件改为**有条件**：
  有记录/统计证据支撑时，允许给具体的、指得回记录的建议；**无证据时仍然不许泛泛说教**
- 「禁止无证据共情」**保留**（有 mood 证据时，共情本身就是有证据的，不冲突）
- 两条硬红线**不动**：不许编日记内容、不许把不同主题的记录串成因果
- 长度「3-6 句」放宽到 3-8 句（给建议比报事实费字）

## 6. 兜底（保留项）

循环吃掉了原"第一批"的方案 1（会话账本）和方案 2（三级抢救）——模型自己会用上文、
自己会去摸底，这两项不用再写。仍需要的两项：

1. **文案分三档**，替换恒定的 `FALLBACK_NO_RECORDS = "没有找到相关记录"`：
   零记录新用户 / 有记录但没匹配上 / 情绪倾诉类
2. **兜底消息不进历史上下文**。建议加 `conversation_history.is_fallback` 列精准跳过
   （文案匹配太脆——用户自己说这句话会被误跳）。注意 `schema.sql` 是
   `CREATE TABLE IF NOT EXISTS`，已有库不会自动加列，需手动一条 `ALTER TABLE`。
   不留迁移脚本文件（`10aa044` 刚清掉 `migration-v2.sql`，尊重该口径）。

## 7. 不动的东西（红线）

1. **`rag-max-cosine-distance: 0.35` 不动** —— 阈值过滤是"宁可不答不乱答"的有意设计
2. **`record.content` / 原文展示不动** —— 项目红线
3. **SSE 事件契约不动** —— `meta`/`delta`/`sources`/`done`/`error`/`thinking`/`vault_refs`
4. **F 侧零改动** —— 只需回归验证
5. **不动存量向量**（裁决 #18 延续）

## 8. 分工与顺序

| 序 | 侧 | 任务 | 依赖 |
|---|---|---|---|
| 0 | B | §4.3 三层超时对齐（`HISTORY_ROUNDS` 经核实为有意设计，不改） | 无，独立小改可先合 |
| 1 | 双仓 | §3 proto 登记同步（B `src/main/proto` + AI 根目录与 `upload/` 双份） | 无 |
| 2 | AI | `PlanNextStep` 流式 servicer（`chat_stream` 替 `json_task`）+ `plan_tools.txt` 重写 + `chat.txt` 人情味改造，全部落两遍（根 + `upload/`） | 1 |
| 3 | B | `ToolOrchestrator` 循环 + SSE 透传 + 兜底分档 + `is_fallback` 列 + 单测 | 1、2 |
| 4 | F | 零改动，回归验证思考面板与芯片实时性 | 3 |
| 5 | 联调 | §9 全量 | 全部 |

## 9. 验收口径

1. 同会话连续两问（先问记录，再问"我最近特别焦虑怎么办"）→ 第 2 问**有工具芯片、
   思考面板实时滚动（不再黑屏等待）、回答带证据且给出有人情味的建议**
2. 全新零记录用户问同样问题 → 情绪引导类文案，不是「没有找到相关记录」
3. "我上周写的开题报告在哪"且确实没有任何材料 → **仍然兜底**（防过度修复）
4. 兜底文案不出现在后续轮次的历史上下文里
5. 循环打转 / 超步数 / 超预算 → 带着已有材料照常出答案，不报错、不空屏
6. 配置开关关掉循环 → 行为回到今天的 `PlanTools` 单次规划（可回滚）
7. `tool_calls` 表每一步都有审计记录（论文的步数/成功率/延迟素材白捡）

## 10. 环境备注

- `MuMirrorBApplicationTests.contextLoads` 有**两道环境前置**，缺任一即失败（均属既有环境依赖，
  与本轮改动无关）：
  1. **`jwt.secret` 解析不到**，报 `PlaceholderResolutionException: Could not resolve placeholder
     'jwt.secret'`，Spring 在 `jwtUtils` 就炸，**根本走不到数据源**。
     根因不是"靠环境变量注入"，而是 **profile 没激活**：默认 profile 是 `prod`
     （`application.yml:15` `active: prod`），而仓库里**只有 `application.yml` 和
     `application-dev.yml`，没有 `application-prod.yml`** —— `jwt.secret` 实际定义在
     `application-dev.yml:22`，生产则由 `JWT_SECRET` 环境变量提供
     （`deploy/env/backend.env.example:20-22`，注释写着"prod 里默认是空串"）。
     **本地解法：加 `-Dspring.profiles.active=dev`**（dev profile 同时给齐 jwt.secret、
     数据源和 AI 地址 10003），或者 export `JWT_SECRET`
  2. **PostgreSQL `localhost:5432`**（激活 dev profile 之后才会走到这一步）
  2026-09-21 实测澄清：本机缺的是第 ①。这条先后错了两次——最早照抄旧任务书只写
  "需要 PostgreSQL"，之后改成"`jwt.secret` 靠环境变量注入"仍不准确，以上为核实后的版本
- **surefire 坑**：`~/.m2/repository/org/apache/maven/surefire/*/3.5.6/` 里只有 `.pom` 没有 `.jar` 时，
  `.\mvnw.cmd -o test` 会在插件解析阶段直接失败（`PluginResolutionException: Cannot access central
  ... in offline mode`），一个用例都跑不到。解法：**联网跑一次** `.\mvnw.cmd test` 把 jar 拉全，
  之后 `-o` 正常。别误判成代码问题
- 单测：`.\mvnw.cmd -o test -Dtest=ChatRetrievalTest,ChatLoopTest`
  （多个类用**逗号**分隔；用 `+` 会报 "No tests matching pattern"）
- 现有兜底用例需同步调整：`ChatRetrievalTest` 的 `emptyRetrieval_fallbackPersisted:215`、
  `emptyRetrieval_withUsableToolData_stillCallsLlm:229`、
  `emptyRetrieval_withEmptyToolPayload_fallsBack:255`

## 11. 需手动执行的 DDL（`is_fallback` 列）

§6.2 的 `conversation_history.is_fallback` 已写进 `src/main/resources/db/schema.sql` 的建表语句，
但那条是 `CREATE TABLE IF NOT EXISTS` —— **已有库不会自动加列**。不新留迁移脚本文件
（尊重 `10aa044` 清掉 `migration-v2.sql` 的口径），请在已有数据库上手动执行一次：

```sql
ALTER TABLE conversation_history ADD COLUMN IF NOT EXISTS is_fallback BOOLEAN NOT NULL DEFAULT FALSE;
```

幂等（`IF NOT EXISTS` + 有默认值），存量行自动补 `false`（即"存量兜底消息仍会进上下文"，
只影响执行这条 DDL 之前落库的历史，不再回填）。未执行时表现：插入 assistant 消息会因
未知列报错 —— 也就是说这条 DDL 是本轮改动的**硬前置**，不是可选优化。

## 12. 联调后修订（2026-09-21）

端到端联调发现并修复 10 个问题，完整记录、实测延迟与遗留见 `chat-loop-integration.md` §0 / §6 / §9 / §10。
与本设计稿直接相关的三处修订：规划器思考预算单独配置（实测定为 0）；`done=true` 带 calls 时执行完即收尾；循环内恢复 `recall_item` 的 id 自动接续。
