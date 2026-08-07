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
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.service.RecordService;

import java.util.List;
import java.util.UUID;

/**
 * 记录管理控制器
 *
 * 提供日记记录的完整 CRUD 操作，包括：
 * - 创建记录（用户提交原始内容）
 * - 查询记录列表（支持按日期范围筛选）
 * - 获取单条记录详情
 * - 更新记录（仅在人工审查状态下允许修改 AI 生成的标签）
 * - 确认审查完成（将状态从"人工审查"改为"已完成"）
 * - 软删除记录（仅在人工审查状态下允许）
 *
 * 状态流转：PROCESSING → REVIEWING → DONE
 */
@Tag(name = "记录管理", description = "日记记录的增删改查操作")
@RestController
@RequestMapping("/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

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
     * 用户提交原始内容，系统将创建一条新记录，状态为"处理中"（PROCESSING）。
     * AI 服务会异步处理内容，生成标题、摘要、标签等信息。
     * 处理完成后，记录状态变为"人工审查"（REVIEWING），等待用户审核。
     *
     * @param dto 记录数据（只需提供 content 字段）
     * @return 创建成功的记录（状态为 PROCESSING）
     */
    @Operation(
            summary = "创建记录",
            description = "用户提交原始内容，系统创建新记录并触发 AI 处理。" +
                    "创建后记录状态为'处理中'，AI 处理完成后变为'人工审查'状态。"
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
        return R.ok("记录已提交", record);
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
     * 根据记录 ID 获取详细信息，包括 AI 生成的标签、情绪等。
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
     * 更新记录
     *
     * 仅在记录处于"人工审查"（REVIEWING）状态时允许更新。
     * 可以修改 AI 生成的字段：标题、摘要、内容类型、情绪标签、关键词。
     * 不能修改用户的原始内容（content）。
     *
     * 更新后，记录的 userReviewed 标记会设为 true，表示用户已审核。
     * 如果需要将状态改为"已完成"，请调用"确认审查完成"接口。
     *
     * @param id  记录ID
     * @param dto 更新数据（只需提供需要修改的字段）
     * @return 更新后的记录
     */
    @Operation(
            summary = "更新记录",
            description = "仅在'人工审查'状态下允许更新。" +
                    "可以修改标题、摘要、内容类型、情绪标签、关键词等 AI 生成的字段。" +
                    "不能修改用户的原始内容。" +
                    "更新后请调用'确认审查完成'接口将状态改为'已完成'。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "更新成功",
                    content = @Content(schema = @Schema(implementation = RecordVO.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "参数错误或记录状态不允许修改",
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
    @PutMapping("/{id}")
    public R<RecordVO> update(
            @Parameter(description = "记录ID", required = true, example = "1")
            @PathVariable Long id,
            @Valid @RequestBody RecordDTO dto) {
        UUID userId = getCurrentUserId();
        RecordVO record = recordService.update(id, dto, userId);
        return R.ok("记录已更新", record);
    }

    /**
     * 确认审查完成
     *
     * 将记录状态从"人工审查"（REVIEWING）改为"已完成"（DONE）。
     * 调用此接口表示用户已确认 AI 生成的标签无误，或已完成修改。
     *
     * 只有处于"人工审查"状态的记录才能调用此接口。
     *
     * @param id 记录ID
     * @return 状态更新后的记录
     */
    @Operation(
            summary = "确认审查完成",
            description = "将记录状态从'人工审查'改为'已完成'。" +
                    "表示用户已确认 AI 生成的标签无误，或已完成修改。" +
                    "只有'人工审查'状态的记录才能调用此接口。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "确认成功",
                    content = @Content(schema = @Schema(implementation = RecordVO.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "记录状态不允许确认（非'人工审查'状态）",
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
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        RecordVO record = recordService.confirmReview(id, userId);
        return R.ok("审查已完成", record);
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
}
