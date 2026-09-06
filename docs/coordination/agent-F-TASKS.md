# 子 Agent 3：前端开发（Mu-mirror-F）—— 第十四轮：审查修复批前端（确认门禁+防误删+五态）

## 背景
B 侧审查修复批即将交付（确认端点/五态 digest/confirm_name 校验/，契约见 fix-batch-design.md + toolcalling-vault-design.md §3.3b/c/d）。你做前端配套，mock 先行不等 B。

先读：E:\project\mirror\own\coordination\toolcalling-vault-design.md §3.3b/c/d（确认门禁/key-embed/三层防误删——本轮全部设计依据）+ fix-batch-design.md 的 F 侧任务节。

## 任务（5 项）

### 1. digest 五态徽标（资产页）
四态改五态：pending 灰"排队中" / **extracted 蓝"待确认 · 检索不到"** / confirmed 绿"已可检索" / skipped 灰"仅保管" / failed 红"读取失败"。existing done 数据语义上=confirmed（后端已迁移）。

### 2. 回执卡适配确认门禁（核心交互变化）
- 上传后回执卡不再是"已消化✓"，而是 extracted 态：key/description **可编辑字段** + 「确认，让它可被检索」主按钮 + 「仅保管，不检索」次按钮（对应 skipped）
- 确认 → POST /vault/{id}/confirm（key/description/category）→ toast「已可检索」→ 状态变 confirmed
- mock：USE_MOCK 下 confirm 走 1s 延迟改状态
- 提取失败态：key/description 留空输入框 + 引导文案"告诉镜子这是什么，才能被找到"

### 3. 删除三层防误删（Q2）
- 对话内删除：内联确认卡文案**带文件名**「确认删除「毕业论文.pdf」？此操作不可恢复」
- 资产页删除：点删除 → 弹输入框「输入文件名最后 4 位以确认」→ 校验匹配才调 DELETE（后端 confirm_name 校验，前端先本地预校验减少无谓请求）→ 错误 toast
- 删除 toast **5 秒撤销窗**：点删除后立即出 toast「已删除 · 撤销」，5 秒倒计时条；点撤销→取消 DELETE 请求恢复卡片；5 秒到才真正发 DELETE
- 避免过度设计：对话内路径不加后四位（已有带名确认卡）

### 4. 图片强制描述
上传卡选了图片类文件时 description 输入框必填（placeholder 强提示「图片必须写一句描述，否则无法被找到」），空则「就这样存」禁用。

### 5. mock 开关切真
- stores/glossary.js USE_MOCK → false（B 七端点已上线）
- stores/vault.js USE_MOCK → false（B vault REST 已上线）——**注意确认门禁新契约**（extracted/confirm）
- stores/mirror.js USE_MOCK_MONTHLY → false（B generate-monthly 已上线）
- 切换后跑全链路走查，发现契约不符列清单（别自己改 B 侧语义）

## 验证标准
- dev 真服务 + Playwright 走查：五态徽标/确认门禁全流程（上传→extracted→确认→confirmed→检索到）/后四位删除/撤销窗/图片必填/三 mock 切真后的全页冒烟
- 控制台 0 error、check-imports、build、375px
- 写日志 agent-F.md；不要 git commit

## 最终返回
任务逐项、走查 PASS/FAIL、mock 切真后的契约差异清单、改动文件、偏差
