package org.xianshen.mumirrorb.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 记录处理状态枚举（4 种状态）
 *
 * <p><strong>状态流转：</strong></p>
 * <pre>
 *   PROCESSING → REVIEWING → DONE
 *       ↓
 *     FAILED
 * </pre>
 *
 * <p><strong>各状态说明：</strong></p>
 * <ul>
 *   <li><strong>PROCESSING（处理中）</strong>：用户提交内容后，AI 正在处理。前端显示转圈动画。</li>
 *   <li><strong>REVIEWING（人工审查）</strong>：AI 处理完成，生成了标题、摘要、标签等。等待用户审核。
 *       <ul>
 *         <li>用户可以查看 AI 生成的标签</li>
 *         <li>用户可以修改标签（调用更新接口）</li>
 *         <li>用户可以删除记录（调用软删除接口）</li>
 *         <li>用户确认后，调用确认接口将状态改为 DONE</li>
 *       </ul>
 *   </li>
 *   <li><strong>DONE（已完成）</strong>：用户已确认标签无误。记录进入最终状态，不可再修改。</li>
 *   <li><strong>FAILED（处理失败）</strong>：AI 处理失败。显示错误信息和重新尝试按钮。</li>
 * </ul>
 *
 * <p><strong>业务规则：</strong></p>
 * <ul>
 *   <li>只有 REVIEWING 状态的记录才能修改和删除</li>
 *   <li>DONE 状态的记录是只读的</li>
 *   <li>PROCESSING 状态的记录正在处理中，不可操作</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
@Schema(description = "记录处理状态枚举")
public enum RecordStatus {

    /**
     * 处理中 - AI 正在处理
     *
     * <p>用户提交内容后的初始状态</p>
     * <p>前端应显示转圈动画或加载提示</p>
     */
    @Schema(description = "处理中 - AI正在处理内容")
    PROCESSING("processing", "处理中"),

    /**
     * 人工审查 - AI 处理完成，等待用户审核
     *
     * <p>AI 已生成标题、摘要、标签等信息</p>
     * <p>用户可以：</p>
     * <ul>
     *   <li>查看 AI 生成的标签</li>
     *   <li>修改标签（调用 PUT /api/records/{id}）</li>
     *   <li>删除记录（调用 DELETE /api/records/{id}）</li>
     *   <li>确认完成（调用 PUT /api/records/{id}/confirm）</li>
     * </ul>
     */
    @Schema(description = "人工审查 - AI处理完成，等待用户审核标签")
    REVIEWING("reviewing", "人工审查"),

    /**
     * 已完成 - 用户已确认
     *
     * <p>用户已审核并确认标签无误</p>
     * <p>记录进入最终状态，不可再修改或删除</p>
     */
    @Schema(description = "已完成 - 用户已确认标签")
    DONE("done", "已完成"),

    /**
     * 处理失败 - AI 处理失败
     *
     * <p>AI 处理过程中发生错误</p>
     <p>前端应显示错误信息和重新尝试按钮</p>
     */
    @Schema(description = "处理失败 - AI处理过程中发生错误")
    FAILED("failed", "处理失败");

    /**
     * 存入数据库的值
     */
    @EnumValue
    @JsonValue
    private final String value;

    /**
     * 中文显示名
     */
    private final String label;
}
