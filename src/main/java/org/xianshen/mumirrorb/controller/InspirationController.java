package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.pojo.DTO.InspirationRequestDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.InspirationVO;
import org.xianshen.mumirrorb.service.InspirationService;

import java.util.UUID;

/**
 * 写作灵感控制器（设计文档 6.8 / 十章）
 *
 * <p>前端在输入停顿 &gt;30s 时调用；阻塞接口（含 Embed + 检索 + LLM，数秒）。
 * 草稿与灵感均不落库。</p>
 */
@Tag(name = "写作灵感", description = "基于历史记录的写作方向提示")
@RestController
@RequestMapping("/inspiration")
@RequiredArgsConstructor
public class InspirationController {

    private final InspirationService inspirationService;

    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    @Operation(
            summary = "写作灵感",
            description = "基于当前草稿与相关历史记录生成 2-3 条写作方向。阻塞接口，前端停顿 30s 后触发。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "生成成功"),
            @ApiResponse(responseCode = "400", description = "草稿为空"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效"),
            @ApiResponse(responseCode = "500", description = "AI 服务不可达")
    })
    @PostMapping
    public R<InspirationVO> inspire(@Valid @RequestBody InspirationRequestDTO dto) {
        UUID userId = getCurrentUserId();
        return R.ok("已生成灵感", inspirationService.inspire(userId, dto.getDraft()));
    }
}
