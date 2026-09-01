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
import org.xianshen.mumirrorb.pojo.DTO.ChunkDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.ChunkVO;
import org.xianshen.mumirrorb.service.ChunkService;

import java.util.UUID;

/**
 * Chunk 管理控制器
 *
 * <p>审核阶段用户可修改 Chunk 的 segment 和 AI 元数据。</p>
 */
@Tag(name = "Chunk管理", description = "审核阶段修改Chunk的segment和元数据")
@RestController
@RequestMapping("/chunks")
@RequiredArgsConstructor
public class ChunkController {

    private final ChunkService chunkService;

    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    /**
     * 更新 Chunk
     *
     * 仅在记录处于 REVIEWING 状态时允许修改。
     * 可修改 segment（主题片段）和 AI 元数据（title, contentType, mood 等）。
     */
    @Operation(
            summary = "更新Chunk",
            description = "审核阶段修改Chunk的segment和AI元数据。只有REVIEWING状态的记录才允许修改。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "更新成功",
                    content = @Content(schema = @Schema(implementation = ChunkVO.class))),
            @ApiResponse(responseCode = "400", description = "参数错误或状态不允许修改"),
            @ApiResponse(responseCode = "404", description = "Chunk不存在")
    })
    @PutMapping("/{id}")
    public R<ChunkVO> update(
            @Parameter(description = "Chunk ID", required = true, example = "1")
            @PathVariable Long id,
            @Valid @RequestBody ChunkDTO dto) {
        UUID userId = getCurrentUserId();
        ChunkVO chunk = chunkService.update(id, dto, userId);
        return R.ok("Chunk 已更新", chunk);
    }
}
