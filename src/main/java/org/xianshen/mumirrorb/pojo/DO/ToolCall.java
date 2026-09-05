package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.common.typehandler.JsonbMapTypeHandler;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 工具调用审计实体（对应 tool_calls 表，toolcalling-vault-design.md 第 2 节）
 *
 * <p>每次工具执行落一行（含失败）——论文三档消融 + 工具频率/成功率/延迟图表素材。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "tool_calls", autoResultMap = true)
@Schema(description = "工具调用审计 - 对应 tool_calls 表")
public class ToolCall {

    @TableId(type = IdType.AUTO)
    @Schema(description = "审计ID", example = "1")
    private Long id;

    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "用户ID")
    private UUID userId;

    /** 关联对话会话（非对话触发为 null，如定时/手动） */
    @Schema(description = "会话ID（可空）")
    private UUID sessionId;

    @Schema(description = "工具名", example = "search_records")
    private String tool;

    /** 执行参数 JSONB */
    @TableField(typeHandler = JsonbMapTypeHandler.class)
    @Schema(description = "执行参数", example = "{\"days\": 7}")
    private Map<String, Object> args;

    /** 输出摘要（≤500 字符截断，同 meta.tools_used 芯片文案） */
    @Schema(description = "结果摘要", example = "search_records:12条")
    private String resultSummary;

    @Schema(description = "是否成功", example = "true")
    private Boolean success;

    /** 执行耗时（毫秒） */
    @Schema(description = "耗时毫秒", example = "35")
    private Integer latencyMs;

    @Schema(description = "发生时间")
    private OffsetDateTime createdAt;
}
