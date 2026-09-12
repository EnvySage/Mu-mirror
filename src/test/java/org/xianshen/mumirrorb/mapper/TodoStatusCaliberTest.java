package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待办状态读取口径回归测试（todo-status-removal-design.md §11）
 *
 * <p>背景：镜子页 TODO 统计卡显示"未开始 4 · 进行中 4 · 完成 1"，登记表真实是
 * "未开始 1 · 进行中 2 · 完成 3"。根因 = 统计 SQL 读 {@code chunk.metadata.taskStatus}
 * （登记时快照，状态变更只写 registry 后即过期）。</p>
 *
 * <p>决策：待办<b>状态类读取统一以 todo_registry 为主表</b>（{@code t.current_status}），
 * chunks 只补展示字段。本测试直接断言 Mapper 注解 SQL，防止口径被改回 chunk 快照
 * （Service 层 mock 单测无法覆盖 SQL 口径，故在此守卫）。</p>
 */
class TodoStatusCaliberTest {

    private static String sql(Class<?> mapper, String method, Class<?>... paramTypes) {
        try {
            Method m = mapper.getMethod(method, paramTypes);
            Select select = m.getAnnotation(Select.class);
            if (select == null || select.value().length == 0) {
                throw new AssertionError(mapper.getSimpleName() + "." + method + " 缺少 @Select SQL");
            }
            return String.join(" ", select.value());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("找不到方法 " + mapper.getSimpleName() + "." + method, e);
        }
    }

    private static void assertRegistryCaliber(String sql) {
        assertTrue(sql.contains("FROM todo_registry"), "主表应为 todo_registry：" + sql);
        assertTrue(sql.contains("t.deleted_at IS NULL"), "必须过滤软删行：" + sql);
        assertTrue(sql.contains("JOIN chunks c ON c.id = t.source_chunk_id"),
                "应 INNER JOIN chunks（排 orphan，与 TodoRegistryMapper.selectOpenTodos 同口径）：" + sql);
        assertFalse(sql.contains("metadata->>'taskStatus'"),
                "待办状态不得再读 chunk 快照 taskStatus（状态变更只写 registry）：" + sql);
    }

    @Test
    @DisplayName("ProfileStatsMapper.selectOpenTodos：registry 主表 + current_status != completed")
    void profileStatsOpenTodos_registryCaliber() {
        String s = sql(ProfileStatsMapper.class, "selectOpenTodos", UUID.class);
        assertRegistryCaliber(s);
        assertTrue(s.contains("t.current_status"), "状态字段应取 registry.current_status：" + s);
        assertTrue(s.contains("!= 'completed'"), "只取未完成：" + s);
        assertTrue(s.contains("ORDER BY"), "排序保持时间倒序：" + s);
        assertTrue(s.contains("r.source = 'user'") && s.contains("r.status = 'done'"),
                "records 侧消费口径保留：" + s);
    }

    @Test
    @DisplayName("ProfileStatsMapper.selectTodoStatusCounts：按 registry.current_status 分组计数")
    void profileStatsTodoCounts_registryCaliber() {
        String s = sql(ProfileStatsMapper.class, "selectTodoStatusCounts", UUID.class);
        assertRegistryCaliber(s);
        assertTrue(s.contains("t.current_status AS status"), "计数维度应为 registry 状态：" + s);
        assertTrue(s.contains("GROUP BY"), "按状态分组：" + s);
    }

    @Test
    @DisplayName("DailySummaryMapper.selectOpenTodos：registry 主表 + 保留昨日窗口")
    void dailySummaryOpenTodos_registryCaliber() {
        String s = sql(DailySummaryMapper.class, "selectOpenTodos",
                UUID.class, OffsetDateTime.class, OffsetDateTime.class);
        assertRegistryCaliber(s);
        assertTrue(s.contains("t.current_status != 'completed'"), "只取未完成：" + s);
        assertTrue(s.contains("r.created_at >= #{dayStart}") && s.contains("r.created_at < #{dayEnd}"),
                "昨日窗口保留（源头记录创建时间）：" + s);
    }

    @Test
    @DisplayName("TodoRegistryMapper.selectOpenChainBase：证据链状态取 registry（不再带 chunk 快照 status）")
    void openChainBase_registryCaliber() {
        String s = sql(TodoRegistryMapper.class, "selectOpenChainBase", UUID.class, int.class);
        assertTrue(s.contains("FROM todo_registry"), "主表应为 todo_registry：" + s);
        assertTrue(s.contains("t.deleted_at IS NULL"), "必须过滤软删行：" + s);
        assertFalse(s.contains("chunkStatus"), "证据链不应再取 chunk 快照状态：" + s);
        assertTrue(s.contains("t.current_status AS currentStatus"), "状态取 registry：" + s);
    }
}
