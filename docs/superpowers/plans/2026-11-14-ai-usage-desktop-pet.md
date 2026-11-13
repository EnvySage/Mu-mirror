# AI Usage Desktop Pet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. User explicitly authorized autonomous execution and checkpoint commits; do not ask for another plan approval. Independent repositories may be delegated under dispatching-parallel-agents after fixing the interfaces below.

**Goal:** Ship eight desktop-only interactive half-body pets with honest per-task and cumulative AI usage, plus useful navigation shortcuts.

**Architecture:** Python observes upstream SDK attempts in request-local contexts and returns a bounded usage report in gRPC trailers. Java binds the authenticated owner, persists invocation/attempt receipts independently of business transactions, aggregates tasks, and exposes read-only current-user APIs. Vue mounts a desktop pet and reuses one usage panel; the server owns totals.

**Tech Stack:** Existing Python gRPC/OpenAI/Anthropic SDKs/httpx, Java 17/Spring Boot/MyBatis/PostgreSQL, Vue 3/Pinia/CSS motion, imagegen transparent atlases.

**Spec:** [AI 用量桌宠设计](../specs/2026-11-14-ai-usage-desktop-pet-design.md)

## Global Constraints

- All non-Embedding LLM requests, including retries, background tasks, model tests and shadow requests, are in scope.
- Existing text/stream behavior, deadlines, retry policy, legacy routing, user confirmation cards and memory authorization remain governed by existing code.
- Python stays stateless and never writes business PostgreSQL. Java owns usage persistence and user isolation.
- Token and decimal credits are separate; ambiguous quota/balance fields remain unknown. Do not infer free use from missing fields.
- First eight reference images map to DeepSeek, Gemini, Claude, GPT, Grok, GLM, Kimi, Qwen; last reference is the style. No mobile pet, even if mobile forces desktop presentation.
- Generate real transparent half-body raster assets. Eight expressions per character, stable framing, local interactions only.
- Work on `feat/usage-pet-v1-20261114` in all three repos. Baseline is `backup/pre-usage-pet-20261114`. Checkpoint coherent tested changes with docs and tests; never commit credentials.
- Database changes target the already isolated `mu_mirror_debug_20261114`; keep original `mu_mirror` unchanged. New migration currently V21, verify numbering before writing.

## Review Focus

- A stream with no final usage cannot become zero consumption; known partial values survive interruption without cumulative double counting.
- SDK automatic retries must appear as distinct observed attempts, with original retry behavior intact.
- A successful business transaction and an AI charge receipt have independent persistence; rollback or errors cannot erase already observed use.
- Two users/tabs and a configuration change cannot mix receipts, duplicate notifications or reattribute historical channels.
- A forced desktop layout on a narrow phone still has no pet DOM; quick navigation respects the existing draft guard.

## Fixed Cross-repository Interfaces

### gRPC metadata (version 1)

Java request metadata:

```text
mirror-usage-invocation-id: <UUID>
mirror-usage-task-id: <UUID>
mirror-usage-scene: <ascii scene id>
```

Python does not need a user ID; Java retains ownership. Absent metadata means no collection (old callers and direct tests retain behavior). Embedding service never collects even if metadata is supplied.

Response trailer key: **`mirror-usage-bin`**, bytes containing UTF-8 JSON:

```json
{
  "version": 1,
  "invocation_id": "0b59b70e-9bc2-4d21-97b2-ef062d8c5e8d",
  "complete": true,
  "attempts": [{
    "id": "9d2f278c-66ad-4771-b769-8b65469e0df3",
    "status": "success",
    "provider": "deepseek",
    "model": "deepseek-v4.1-flash",
    "input_tokens": 34,
    "output_tokens": 16,
    "total_tokens": 50,
    "credits": null,
    "credit_unit": null,
    "usage_state": "complete",
    "latency_ms": 120,
    "http_status": 200
  }]
}
```

`status`: success/error/cancelled. `usage_state`: complete/partial/unknown. Missing counts are null, never defaulted to zero. Credits, if confirmed, are nonnegative decimal strings; unit defaults to 积分. Cache/reasoning details may be additional whitelisted numeric fields, never prompts/text/headers/keys. Limit 48 KiB and 100 attempts per report; overflow sets complete=false and preserves known prefix, which Java marks incomplete. Empty complete attempts means zero actual upstream requests, not one billable call. Java must verify version, matching invocation UUID, field types/ranges, duplicate IDs and bounds. Old/missing/broken trailers become an incomplete invocation, not a fabricated physical attempt.

### Scene identifiers

`record_analysis`, `chat`, `planning`, `intent`, `glossary`, `profile`, `summary`, `chat_memory`, `tarot`, `receipt`, `model_test`, `shadow`. Inner calls can inherit a task scene. Unrecognized future scenes are displayed as AI 调用.

### Java REST API (camelCase R.data; frontend request.js converts to snake_case)

- `GET /api/ai-usage/summary?period=today|month|all&from=YYYY-MM-DD&to=YYYY-MM-DD&scene=&provider=`. Custom from/to overrides period; date boundaries use Asia/Shanghai. Return `{inputTokens,outputTokens,totalTokens,requestCount,taskCount,unknownCount,incompleteCount,activeCount,credits:[{channelKey,channelName,unit,amount}],since,timezone}`. `amount` is decimal string. Token sums are known lower bounds, no fabricated estimates.
- `GET /api/ai-usage/tasks?limit=30&before=<cursor>&scene=&provider=`. Return `{items:[task],nextCursor}`. Latest-updated tasks first; server aggregates over all history, not only this page.
- `GET /api/ai-usage/tasks/{id}`. Return task with `attempts` safe details. Non-owner gets no record.
- Task: `{id,scene,label,provider,model,status,startedAt,completedAt,updatedAt,inputTokens,outputTokens,totalTokens,requestCount,unknownCount,incompleteCount,activeCount,credits:[...]}`. Status running/complete/partial/error/cancelled. A task ID is stable across its phases, particularly a chat run; default new UUID for standalone background work. Backend can add fields but must coordinate any renaming with frontend agent.
- Frontend polls recent tasks and summary while visible, seeds initial seen IDs without bubbling history, and queues/coalesces newly completed tasks. Never increment server totals locally. Task updates/replays are deduplicated by task ID plus completion timestamp/version.

### Asset interface

Root owns `Mu-mirror-F/public/pets/`. Filenames: `deepseek-expressions.png`, `gemini-expressions.png`, `claude-expressions.png`, `gpt-expressions.png`, `grok-expressions.png`, `glm-expressions.png`, `kimi-expressions.png`, `qwen-expressions.png`. Each is a transparent **4 columns × 2 rows** equally spaced atlas. Frame order: idle, blink, thinking, success, shy, pout, sad, sleep. CSS background-size 400% 200%; all head anchors must align. Manifest and prompts live next to assets. Frontend agent owns source catalog/lookup and consumes these paths; it does not generate or overwrite these files.

## Task 0: Baseline and checkpoints (root)

- [x] Verify three repositories and preserve baseline branches; create feature branches without changing current service paths.
- [x] Dump the isolated debug database to `own/backups/usage-pet-v1-20261114/database-before.dump`.
- [ ] Commit design, this plan and document index as checkpoint 0. Save baseline SHAs and rollback instructions outside repos in the backup directory.

## Task 1: Upstream capture and trailer reporting (AI owner)

**Files:** add focused modules under `mirror_core/usage/` (or `usage/` if it avoids package ambiguity), add gRPC interceptor registration in `server.py`, connect existing `llm/openai_llm.py` and `llm/anthropic_llm.py` at SDK HTTP-client boundary, propagate context at `agent_core/` thread boundaries where required; add `tests/test_ai_usage*.py`; update protocol/startup docs and `upload/` via existing sync script.

**Interfaces:** consumes metadata and emits exact report above; does not modify B/F or change business proto payloads.

- [ ] Write and run failing behavioral tests using real SDKs with httpx mock transport: unary 34+16=50, final empty-choice usage chunk, Anthropic cumulative output and cache normalization, explicit decimal credit consumption, ambiguous quota, interrupted stream, retry attempts and per-request context isolation.
- [ ] Implement bounded capture without reading/retaining model content, changing request payloads except supported stream usage request, or altering network retry/timeout/proxy behavior. Unknown upstream/network outcomes are explicit.
- [ ] Add unary and server-streaming gRPC interceptor coverage for success/error/finally, Embedding exclusion and complete empty reports; verify context propagation through graph work.
- [ ] Run targeted tests then complete existing pytest suite; disclose environment skips and set up isolated reader fixtures if needed. Sync `upload/` and run `--check`.
- [ ] Update docs and commit a checkpoint. Report exact trailer sample and any integration caveats to root/B owner.

## Task 2: Persistent receipts, ownership and API (backend owner)

**Files:** add focused `usage/` services/controller/interceptor/models/mapper; modify gRPC channel/client binding in `grpc/AiGrpcClient.java`, `grpc/TarotGrpcClient.java`, `grpc/AgentGrpcClient.java`, and task scoping at existing run/async/background entry points; add `db/migration-v21-ai-usage.sql` and matching `schema.sql`; add `src/test/.../usage/` tests; update `REFERENCE.md`, `shared-protocol.md`, and current progress.

**Interfaces:** consumes exact report and emits exact APIs above. Coordinate refinements with AI/frontend owners. Do not modify AI/F. Root owns design/plan/checkpoint manifest.

- [ ] Write and run failing tests for report validation, replay idempotence, missing/partial trailers, accurate decimal credit aggregation, ownership filtering, date boundaries and task grouping.
- [ ] Implement invocation pre-record and independent completion transaction. Persist observed attempts once, immutable channel/provider/model snapshots, status and coverage. Pending stale invocations become visibly incomplete instead of active forever.
- [ ] Bind trusted owner/config/scene for every non-Embedding method across all three gRPC clients, propagate one chat task ID across planner/answer RPCs, and group standalone background tasks. Exclude Embed/EmbedBatch/GetModelInfo entirely.
- [ ] Implement current-user summary, paginated tasks and task detail, keeping credits per channel and totals separate. Usage write failures must not turn successful business answers into failures.
- [ ] Verify V21 twice and fresh schema against an isolated database; run targeted tests and backend full suite with safe named test DB. Leave live debug migration/restart to root after checkpoint.
- [ ] Update docs and commit checkpoint. Provide API fixtures and integration instructions to frontend/root.

## Task 3: Desktop pet, usage panel and routes (frontend owner)

**Files:** add `src/components/pet/` components and styles, `src/stores/aiUsage.js`, `src/api/aiUsage.js`, utility functions for model identification/formatting/queue state; modify `MainLayout.vue` to mount and models/settings view to expose restoration; add behavioral unit/browser checks in existing test structure; update UI docs.

**Interfaces:** consumes exact REST/asset contracts above. Root supplies atlases. Do not modify `public/pets/`, backend or AI.

- [ ] Write and run failing tests for manufacturer aliases (including `cn:deepseek-v4.1-flash`), manual lock/fallback, credit formatting and unknown display, deduped notifications/account changes, task totals, bounds/drag-click separation, and route targets.
- [ ] Implement 120–140px half-body sprite pet, eight states and elastic local click/drag motion. Toolbar buttons open usage/character picker/quick navigation; offer existing ledger and models routes without bypassing router guards. Include minimize/restore, local per-user position/preferences, keyboard and reduced motion support.
- [ ] Implement today/month/all/custom dates, scene/model filters, paginated tasks and per-request detail using server totals. Handle pending/unknown/partial states honestly and format decimal credits without float summation. No mock fallback in ordinary logged-in app.
- [ ] Mount only on real desktop viewport plus desktop presentation; remove DOM on narrow viewport even forced desktop. Suspend notifications/polling while hidden or modal conflicts; no history storm on return.
- [ ] Run npm tests/build and browser desktop/narrow-screen checks. Update docs and commit checkpoint, leaving asset additions for root checkpoint.

## Task 4: Eight original transparent atlases (root)

- [ ] Use imagegen built-in with inspected local references and final style sample. Generate DeepSeek first to establish style, then remaining seven using their own identity references and same style.
- [ ] Inspect every atlas for correct 4×2 layout, transparency, eight readable expressions, consistent head anchor and role identity. Regenerate specific failures; do not substitute existing full-body originals or code placeholders.
- [ ] Save all assets, manifest and prompt set inside F/public/pets; verify file paths/catalog alignment and include in a checkpoint with UI documentation.

## Task 5: Real integration, review and morning handoff (root)

- [ ] Reconcile three owner changes, run matching contract tests, apply V21 only to the backed-up debug database, restart AI/backend against current feature branch, preserve frontend URL 5173.
- [ ] Use the user-authorized local test model in an isolated acceptance account to verify unary/streamed use, grouped task totals and bubbles. Keep key in transient process memory/environment, never committed or printed.
- [ ] Browser evidence: 1440px desktop pet, all characters/expressions, drag/click, usage totals/details, route jumps and unsaved guard; mobile 390px and forced-desktop narrow mode have no pet. Credit mode uses explicit fixtures and is reported as fixture verification unless a real credit API exists.
- [ ] Run relevant full test suites and build after integration; investigate any new failures. Request final independent review and fix valid findings before final checkpoint.
- [ ] Commit final coherent work in all three repos, leave status clean, record SHAs/test evidence/known limits and rollback instructions in checkpoint manifest. Do not push/deploy without a request.
- [ ] Keep services available at existing local addresses. Explain which test account/debug database is active and how to restore baseline. No production or other-machine migration claims.
