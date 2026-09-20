# 对话 Agent 循环 · 联调手册

> 2026-09-21。配套 `chat-loop-design.md`（设计稿）。
> 代码改动已完成并通过离线验证；**本轮所有验收都需要活栈，尚未做过任何端到端联调。**
> 谁来跑：有 PostgreSQL + Python 服务 + LLM key 的环境。照本文从头到尾走一遍。

## 0. 当前状态（诚实版）

| 项 | 状态 |
|---|---|
| B 侧编译 | ✅ 通过 |
| B 侧单测 `ChatLoopTest`(14) + `ChatRetrievalTest`(20) | ✅ 34 通过 0 失败 |
| B 侧全量 236 例 | ✅ 除 `contextLoads`（环境前置，见 §2）全通过 |
| AI 侧 pytest | ✅ 295 通过；7 失败是 `test_round9.py` 既有 `import httpx2` 拼写错误，与本轮无关 |
| 跨仓 proto 字段号/签名一致 | ✅ 静态核对通过 |
| SSE 事件名 B 发 vs F 收 | ✅ 两侧集合完全相同（7 个），**"F 零改动"成立** |
| **端到端行为（验收 1~7）** | ❌ **全部未验证** |
| **真实延迟** | ❌ **未实测**。设计里"每步约 20s"是从代码注释推的假设，不是实测值 |

**最大的未知数是延迟。** 如果实测一步要 40s 以上，`max-loop-steps: 4` 必须往下调，详见 §6。

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
select tool, summary, success, latency_ms, created_at
from tool_calls where session_id='…' order by created_at;
```
**该看到**：循环每一步都有记录（含失败步、未知工具步）。这是论文消融/延迟图表的素材来源。

### 验收 8（新增）：`find_item` 兜底
问一个需要读文件正文的问题（如「我上传的设计文档里架构部分怎么写的」）。
**该看到**：若模型全程只调了 `find_item` 没调 `recall_item`，循环正常结束时会**自动补一次
`recall_item`**，审计里能看出这一刀是兜底补的。
背景：旧路径注释记着「实测模型经常只规划 find_item」，循环不能把这个实测结论丢掉。

## 6. 延迟实测（**请填**，这是最重要的一张表）

| 指标 | 实测值 | 备注 |
|---|---|---|
| 单步规划耗时（thinking 打开 + 流式） | | 日志 `[PlanNextStep] … LLM 耗时 Xms` |
| 典型循环步数 | | 验收 1 第 2 问实际跑了几步 |
| 从提问到第一个 thinking 字符上屏 | | **这个数决定体验**，应该是 1~2 秒级 |
| 从提问到答案第一个字 | | = 循环总时长 + Chat 首字 |
| 循环总时长 | | 日志时间戳相减 |

判据与动作：
- 首个 thinking 字符 > 5s → 流式没生效，回头查 `chat_stream` 那条链路
- 单步 > 40s → `max-loop-steps: 4` 降到 2~3，否则最坏路径会顶到 SSE 上限
- 总时长接近 240s → 说明 `loop-budget-ms: 120000` 的掐表点（步与步之间，不中途掐断）
  加上单步 60s 上限已经吃满，需要同时下调两者

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

1. **`upload/` 到底是不是部署源** —— 见坑 A 的附带结论。搞清楚之前不要删，继续双写。
2. **情绪类问题的判定是权宜之计** —— 现在是 Java 侧关键词表 + `intent.moodsList` 非空。
   正解是 Python 侧在 `ExtractIntent` 里给一个显式意图位，但那要改 proto，留到下一轮。
3. **`test_round9.py::TestSdkExceptionTranslation` 的 `import httpx2`** —— HEAD 既有拼写错误
   （应为 `httpx`），7 个用例一直在失败。与本轮无关，顺手可修。
4. **延迟假设未验证** —— 见 §6。这是唯一可能推翻 `max-loop-steps: 4` 这个参数的因素。
