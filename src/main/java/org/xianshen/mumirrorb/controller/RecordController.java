package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.pojo.DTO.RecordConfirmDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;
import org.xianshen.mumirrorb.pojo.DTO.TodoResolutionDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.ChunkVO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.pojo.VO.TodoSuggestionVO;
import org.xianshen.mumirrorb.service.ChunkService;
import org.xianshen.mumirrorb.service.RecordService;
import org.xianshen.mumirrorb.service.TodoRegistryService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录管理控制器
 *
 * 提供日记记录的完整 CRUD 操作，包括：
 * - 创建记录（用户提交原始内容）
 * - 查询记录列表（支持按日期范围筛选）
 * - 获取单条记录详情
 * - 编辑尚未授权 AI 的原文、更新 AI 片段与授权
 * - 分别确认记忆片段与待办建议
 * - 软删除当前用户自己的记录
 *
 * 状态流转：RECORD_ONLY →（用户请求整理）PROCESSING → REVIEWING → DONE
 */
@Tag(name = "记录管理", description = "日记记录的增删改查操作")
@RestController
@RequestMapping("/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;
    private final ChunkService chunkService;
    private final TodoRegistryService todoRegistryService;
    private final org.xianshen.mumirrorb.service.ActionProposalService actionProposalService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 获取当前登录用户的UUID
     */
    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    /**
     * 创建记录
     *
     * 默认创建为仅记录（RECORD_ONLY）；仅在用户本次主动请求 AI 整理时触发异步处理。
     * AI 服务生成的内容仅为候选，长期记忆与待办仍需分别确认。
     *
     * @param dto 记录数据（只需提供 content 字段）
     * @return 创建成功的记录（RECORD_ONLY 或 PROCESSING）
     */
    @Operation(
            summary = "创建记录",
            description = "本路由只保存用户明确提交的原文。AI 整理请使用操作卡准备接口；requestAiProcessing=true 为兼容字段但会被拒绝。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "创建成功",
                    content = @Content(schema = @Schema(implementation = RecordVO.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "参数错误（如内容为空或超过2000字）",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未登录或 Token 无效",
                    content = @Content
            )
    })
    @PostMapping
    public R<RecordVO> create(@Valid @RequestBody RecordDTO dto) {
        UUID userId = getCurrentUserId();
        RecordVO record = recordService.create(dto, userId);
        return R.ok("已保存，仅供自己记录", record);
    }

    @PostMapping("/{id}/organize")
    @Operation(summary = "准备 AI 整理候选", description = "保留旧路由；只生成待确认操作卡，不更改记录或 AI 授权。")
    public R<ActionProposalVO> organize(@PathVariable Long id) {
        var payload = objectMapper.createObjectNode().put("sourceRecordId", id).put("mode", "AI_ASSISTED");
        var body = new ActionPrepareDTO("record.organize", payload, "record-organize:" + id + ":" + UUID.randomUUID(), null, null);
        return R.ok("已准备整理候选，确认前不会修改记录或授权 AI", actionProposalService.prepare(getCurrentUserId(), body));
    }

    @PutMapping("/{id}/content")
    @Operation(summary = "编辑仅记录原文", description = "仅允许编辑未授权 AI 的 record_only 原文。")
    public R<RecordVO> updateRaw(@PathVariable Long id, @Valid @RequestBody RecordDTO dto) {
        return R.ok("原文已更新", recordService.updateRaw(id, dto.getContent(), getCurrentUserId()));
    }

    /**
     * 查询记录列表
     *
     * 按日期范围查询当前用户的记录列表。
     * - 不传参数：默认查询今天的记录
     * - 只传 startDate：查询该日期的记录
     * - 只传 endDate：查询该日期的记录
     * - 两个都传：查询日期范围内的记录
     *
     * 结果按创建时间降序排列（最新在前），自动过滤已删除的记录。
     *
     * @param queryDTO 查询条件（startDate, endDate 均为可选）
     * @return 记录列表
     */
    @Operation(
            summary = "查询记录列表",
            description = "按日期范围查询当前用户的记录列表。" +
                    "不传日期参数时默认查询今天的记录。" +
                    "结果按创建时间降序排列，自动过滤已删除的记录。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "查询成功",
                    content = @Content(schema = @Schema(implementation = RecordVO.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未登录或 Token 无效",
                    content = @Content
            )
    })
    @GetMapping
    public R<List<RecordVO>> list(@Valid RecordQueryDTO queryDTO) {
        UUID userId = getCurrentUserId();
        List<RecordVO> records = recordService.list(queryDTO, userId);
        return R.ok("查询成功", records);
    }

    /**
     * 获取单条记录详情
     *
     * 根据记录 ID 获取详细信息，包括原文、AI 候选片段和各自授权状态。
     * 只能查看自己的记录，自动过滤已删除的记录。
     *
     * @param id 记录ID
     * @return 记录详情
     */
    @Operation(
            summary = "获取记录详情",
            description = "根据记录ID获取详细信息，包括内容、标题、摘要、标签、情绪等。只能查看自己的记录。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "查询成功",
                    content = @Content(schema = @Schema(implementation = RecordVO.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未登录或 Token 无效",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "记录不存在或已被删除",
                    content = @Content
            )
    })
    @GetMapping("/{id}")
    public R<RecordVO> detail(
            @Parameter(description = "记录ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        RecordVO record = recordService.getById(id, userId);
        return R.ok("查询成功", record);
    }

    /**
     * 更新 Chunk（审核阶段修改 AI 元数据）
     *
     * 请使用 Chunk 管理接口：PUT /chunks/{id}
     */

    /**
     * 确认审查完成
     *
     * 将记录状态从"人工审查"（REVIEWING）改为"已完成"（DONE）。
     * 调用此接口分别保存用户选择的长期记忆片段和待办片段。
     *
     * REVIEWING 用于首次确认；DONE 用于调整既有片段授权。
     *
     * @param id 记录ID
     * @return 状态更新后的记录
     */
    @Operation(
            summary = "确认片段记忆授权（携带待办决议）",
            description = "首次确认时将记录状态从'人工审查'改为'已完成'，也可在已完成记录中调整记忆授权。" +
                    "aiMemoryChunkIds 与 todoChunkIds 分别列出同意进入长期记忆和登记为待办的片段。" +
                    "可选 body：{\"todoResolutions\":[{\"suggestionId\":123,\"action\":\"confirmed\"," +
                    "\"status\":\"completed\"},{\"suggestionId\":124,\"action\":\"dismissed\"}," +
                    "{\"todoId\":6,\"action\":\"confirmed\",\"status\":\"in_progress\"}]}。" +
                    "条目分两类且 suggestionId / todoId 恰好其一：前者裁决 AI 建议，后者为'用户主动挂载已注册 todo'。" +
                    "action=confirmed 时 status 必填（not_started/in_progress/completed），状态随入库一起生效；" +
                    "todoId 分支 action 仅允许 confirmed。" +
                    "该记录下未出现在 body 中的 pending 建议一律作废。body 缺省 = 全部未处理建议作废。" +
                    "仅 REVIEWING 或 DONE 状态可调用此接口。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "确认成功",
                    content = @Content(schema = @Schema(implementation = RecordVO.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "记录状态不允许授权（非 REVIEWING/DONE）或片段/待办决议参数非法",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未登录或 Token 无效",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "记录不存在或已被删除",
                    content = @Content
            )
    })
    @PutMapping("/{id}/confirm")
    public R<RecordVO> confirmReview(
            @Parameter(description = "记录ID", required = true, example = "1")
            @PathVariable Long id,
            @RequestBody(required = false) RecordConfirmDTO body) {
        UUID userId = getCurrentUserId();
        List<TodoResolutionDTO> resolutions = body == null ? null : body.getTodoResolutions();
        RecordVO record = recordService.confirmReview(id, userId,
                body == null ? null : body.getAiMemoryChunkIds(),
                body == null ? null : body.getTodoChunkIds(), resolutions, null, null,
                body == null ? null : body.getGrowthAdjustments(), body == null ? null : body.getAxesVersion());
        return R.ok("片段授权已更新", record);
    }

    /**
     * 审核页待办建议数据
     *
     * 返回该记录 evidence 的 pending 建议（审核窗口绑定），供审核页渲染待办状态选择项。
     * 字段契约：{suggestionId, todoId, todoTitle, todoStatus, suggestedStatus, evidenceChunkId}。
     *
     * @param id 记录ID（须属当前用户且处于人工审查状态；非本人返回空）
     * @return pending 建议列表
     */
    @Operation(
            summary = "审核页待办建议列表",
            description = "返回该记录 evidence 的 pending 建议（审核窗口绑定）。" +
                    "字段：suggestionId / todoId / todoTitle / todoStatus / suggestedStatus / evidenceChunkId。" +
                    "confirm 提交时据此渲染待办状态选择并随 todoResolutions 一起入库。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功",
                    content = @Content(schema = @Schema(implementation = TodoSuggestionVO.RecordSuggestion.class))),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping("/{id}/suggestions")
    public R<List<TodoSuggestionVO.RecordSuggestion>> listSuggestions(
            @Parameter(description = "记录ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        List<TodoSuggestionVO.RecordSuggestion> suggestions =
                todoRegistryService.listRecordSuggestions(id, userId);
        return R.ok("查询成功", suggestions);
    }

    /**
     * 重试失败的记录
     *
     * 仅 FAILED 状态的记录可重试。重置状态为"处理中"并重新触发 AI 管道，
     * 前端继续按 processing 状态轮询（设计文档 4.3，裁决 #21）。
     *
     * @param id 记录ID
     * @return 重试后的记录（状态 PROCESSING）
     */
    @Operation(
            summary = "重试失败记录",
            description = "仅'处理失败'状态的记录可重试。重置为'处理中'并重新触发 AI 管道。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "已重新提交处理",
                    content = @Content(schema = @Schema(implementation = RecordVO.class))),
            @ApiResponse(responseCode = "400", description = "记录状态不允许重试（非 failed）"),
            @ApiResponse(responseCode = "404", description = "记录不存在或已被删除")
    })
    @PostMapping("/{id}/retry")
    public R<ActionProposalVO> retry(
            @Parameter(description = "记录ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        var payload = objectMapper.createObjectNode().put("sourceRecordId", id).put("mode", "AI_ASSISTED");
        var body = new ActionPrepareDTO("record.organize", payload, "record-retry:" + id + ":" + UUID.randomUUID(), null, null);
        return R.ok("已准备重试候选；确认前不会修改记录或重新处理", actionProposalService.prepare(userId, body));
    }

    /**
     * 软删除记录
     * <p>
     * 仅在记录处于"人工审查"（REVIEWING）状态时允许删除。
     * 删除操作是软删除，只是设置 deletedAt 时间戳，不会真正删除数据。
     * 已删除的记录不会出现在查询结果中。
     *
     * @param id 记录ID
     * @return 操作结果
     */
    @Operation(
            summary = "软删除记录",
            description = "仅在'人工审查'状态下允许删除。" +
                    "删除操作是软删除，只是设置删除时间戳，不会真正删除数据。" +
                    "已删除的记录不会出现在查询结果中。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "删除成功",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "记录状态不允许删除（非'人工审查'状态）",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未登录或 Token 无效",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "记录不存在或已被删除",
                    content = @Content
            )
    })
    @DeleteMapping("/{id}")
    public R<String> softDelete(
            @Parameter(description = "记录ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        recordService.softDelete(id, userId);
        return R.ok("记录已删除");
    }

    /**
     * 获取日历标记数据
     *
     * 返回指定月份内每天的有效记录数，用于前端日历组件标记有记录的日期。
     * 只统计未删除且非失败状态的记录。
     *
     * @param month 月份，格式 "2026-08"
     * @return key=日期（如 "2026-08-12"），value=该日有效记录数
     */
    @Operation(
            summary = "获取日历标记数据",
            description = "返回指定月份内每天的有效记录数，用于日历组件标记有记录的日期。" +
                    "只统计未删除且非失败状态的记录。月份格式：2026-08"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "查询成功",
                    content = @Content(schema = @Schema(implementation = Map.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未登录或 Token 无效",
                    content = @Content
            )
    })
    @GetMapping("/calendar")
    public R<Map<String, Integer>> calendar(
            @Parameter(description = "月份，格式 2026-08", required = true, example = "2026-08")
            @RequestParam String month) {
        UUID userId = getCurrentUserId();
        Map<String, Integer> result = recordService.getCalendarDates(month, userId);
        return R.ok("查询成功", result);
    }

    /**
     * 新增片段（审核阶段"增片段"）
     *
     * 用户手写一段新内容，后端同步调用单段分类（single=true）回填 AI 元数据。
     * 分类失败不阻断：metadata 为空照常返回，confirm 时兜底重试（设计文档 5.2/5.6）。
     *
     * @param id 记录ID
     * @param body {"segment": "片段文本"}
     * @return 新建的 Chunk（含 AI 回填的 metadata，如有）
     */
    @Operation(
            summary = "新增片段",
            description = "审核阶段为记录手写新增一个片段，后端自动单段分类回填元数据。" +
                    "只有 REVIEWING 状态的记录允许操作；分类失败不阻断。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "新增成功",
                    content = @Content(schema = @Schema(implementation = ChunkVO.class))),
            @ApiResponse(responseCode = "400", description = "状态不允许或片段为空"),
            @ApiResponse(responseCode = "404", description = "记录不存在")
    })
    @PostMapping("/{id}/chunks")
    public R<ChunkVO> addChunk(
            @Parameter(description = "记录ID", required = true, example = "1")
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        UUID userId = getCurrentUserId();
        String segment = body == null ? null : body.get("segment");
        ChunkVO chunk = chunkService.add(id, segment, userId);
        return R.ok("片段已新增", chunk);
    }
}
