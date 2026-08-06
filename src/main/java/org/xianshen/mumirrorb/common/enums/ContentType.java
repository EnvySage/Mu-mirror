package org.xianshen.mumirrorb.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 内容类型枚举（设计文档定死的 8 种，不支持用户自定义）
 * 数据库存储英文小写值，前端显示中文
 */
@Getter
@AllArgsConstructor
public enum ContentType {

    TODO("todo", "待办"),
    THOUGHT("thought", "感想"),
    LEARNING("learning", "学习"),
    PLAN("plan", "计划"),
    NOTE("note", "随记"),
    WORK("work", "工作"),
    SOCIAL("social", "社交"),
    HEALTH("health", "健康");

    /**
     * 存入数据库的值（英文小写）
     */
    @EnumValue
    @JsonValue
    private final String value;

    /**
     * 中文显示名
     */
    private final String label;
}
