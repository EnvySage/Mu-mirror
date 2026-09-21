# 对话 Agent 循环 · 联调手册

> 2026-09-21。配套 `chat-loop-design.md`（设计稿）。
> **2026-09-21 已在测试库完成一轮端到端联调**（远程测试库 + 本机 Python/B/F，账号 `xxx`，模型 mimo-v2.5）。
> 联调中发现并修复 11 个问题（含 1 个前端问题），详见 §10；结论与遗留见 §0 / §9。换环境复测照 §1~§5 走。

## 0. 当前状态（2026-09-21 联调后）

| 项 | 状态 |
|---|---|
| 验收 1 同会话第 2 问不再拒答 | ✅ 检索命中 0 条时，循环一步并行查 `get_stats` + `search_records(moods=[anxious])`，回答引用真实记录原文并给出有证据的建议（原文见 §10 末） |
| 验收 2 零记录用户走引导文案 | ⚠️ 单测通过；端到端需要"配好模型但零记录"的账号，见 §9 |
| 验收 3 纯查资料不编造 | ✅ 问不存在的周报 → "这个我这边没记到……文件清单里也没有周报的踪迹" |
| 验收 4 兜底不进历史 | ✅ 兜底消息 `is_fallback=true`，用户消息与正常回答均为 `false` |
| 验收 5 跑飞不空屏 | ✅ 实测单步撞 60s 超时时，带着已有材料退出并走兜底档文案，无报错 |
| 验收 6 回滚闸 | ✅ `chat-loop-enabled: false` → AI 侧走旧 `PlanTools` + 旧 prompt，复现旧行为（计划 0 步） |
| 验收 7 审计完整 | ✅ 每次工具调用落 `tool_calls`（参数/耗时/结果），联调当日 20 条 |
| 验收 8 读文件正文 | ✅ 机制通：`find_item → recall_item`（自动接 id）→ 正文摘录进回答上下文；摘录选段质量见 §9 |
| 思考实时上屏 | ✅ **已在真实浏览器验证**（无头 Edge 走 5173 页面，每 0.4s 采样）：思考面板第 13.8s 出字并持续增长 100 次，回答在 4s 内逐步长出。修复前对照：前 53s 界面空白，回答一次性出现。注意：最初只验证了 SSE 层（3.6~7.9s 首帧），浏览器里并不生效，根因见 §10 #11 |
| B 侧单测 | ✅ `ToolsTest` + `ChatLoopTest` + `ChatRetrievalTest` 55 例全过 |
| AI 侧 pytest | ✅ 313 通过；7 失败是 `test_round9.py` 既有 `import httpx2` 拼写错误 |
| SSE 契约 | ✅ B 发出与 F 处理的事件名集合完全一致（7 个），契约未改 |
| F 改动 | ⚠️ 不再是"零改动"：修了 1 行既有 bug（§10 #11），否则流式在浏览器里完全不生效 |

**最大的遗留是延迟**：从提问到答案第一个字要 21~86s，主要耗在 mimo 的推理上（§6）。

## 1. 环境前置

| # | 组件 | 要求 |
|---|---|---|
| 1 | PostgreSQL | `localhost:5432`，库 `mu_mirror`，账号 `postgres/postgres`（`application-dev.yml:11-13`） |
| 2 | Python AI 服务 | 监听 **10003**（不是默认的 50051，见 §2 坑 A） |
| 3 | B 服务 | 端口 9050（`application.yml:3`），**必须用 dev profile**（见 §2 坑 B） |
| 4 | LLM key | 配在用户的模型配置里（走 `ChatRequest.llm_config` 下发，B 不存 key） |
| 5 | 前端 F | **不需要任何改动**，直接起现有代码 |

## 2. 三个必踩的坑（都已核实，不是猜测）

### 坑 A：Python 默认端口和 B 期望的端口不一致
- AI 仓 `config.yml:5` → `port: 50051`
- AI 仓 `upload/config.yml:5` → `port: 10003`
- B 侧 `application-dev.yml:44` → `address: 127.0.0.1:10003`

**直接从仓库根目录起 Python，它会听 50051，而 B 打 10003，连不上，且表现为"对话没有工具轨迹"（planNextStep 失败 → 零回归降级纯 RAG），不会报错。**
解决：起 Python 前把根目录 `config.yml` 的 port 改成 10003，或用 `upload/config.yml`。

> 附带结论：`upload/config.yml` 才是与部署一致的那份（10003）。所以 **`upload/` 不是死代码**，
> 尽管 `deploy/ai-release.sh:15` 的 `SRC="${SRC:-.}"` 默认指向根目录——真实的 Jenkins job
> 很可能在仓库外传了 `SRC=upload`。**在搞清楚之前不要删 `upload/`，AI 侧改动继续双写。**
> 已在 `generate_proto.py` 末尾加了 stub 自动同步，proto 那部分不会再漂。

### 坑 B：`contextLoads` 失败与 PostgreSQL 无关
默认 profile 是 `prod`（`application.yml:14`），但**仓库里没有 `application-prod.yml`**（只有 `application.yml` 和 `application-dev.yml`），所以 `jwt.secret` 解析不到，Spring 上下文在**走到数据源之前**就挂了：
```
PlaceholderResolutionException: Could not resolve placeholder 'jwt.secret'
```
解决：本地跑加 `-Dspring.profiles.active=dev`（dev profile 里有 `jwt.secret`、数据源、以及 AI 地址 10003）。
> 设计稿 §10 原先写"需要本地 PostgreSQL"，不准确，已更正。

### 坑 C：离线跑测试可能起不来
本机 `~/.m2` 里 surefire 3.5.6 可能只有 `.pom` 没有 `.jar`，`./mvnw.cmd -o test` 会直接
`PluginResolutionException`。**联网跑一次**把 jar 拉下来，之后 `-o` 就正常。
另：`-Dtest` 指定多个类要用**逗号**，用 `+` 会报 "No tests matching pattern"。

## 3. 硬前置：手工执行 DDL

`schema.sql` 是 `CREATE TABLE IF NOT EXISTS`，已有库不会自动加列。**不执行的话所有 assistant
消息落库都会报未知列，对话直接不可用**——这是硬前置，不是可选优化。

```sql
ALTER TABLE conversation_history
  ADD COLUMN IF NOT EXISTS is_fallback BOOLEAN NOT NULL DEFAULT FALSE;
```

存量行补 `false`，**不回填**——所以库里已有的旧兜底消息仍会进上下文，验证验收 4 请用新会话。

## 4. 启动顺序

```bash
# 1. PostgreSQL 起好，执行 §3 的 ALTER TABLE

# 2. Python AI 服务（先改 config.yml 的 port 为 10003，见坑 A）
cd Mu-mirror-AI && ./venv/Scripts/python.exe server.py     # 确认日志打出监听 10003

# 3. B 服务（必须 dev profile，见坑 B）
cd Mu-mirror-B && ./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev

# 4. F 前端（零改动）
cd Mu-mirror-F && npm run dev
```

确认循环开关是开的：`application.yml` 的 `vault.chat-loop-enabled: true`（默认 true）。

## 5. 验收逐条

### 验收 1（核心）：同会话第 2 问不再拒答
1. 新建会话，第 1 问：**「你能看到我最近几天的记录吗，你知道我最近几天的情绪变化吗」**
2. 同一会话第 2 问：**「我感觉我最近特别焦虑怎么办」**

**该看到**：
- 气泡上方有工具轨迹芯片，且**随循环推进逐步增多**（不是一次性全出）
- 思考面板（`ThinkingPanel`）**在等待期间就在滚动**，不是答完才出现
- 回答基于统计/记录给出内容，**并且带有具体建议**（`chat.txt` 已放开"有证据可给建议"）
- 回答里的建议**带 `[n]` 引用**

**算失败**：仍然出现「没有找到相关记录」；或思考面板全程空白直到答案出现（说明 thinking 没逐块透传）；或回答只报事实不给任何建议（说明 `chat.txt` 改动没生效）。

### 验收 2：全新零记录用户
新注册一个用户，一条记录都不写，直接问「我感觉我最近特别焦虑怎么办」。
**该看到**：情绪引导类文案（大意是"你这边还是空的，我手上一条记录都没有…要不先随手写一条"）。
**算失败**：出现「没有找到相关记录」。

### 验收 3：防过度修复
有记录的用户，问一个**确实查不到**的资料类问题，例如「我上周写的开题报告在哪」（确保 vault 里真没有）。
**该看到**：仍然兜底，文案是"你是写过东西的，但这一问我这边没对上…"这一档。
**算失败**：开始编造一份不存在的文档。

### 验收 4：兜底不污染历史
**用新会话**（见 §3 说明）。先制造一次兜底（用验收 3 那个问法），紧接着在同会话问一个正常能查到的问题。
**该看到**：第 2 问正常回答，不受上一轮兜底影响。
**怎么确认**：`select role, is_fallback, left(content,30) from conversation_history where session_id='…' order by created_at;` 兜底那行 `is_fallback` 应为 `true`。
注意：`FALLBACK_NO_ANSWER`（"暂时无法回答"，AI 流失败时的兜底）**也会**打 `is_fallback=true`，这是有意的。

### 验收 5：循环跑飞也不空屏
难以自然触发，建议临时把 `vault.max-loop-steps` 调成 1、或 `loop-budget-ms` 调成 5000 重启，再问验收 1 的第 2 问。
**该看到**：照常出答案（用已经拿到的那部分材料），不报错、不空屏。

### 验收 6：回滚闸真的能回滚
改 `vault.chat-loop-enabled: false` 重启。
**该看到**：行为回到改造前——单次规划、`情绪安慰` 类问题重新走兜底。这是**预期**的，
因为回滚路径用的是 `plan_tools_single.txt`（与改造前逐字节一致，那句「情绪安慰」故意留着）。
**算失败**：`false` 之后行为跟 `true` 一样（说明开关没接上），或者报错起不来。

### 验收 7：审计完整
```sql
select tool, result_summary, success, latency_ms, args, created_at
from tool_calls where session_id='…' order by created_at;
```
**该看到**：循环每一步都有记录（含失败步、未知工具步）。这是论文消融/延迟图表的素材来源。

### 验收 8（新增）：`find_item` 兜底
问一个需要读文件正文的问题（如「我上传的设计文档里架构部分怎么写的」）。
**该看到**：若模型全程只调了 `find_item` 没调 `recall_item`，循环正常结束时会**自动补一次
`recall_item`**，审计里能看出这一刀是兜底补的。
背景：旧路径注释记着「实测模型经常只规划 find_item」，循环不能把这个实测结论丢掉。

## 6. 延迟实测（2026-09-21，mimo-v2.5，测试库远程隧道）

| 指标 | 实测值 | 备注 |
|---|---|---|
| 从提问到首个 thinking 上屏 | **3.6~7.9s**（一次 19.4s，意图抽取慢） | 流式确认生效：帧间隔中位约 80ms，不是攒包 |
| 单步规划（`plan_tools.thinking_budget_tokens` = 2048） | **>60s，必撞超时** | 联调初版：规划器沿用回答的预算，循环一个工具都执行不了 |
| 单步规划（= 1024） | 11 / 20 / 24 / 30 / 59s，另 2 次 >60s 超时 | mimo 不严格守预算，显式预算反而诱导它"想满" |
| 单步规划（= 0，**现行**） | 9 / 28 / 38 / 39s | mimo 是原生推理模型，不传预算照样吐 thinking |
| 典型循环步数 | **1 步**（情绪/统计类）；**2 步**（读文件：find → recall 有依赖） | A+B 改动前是 3 步（第 3 步只为说"够了"） |
| 循环总时长 | 12.5~43s | A+B 改动前 113s |
| 回答模型自身思考 | 15~42s | `llm.thinking_budget_tokens = 2048`，本轮未动 |
| **从提问到答案第一个字** | **21~86s**（文件类问题 63~79s） | ≈ 意图/检索约 5s + 循环 + 回答思考 |

判据与动作：
- 首个 thinking > 10s → 先看意图抽取和向量化是否慢，再看流式链路
- 单步 >60s 频繁出现 → 可把 `vault.plan-tools-timeout-ms` 提到 90000（SSE 240s 仍够：90 + 60 + 余量）
- 想再快：回答侧 `llm.thinking_budget_tokens` 2048 也占 15~42s，可下调，但影响回答质量，需权衡
- 换成真 Claude 时：规划器预算 0 = 规划阶段完全不思考（思考面板要等回答阶段才出现），届时可设 1024

现有超时预算（`chat-loop-design.md` §4.3）：SSE 240s ≥ 循环 120s + Chat 流 60s + 余量。
最坏情况是 `loop-budget-ms + 单步上限`，因为已经开跑的一步不会被中途掐断。

## 7. 排查手册

**日志关键字**
| 想看什么 | 关键字 |
|---|---|
| 循环每步的入参 | `[PlanNextStep] step=2/4 \| prev_results=1 \| has_retrieval=False`（Python 侧） |
| 单步 LLM 耗时与 thinking 块数 | `[PlanNextStep] LLM 耗时 Xms \| thinking 块 N`（Python 侧） |
| 终帧决策 | `[PlanNextStep] done=… \| 计划 N 步`（Python 侧） |
| 检索命中数 | `对话检索完成，路由: X，命中: N`（B 侧） |

**直接看原始 SSE 流**（绕开前端，确认 thinking 是不是真的边出边来）
```bash
curl -N -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"question":"我感觉我最近特别焦虑怎么办","sessionId":"<会话id>"}' \
  http://localhost:9050/api/mirror/chat
```
`thinking` 事件应该**分很多帧陆续到达**。如果是最后一次性涌出来，说明流式链路断在某处。

**B 连不上 Python 的表现**：不报错，安静降级成纯 RAG（零回归设计），现象是"没有工具轨迹芯片"。
先查坑 A 的端口。

**循环终止原因**（B 侧 `org.xianshen.mumirrorb.tools`，dev 已开 INFO）：看 `循环终止（…）` / `对话循环完成`。
异常退出（步数 / 预算 / 规划中断 / 整体异常）是 **WARN**——联调初版这些是 INFO，被 dev 的
`org.xianshen.mumirrorb: WARN` 吞掉，循环静默地一个工具都没执行，日志里什么都看不到。

**embedding key 失效的表现**：路由为 HYBRID / SEMANTIC 的问题直接返回"暂时无法回答"（B 日志
`gRPC Embed 调用失败`，Python 日志 `[Embed] 错误: 401 invalid_api_key`）；路由为 PROFILE 的问题不受影响，
所以看起来像"时好时坏"。确认设置是否真的写进了库：看 `user_settings.updated_at`。

**Python 侧 LLM 超时实际是每次 10s**：`llm.timeout_seconds = 20` 按 `max_retries = 1` 平摊
（`openai_llm.py:29`），对流式调用是"两块数据之间的最大间隔"。日志里写的是 `>20s`，数字不准，别被误导。

**回答里出现方括号假标记**（`[get_stats]` 之类）：看 `chat_service._FakeCiteFilter` 是否生效
（只拦本轮用过的工具名，以及以"工具"开头的方括号；`[n]`、`[F1]` 放行）。

## 8. 回滚

一行配置，重启即生效：
```yaml
vault:
  chat-loop-enabled: false
```
回滚后走旧 `planAndExecute()` 单次规划 + `plan_tools_single.txt`（与改造前逐字节一致）。
`PlanTools` RPC、旧 prompt、`find_item→recall_item` 的旧兜底全部原样保留，**没有删任何东西**。
数据库那一列留着不用管（`DEFAULT FALSE`，旧代码不读它）。

## 9. 未决事项

1. **延迟** —— 端到端 21~86s，mimo 单步规划 9~60+s 波动大（§6）。结构上已压到 1 步，剩下是模型本身的推理速度。
   可选手段：单步超时 60→90s 提高成功率；下调回答侧思考预算换速度；规划器换一个更快的模型（要改 `user_settings` 结构，工作量大）。
2. **验收 2 端到端未跑通** —— 需要"配好模型但零记录"的账号。联调时注册了 `loopcheck0921 / 111111`（未配模型），
   未配模型的账号在向量化那步就会报"暂时无法回答"，这是既有行为，与循环无关。给它配上模型即可复测。
3. **`recall_item` 摘录选段质量** —— `recall_item(query="架构")` 返回的是文档开头（版本说明、旧文档对照表），
   而不是架构章节。回答模型如实说了"摘录里没有架构部分"。属 vault 摘录选段的既有问题，不在本轮范围。
4. **`find_item` 无精确匹配时返回全部文件** —— 问不存在的"周报"时返回了 4 个文件，兜底补读于是去读了
   不相关的"毕设开题报告.md"。本次没有误导回答，但白读一次。既有 vault 行为。
5. **规划器思考内容会提到 prompt 字眼** —— 如"根据规则 2""系统提示里说"。prompt 已要求不要，模型部分遵守。
   用户在思考面板里能看到，属体验小瑕疵。
6. **`upload/` 是不是部署源** —— 见坑 A。搞清楚之前不要删，继续双写。
7. **情绪类问题的判定是权宜之计** —— Java 侧关键词表 + `intent.moodsList` 非空。正解是 `ExtractIntent`
   给显式意图位，要改 proto，留到下一轮。
8. **`test_round9.py` 的 `import httpx2`** —— HEAD 既有拼写错误（应为 `httpx`），7 个用例一直失败。

## 10. 联调记录（2026-09-21）

环境：远程测试库（GameViewer 隧道到 `localhost:5432`）+ 本机 Python（10003）/ B（9050，dev profile）/ F（5173）。
测试账号 `xxx`（26 条记录，最后一条 9/13，近 30 天 12 条），模型 mimo-v2.5（Anthropic 协议）。

### 发现并修复的 11 个问题

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 循环一个工具都没执行 | 规划器沿用回答的 2048 思考预算，mimo 约 36 token/s，光思考 ~57s，必撞 60s 单步超时 | 新增 `plan_tools.thinking_budget_tokens`，实测后定为 0（§6） |
| 2 | 上面那次超时日志里完全看不到 | 循环终止日志是 INFO，被 dev 的 `org.xianshen.mumirrorb: WARN` 吞掉 | 异常退出改 WARN；dev 开 `tools` 包 INFO |
| 3 | 3 轮规划里第 3 轮只为说"够了"（~30s） | `done=true` 带 calls 时丢弃 calls | 改为"执行完这最后一批即收尾" |
| 4 | 一步只查一个工具 | prompt 没鼓励并行 | prompt：互不依赖的查询放同一步 |
| 5 | 查到 12 条记录，回答却说"没有素材" | 渲染工具结果时 summary 非空就不渲染 payload，回答模型只看到"12条"三个字 | summary 当标题、payload 当明细（记录逐行摘录） |
| 6 | 同上，加剧 | 检索空时 `{context}` 写死"（没有找到相关记录）"，与工具结果打架 | 有工具数据时改口"依据在下方工具查询结果" |
| 7 | 回答里出现 `[工具结果·xxx]` `[get_stats]` `[工具查询结果]` | 方括号标签像引用编号，模型照抄；prompt 禁止也拦不住 | 标签改成"工具 X 返回："+ 输出流确定性过滤 |
| 8 | `search_records(moods=["anxious"])` 返回全部 12 条 | 工具定义宣称支持 moods，执行时写死传 null | moods 下推到 SQL（修后返回 1 条） |
| 9 | 读文件正文失败，回答"看不到原文" | 循环里删了 recall_item 的 id 自动接续，假设"模型会自己填"——实测不填 | 恢复自动接续 find_item 命中的 id |
| 10 | recall_item 成功，回答仍说"只看到上传记录" | 文件类结果只渲染文件元信息，正文摘录被丢 | 渲染 `quotes` 摘录，挂在对应 [F编号] 下 |
| 11 | **浏览器里等一分钟，回答一次性全部出现**（后端逐帧推送正常） | F `chat.js` 的 `pushAiPlaceholder` 返回原始对象而非响应式代理，之后每帧写入 `thinking`/`content` 都不触发 Vue 更新，要等流结束 `sending` 翻转才一次性渲染。**自 2026-09-04 对话功能上线即存在**，回答正文的流式也从未生效过 | 返回 `messages.value[末尾]`（响应式代理）。Node 里用 F 自己的 Vue 复现：修复前 70 帧刷新 0 次、修复后 70 次；无头 Edge 真实页面前后对照见 §0 |

另有环境问题：账号 `xxx` 的 embedding key 失效（阿里云 401），已由用户更新。

### 追加：按日期查（同日，用户提问「你还记得我十二号弹了什么曲子吗」）

规划器思考里猜"假设今天是 10 月 15 号"，最后调 `search_records({"query":"弹曲子","days":30})` → 0 条。
9/12 的日记里答案写得很清楚（先想直接练春日影 → 太难 → 改练小星星 → 20:29 弹出来了），查不到不是模型笨：

| # | 根因 | 修复 |
|---|---|---|
| 12 | 规划 prompt 里没有今天的日期，"十二号""上周"无从换算 | 规划 prompt 注入 `今天是 YYYY-MM-DD（星期X）`（东八区） |
| 13 | `search_records` 只有 `days`，表达不了"某一天"；`query` 是逐字匹配，"弹曲子"命中 0 条 | 新增 `date` / `date_from` / `date_to`（按 `created_at` 精确过滤，按日期查默认取满 20 条）；工具描述写明 query 是逐字匹配；prompt 加"问某一天 → 按日期整天取回、不带 query" |
| 14 | 回答里出现编造的 `[4][6][7]`、`[F1]`（本轮根本没有对应来源） | 输出过滤按本轮真实来源数 / 文件数校验编号，超出的剔除，连同前导空格 |
| 15 | 把一天里的先后顺序讲反 | 工具记录渲染改为时间正序、保留到分钟（原先只留日期且沿用 SQL 倒序） |

说明：入库时的相对时间词替换（`TimeSubstitutionApplier`，9/20 上线）改的是 segment 文本，
解决的是"读"的问题（旧记录里的"明天"不被误读）；这次卡的是"查"——按日期过滤只需要 `created_at`，
而且关键那条"我终于能弹出小星星了"本身没有时间词，替换后文本里也不会出现日期。

修复后回答：
> 9月12号你下午练琴的时候，一开始是想直接上手弹《春日影》的，但很快就发现这首曲子太难了，
> 所以改计划从《小星星》开始学。到了晚上，你已经能弹出《小星星》了。

### 验收 1 最终回答（同会话第 2 问「我感觉我最近特别焦虑怎么办」）

> 焦虑嘛，我这边能看到过去30天里有一次你标注了焦虑。另外，你9月12号还计划补文献综述，说"要肝一波了"，
> 这压力估计不小。最近7天你一条新记录都没写，所以情绪变化我这儿看不全。要不试试从今天起每天随手记一笔心情？
> 或者先把剩下的3个待办任务拆成小块，别一起扛着。

改造前同一问题的回答是「没有找到相关记录」。
