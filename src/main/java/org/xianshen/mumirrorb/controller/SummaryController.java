package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.DailySummaryVO;
import org.xianshen.mumirrorb.service.SummaryService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 每日总结控制器（设计文档 6.7 / 十章）
 *
 * <p>日报走独立入口（source='system' 的记录不混入记录流，8.4）。</p>
 */
@Tag(name = "每日总结", description = "系统生成的每日日报查询")
@Slf4j
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
     * <p>不带 date：返回日报列表（新→旧，content 为空、highlights 取前 3 行），
     * <strong>游标分页</strong>——limit 每页条数（默认 20，上限 50），
     * before 为 summary_date 游标（只返回严格早于该日期的日报，不传 = 从最新一篇开始）。
     * 前端判底：返回条数 &lt; limit 或为空即没有更早的日报了。
     * 带 date（YYYY-MM-DD）：返回该日单篇（含全文 content），忽略 limit/before。</p>
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
            @RequestParam(required = false) String date,
            @Parameter(description = "每页条数（1~50，默认 20）", example = "7")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "游标：只返回 summary_date 早于该日期的日报（YYYY-MM-DD）", example = "2026-09-02")
            @RequestParam(required = false) String before) {
        UUID userId = getCurrentUserId();
        return R.ok("查询成功", summaryService.list(userId, date, limit, before));
    }

    /**
     * 查询最近 N 天内"有记录但缺日报"的日期（新→旧）
     *
     * <p>给前端在日报流里插入「未生成」行用：按钮挂在缺失的那一天上，而不是挂列表顶部。</p>
     */
    @Operation(
            summary = "查询缺失日报的日期",
            description = "返回最近 days 天内「有记录但没有日报」的日期（yyyy-MM-DD，新→旧），用于渲染补生成入口。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功（无缺失返回空数组）"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping("/missing")
    public R<List<String>> missing(
            @Parameter(description = "回溯天数（1~30，默认 7）", example = "7")
            @RequestParam(required = false, defaultValue = "7") int days) {
        UUID userId = getCurrentUserId();
        return R.ok("查询成功", summaryService.listMissingDates(userId, days));
    }

    /**
     * 回溯补生成最近 N 天缺失的日报
     *
     * <p>解决"某天没生成成功 → 那天永远不在列表里 → 连重新生成的入口都没有"的死结：
     * 按天回溯，对"有记录但缺日报"的日期逐个补生成（幂等，已有则跳过）。</p>
     *
     * <p>阻塞接口：每天一次 LLM 调用，days 上限 7；调用方需放宽超时。</p>
     */
    @Operation(
            summary = "回溯补生成最近 N 天缺失的日报",
            description = "按天回溯（1=昨天），只补「有记录但缺日报」的日期；已有日报跳过。days 上限 7，接口阻塞。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "补生成完成，返回补了几份"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping("/backfill")
    public R<Integer> backfill(
            @Parameter(description = "回溯天数（1~7，默认 3）", example = "3")
            @RequestParam(required = false, defaultValue = "3") int days) {
        UUID userId = getCurrentUserId();
        int n = Math.max(1, Math.min(days, 7));
        LocalDate today = LocalDate.now();
        int created = 0;
        for (int d = 1; d <= n; d++) {
            try {
                if (summaryService.generateForUser(userId, today.minusDays(d), false) != null) {
                    created++;
                }
            } catch (Exception e) {
                log.warn("用户 {} 回溯补生成失败（{} 天前）：{}", userId, d, e.getMessage());
            }
        }
        return R.ok(created > 0 ? "已补生成 " + created + " 份日报" : "最近 " + n + " 天没有缺失的日报", created);
    }

    /**
     * 补生成 / 重生成指定日期的日报
     *
     * <p>日报只能由 01:00 定时任务生成，且只跑"昨天"——某天失败（LLM 超时/限流/服务重启）
     * 就再也不会补。这个接口补上手工入口：
     *   - 该日期还没有日报 → 生成（补生成）
     *   - 已有日报 + force=true → 删旧重建（重生成）
     *   - 已有日报 + force=false → 不动，返回 null</p>
     *
     * <p>返回 null 表示"无需生成"：该日无记录 / 未配置模型 / 日报已存在（force=false）。</p>
     */
    @Operation(
            summary = "补生成 / 重生成指定日期的日报",
            description = "date=YYYY-MM-DD（必须早于今天）；force=true 时先删旧日报再重建。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "生成成功；data 为 null 表示无需生成"),
            @ApiResponse(responseCode = "400", description = "日期格式错误或不是过去的日期"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping("/regenerate")
    public R<DailySummaryVO> regenerate(
            @Parameter(description = "日报日期（YYYY-MM-DD，必须早于今天）", example = "2026-09-15")
            @RequestParam String date,
            @Parameter(description = "是否覆盖已有日报", example = "false")
            @RequestParam(required = false, defaultValue = "false") boolean force) {
        UUID userId = getCurrentUserId();
        LocalDate day;
        try {
            day = LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "日期格式应为 YYYY-MM-DD");
        }
        DailySummaryVO vo = summaryService.generateForUser(userId, day, force);
        return vo != null
                ? R.ok("日报已生成", vo)
                : R.ok("该日期无需生成（没有记录 / 未配置模型 / 日报已存在）", null);
    }
}
