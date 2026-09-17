package org.xianshen.mumirrorb.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.service.SummaryService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 每日总结定时任务（设计文档 6.7：每天 01:00 Asia/Shanghai）
 *
 * <p>逐用户生成昨日日报；单用户失败不影响其他用户。
 * 跳过条件（幂等）：昨日无记录 / 未配置 LLM / 该日期日报已存在。</p>
 *
 * <p>顺路任务（lexicon-design.md 第 3 节，不新增用户等待路径 #0.5）：
 * 生成日报后调 ExtractTerms RPC 抽取个人词典候选（近 14 天 confirmed chunks，
 * user_edited 优先，30 天去重窗）。抽取失败只打日志，绝不影响每日总结主流程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySummaryScheduler {

    private final SettingsMapper settingsMapper;
    private final SummaryService summaryService;
    private final GlossaryService glossaryService;

    /**
     * 回溯补跑窗口：近 N 天内"有记录但缺日报"的日期都会被补生成。
     *
     * 只跑昨天的话，某天失败（LLM 超时/限流/服务重启）就永久丢失——回溯让失败自愈。
     * 补跑靠 generateForUser 的幂等 + 无记录跳过兜底，重复执行不会重复生成。
     */
    private static final int BACKFILL_DAYS = 7;

    @Scheduled(cron = "0 0 1 * * ?", zone = "Asia/Shanghai")
    public void generateDailySummaries() {
        log.info("============ 每日总结定时任务开始 ============");
        List<UserSettings> all = settingsMapper.selectList(null);
        int generated = 0;
        int extracted = 0;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (UserSettings settings : all) {
            // 回溯补跑：1=昨天（主流程），2..N=历史缺失补生成
            for (int d = 1; d <= BACKFILL_DAYS; d++) {
                try {
                    if (summaryService.generateForUser(settings.getUserId(), today.minusDays(d), false) != null) {
                        generated++;
                    }
                } catch (Exception e) {
                    log.error("用户 {} 日报生成失败（{} 天前）", settings.getUserId(), d, e);
                }
            }
            // 顺路：个人词典候选抽取（内部全量 try-catch，主流程零影响）
            try {
                glossaryService.extractScheduled(settings.getUserId());
                extracted++;
            } catch (Exception e) {
                log.warn("用户 {} 词典抽取调度异常（已忽略）", settings.getUserId(), e);
            }
        }
        log.info("============ 每日总结定时任务结束，生成 {} 份，词典抽取处理 {} 用户 ============", generated, extracted);
    }
}
