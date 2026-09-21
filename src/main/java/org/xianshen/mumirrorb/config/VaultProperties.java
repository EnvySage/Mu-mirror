package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Vault 配置（toolcalling-vault-design.md 3.1：全部配置化）
 *
 * <p>application.yml 前缀 {@code vault.*}；默认值即设计稿首版参数。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vault")
public class VaultProperties {

    /** 单文件上限（字节），默认 20MB */
    private long maxFileSizeBytes = 20L * 1024 * 1024;

    /** 每用户总配额（字节），默认 500MB */
    private long quotaBytes = 500L * 1024 * 1024;

    /** 类型白名单（magic bytes 判定的 mime 前缀组） */
    private List<String> allowedTypes = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // docx
            "text/plain", "text/markdown", "text/csv",
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "audio/mpeg", "audio/wav", "audio/x-m4a", "audio/mp4");

    /** digest 全消化文本上限（token 近似=字符数/2，超限只索引前 N 章）；默认 5 万 token ≈ 10 万字符 */
    private int digestMaxChars = 100_000;

    /**
     * 规划**单步**超时（毫秒）。chat-loop-design.md §4.3：必须 < mirror.sse-timeout-ms，
     * 否则单步跑满时 SSE 连接会先断（改造前 135s > 120s 就是这个隐患）。
     */
    private long planToolsTimeoutMs = 120_000;

    /** PlanTools（旧单次规划路径）单次对话最多工具步数 */
    private int maxToolCalls = 2;

    /**
     * 对话 Agent 循环开关（chat-loop-design.md 裁决 0.4 的回滚闸）：
     * false 时退回旧的 PlanTools 单次规划链路，行为与改造前一致。
     */
    private boolean chatLoopEnabled = true;

    /** 循环步数上限（chat-loop-design.md §4.1 终止条件 3） */
    private int maxLoopSteps = 4;

    /** 循环累计耗时预算（毫秒；§4.1 终止条件 4）——规划 + 工具执行合计 */
    private long loopBudgetMs = 180_000;

    /**
     * 规划器历史窗口（轮）。与生成答案的 ChatServiceImpl.HISTORY_ROUNDS=3 **故意不同**：
     * 那 3 轮是为了防止模型顺着上文措辞跑偏（见 ChatServiceImpl:65-66），而规划器输出 JSON
     * 不输出散文，该风险不成立，但指代消解（"我焦虑怎么办"接上一问）需要更多上文。
     */
    private int planHistoryRounds = 6;

    /** find_item 结果上限 */
    private int findItemLimit = 10;

    /** search_records 结果上限（设计稿 limit≤20） */
    private int searchRecordsLimit = 20;
}
