package org.xianshen.mumirrorb.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 情绪标签枚举（设计文档定死的 13 种，支持多选，不支持用户自定义）
 * 数据库以 JSONB 数组存储，如 ["happy", "calm"]
 * 前端显示中文
 */
@Getter
@AllArgsConstructor
public enum MoodType {

    HAPPY("happy", "开心"),
    EXCITED("excited", "兴奋"),
    SATISFIED("satisfied", "满足"),
    GRATEFUL("grateful", "感恩"),
    EXPECTING("expecting", "期待"),
    CALM("calm", "平静"),
    BORED("bored", "无聊"),
    CONFUSED("confused", "困惑"),
    ANXIOUS("anxious", "焦虑"),
    SAD("sad", "难过"),
    ANGRY("angry", "愤怒"),
    EXHAUSTED("exhausted", "疲惫"),
    STRESSED("stressed", "压力");

    /**
     * 存入数据库的值（英文小写）
     */
    @JsonValue
    private final String value;

    /**
     * 中文显示名
     */
    private final String label;

    /**
     * 反序列化：根据 value 字符串找到对应枚举
     */
    @JsonCreator
    public static MoodType fromValue(String value) {
        for (MoodType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的情绪类型: " + value);
    }
}
