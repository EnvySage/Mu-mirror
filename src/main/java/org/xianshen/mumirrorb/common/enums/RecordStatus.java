package org.xianshen.mumirrorb.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 记录处理状态枚举（设计文档 3 种状态）
 *
 * processing → AI 正在处理（前端显示转圈动画）
 * done       → 全部完成（正常显示标签）
 * failed     → 处理失败（显示错误 + 重新尝试按钮）
 */
@Getter
@AllArgsConstructor
public enum RecordStatus {

    PROCESSING("processing", "处理中"),
    DONE("done", "已完成"),
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
