package org.xianshen.mumirrorb.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.service.SummaryService;

import java.util.List;

/**
 * 每日总结定时任务（设计文档 6.7：每天 01:00 Asia/Shanghai）
 *
 * <p>逐用户生成昨日日报；单用户失败不影响其他用户。
 * 跳过条件（幂等）：昨日无记录 / 未配置 LLM / 该日期日报已存在。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySummaryScheduler {

    private final SettingsMapper settingsMapper;
    private final SummaryService summaryService;

    @Scheduled(cron = "0 0 1 * * ?", zone = "Asia/Shanghai")
    public void generateDailySummaries() {
        log.info("============ 每日总结定时任务开始 ============");
        List<UserSettings> all = settingsMapper.selectList(null);
        int generated = 0;
        for (UserSettings settings : all) {
            try {
                if (summaryService.generateForUser(settings.getUserId()) != null) {
                    generated++;
                }
            } catch (Exception e) {
                log.error("用户 {} 每日总结生成失败", settings.getUserId(), e);
            }
        }
        log.info("============ 每日总结定时任务结束，生成 {} 份 ============", generated);
    }
}
