package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.DailySummaryVO;
import org.xianshen.mumirrorb.service.SummaryService;

import java.util.List;
import java.util.UUID;

/**
 * 每日总结控制器（设计文档 6.7 / 十章）
 *
 * <p>日报走独立入口（source='system' 的记录不混入记录流，8.4）。</p>
 */
@Tag(name = "每日总结", description = "系统生成的每日日报查询")
@RestController
@RequestMapping("/summaries")
@RequiredArgsConstructor
public class SummaryController {

    private final SummaryService summaryService;

    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    /**
     * 查询每日总结
     *
     * <p>不带 date：返回全部日报列表（新→旧，content 为空、highlights 取前 3 行）。
     * 带 date（YYYY-MM-DD）：返回该日单篇（含全文 content）。</p>
     */
    @Operation(
            summary = "查询每日总结",
            description = "不带 date 返回全部日报列表（新→旧，不含全文）；带 date=YYYY-MM-DD 返回单篇（含全文）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功（无日报返回空数组）"),
            @ApiResponse(responseCode = "400", description = "日期格式错误"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping
    public R<List<DailySummaryVO>> list(
            @Parameter(description = "日报日期（YYYY-MM-DD，可空）", example = "2026-09-02")
            @RequestParam(required = false) String date) {
        UUID userId = getCurrentUserId();
        return R.ok("查询成功", summaryService.list(userId, date));
    }
}
