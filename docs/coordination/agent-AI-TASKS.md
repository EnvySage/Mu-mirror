# 子 Agent 1：Python AI（Mu-mirror-AI）—— 第十一轮：PlanTools RPC + 工具结果渲染

## 状态：排队中（待 B Agent 词典轮交付后开工，避免两 Agent 同仓改 proto 冲突）

## 背景
toolcalling+vault sprint AI 侧。设计稿（唯一蓝本）：`E:\project\mirror\own\coordination\toolcalling-vault-design.md`——重点第 0/1/5/6 节。词典轮你的产出（lexicon_service.py/glossary_render.py/errors.py 超时体系）全部复用。

## 任务

### 1. proto 落地（契约源头在 AI 仓，B 侧对账）
按设计稿第 6 节在 proto/ 四件套上落：
- common.proto 或 mirror_chat.proto：`ToolSpec{name,description,args_schema}` / `ToolResult{tool,summary,payload_json,success}`
- `PlanToolsRequest{question=1, glossary=2, repeated ToolSpec tools=3, llm_config=4}` / `PlanToolsReply{repeated PlannedCall calls=1}` / `PlannedCall{tool=1, args_json=2}`
- MirrorChat 服务加 `rpc PlanTools(PlanToolsRequest) returns (PlanToolsReply)`
- ChatRequest 加 `repeated ToolResult tool_results = 6`（字段号核对现有注释避免撞号）
- generate_proto.py 重生成 + wire 冒烟（首字节断言）+ shared-protocol.md 登记（B 对账清单：字段号+方法归属）

### 2. PlanTools RPC 实现（services/plan_service.py，并入 MirrorChat servicer 或独立——参考 lexicon_service 并入 RecordProcessor 的先例自行判断，日志声明理由）
- 输入：question + glossary + **tools 注册表快照（Java 传入，Python 不硬编码工具清单）**
- prompt 第 8 套 prompts/plan_tools.txt：
  - 工具注册表逐条渲染（name/description/args_schema）
  - 输出 JSON 约定：`{"calls":[{"tool":"search_records","args":{...}}]}`，**最多 2 步**，无需工具时 `{"calls":[]}`
  - few-shot 2 例（查统计类/检索类各一）
  - 内置决策规则：可核查自我评价（"我这周啥也没干"类）→ search_records 本周窗口；"我什么时候开始…"类 → get_coverage；"用 x 月的我回答" → get_profile 变体（B1/B2/B4 场景的判定约束写死在 prompt）
- parse_json 复用 llm_json.py；失败/超时走 abort_with_mapped；**PlannedCall 校验**：tool 名必须在传入注册表内（防幻觉调用）、args_json 可解析、步数≤2，违规项剔除而非报错
- 超时预算：**3s 客户端 deadline 由 B 控制**，Python 侧 LLM 调用沿用 20s 超时体系但 plan prompt 要求短输出——日志记录 plan 耗时

### 3. Chat 的 tool_results 渲染
- ChatRequest.tool_results → chat prompt 渲染为上下文块：`[工具结果·search_records] 12 条：...`（截断到 max_tool_chars 配置）
- 空列表零影响（prompt 不留孤儿块，同 glossary 模式）
- prompts/chat.txt 加 {tool_results} 占位符

### 4. 配置
config.yml 加 `plan_tools` 段：max_calls=2 / max_tool_chars=2000 / 各上限配置化

## 验证标准
- 桩 LLM 扩展 plan_tools 分支（返回造好的 calls JSON）+ 真实 server 起停
- E2E：正常规划（返回 1-2 步）/ 无需工具（空 calls）/ 工具名幻觉（注册表外→剔除）/ LLM 超时→DEADLINE_EXCEEDED / 坏 JSON→INVALID_ARGUMENT 且失败隔离
- 单测 tests/test_round11.py：PlannedCall 校验、注册表渲染、tool_results 渲染、配置
- 既有全量回归（round9/round10 单测+冒烟+E2E）
- 日志 agent-AI.md；**不要 git commit**

## 最终返回
任务逐项结果、proto 登记确认、测试结果、改动文件、偏差
