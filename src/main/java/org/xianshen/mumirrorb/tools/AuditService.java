package org.xianshen.mumirrorb.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.mapper.ToolCallMapper;
import org.xianshen.mumirrorb.pojo.DO.ToolCall;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * 工具审计服务（tool_calls 落库；论文三档消融 + 频率/成功率/延迟图表素材）
 *
 * <p>每次执行（含失败）落一行；写失败只打日志，绝不影响对话主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int SUMMARY_MAX = 500;

    private final ToolCallMapper toolCallMapper;

    /**
     * 落审计（异步语义由调用方自行决定；本方法自身吞异常）
     */
    public void record(UUID userId, UUID sessionId, String tool, Map<String, Object> args,
                       String summary, boolean success, long latencyMs) {
        try {
            toolCallMapper.insert(ToolCall.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .tool(tool)
                    .args(args == null ? Map.of() : args)
                    .resultSummary(truncate(summary))
                    .success(success)
                    .latencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE))
                    .createdAt(OffsetDateTime.now(ZONE))
                    .build());
        } catch (Exception e) {
            log.warn("tool_calls 审计落库失败（不影响对话），tool: {}, 原因: {}", tool, e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > SUMMARY_MAX ? s.substring(0, SUMMARY_MAX) : s;
    }
}
