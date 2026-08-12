package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录服务接口
 *
 * <p>提供日记记录的核心业务逻辑，包括：</p>
 * <ul>
 *   <li>创建记录（提交原始内容，触发 AI 处理）</li>
 *   <li>查询记录列表（支持按日期范围筛选）</li>
 *   <li>获取记录详情</li>
 *   <li>更新记录（仅在人工审查状态下允许）</li>
 *   <li>确认审查完成（将状态从 REVIEWING 改为 DONE）</li>
 *   <li>软删除记录（仅在人工审查状态下允许）</li>
 * </ul>
 *
 * <p><strong>业务规则：</strong></p>
 * <ul>
 *   <li>只有 REVIEWING 状态的记录才能修改和删除</li>
 *   <li>更新时只能修改 AI 生成的字段，不能修改原始内容</li>
 *   <li>删除是软删除，设置 deletedAt 时间戳</li>
 *   <li>查询时自动过滤已删除的记录</li>
 * </ul>
 */
public interface RecordService {

    /**
     * 创建记录（提交 → 清洗 → AI 处理 → 保存）
     *
     * <p>创建流程：</p>
     * <ol>
     *   <li>校验内容非空</li>
     *   <li>创建记录，状态设为 PROCESSING</li>
     *   <li>发布事件，触发 AI 异步处理</li>
     *   <li>立即返回（前端显示加载动画）</li>
     * </ol>
     *
     * @param dto    记录数据（只需提供 content 字段）
     * @param userId 当前用户ID
     * @return 创建成功的记录（状态为 PROCESSING）
     */
    RecordVO create(RecordDTO dto, UUID userId);

    /**
     * 查询记录列表（按日期范围筛选）
     *
     * <p>查询规则：</p>
     * <ul>
     *   <li>不传参数：默认查询今天的记录</li>
     *   <li>只传 startDate：查询该日期的记录</li>
     *   <li>只传 endDate：查询该日期的记录</li>
     *   <li>两个都传：查询日期范围内的记录（包含边界）</li>
     * </ul>
     *
     * <p>结果按创建时间降序排列（最新在前），自动过滤已删除的记录。</p>
     *
     * @param queryDTO 查询条件（startDate, endDate 均为可选）
     * @param userId   当前用户ID
     * @return 记录列表
     */
    List<RecordVO> list(RecordQueryDTO queryDTO, UUID userId);

    /**
     * 根据ID获取记录详情
     *
     * <p>只能查看自己的记录，自动过滤已删除的记录。</p>
     *
     * @param recordId 记录ID
     * @param userId   当前用户ID（验证所有权）
     * @return 记录视图
     */
    RecordVO getById(Long recordId, UUID userId);

    /**
     * 更新记录（仅在人工审查状态下允许）
     *
     * <p>更新规则：</p>
     * <ul>
     *   <li>只有 REVIEWING 状态的记录才能更新</li>
     *   <li>只能修改 AI 生成的字段：title, summary, contentType, mood, keywords</li>
     *   <li>不能修改原始内容（content）</li>
     *   <li>更新后 userReviewed 会自动设为 true</li>
     * </ul>
     *
     * @param recordId 记录ID
     * @param dto      更新数据（只需提供需要修改的字段）
     * @param userId   当前用户ID（验证所有权）
     * @return 更新后的记录视图
     */
    RecordVO update(Long recordId, RecordDTO dto, UUID userId);

    /**
     * 确认审查完成（将状态从 REVIEWING 改为 DONE）
     *
     * <p>调用此接口表示用户已确认 AI 生成的标签无误，或已完成修改。</p>
     * <p>只有 REVIEWING 状态的记录才能调用此接口。</p>
     *
     * @param recordId 记录ID
     * @param userId   当前用户ID（验证所有权）
     * @return 更新后的记录视图
     */
    RecordVO confirmReview(Long recordId, UUID userId);

    /**
     * 软删除记录（设置 deletedAt 时间戳）
     *
     * <p>删除规则：</p>
     * <ul>
     *   <li>只有 REVIEWING 状态的记录才能删除</li>
     *   <li>删除是软删除，只是设置 deletedAt 时间戳</li>
     *   <li>已删除的记录不会出现在查询结果中</li>
     * </ul>
     *
     * @param recordId 记录ID
     * @param userId   当前用户ID（验证所有权）
     */
    void softDelete(Long recordId, UUID userId);

    /**
     * 获取指定月份每天的有效记录数（用于日历标记）
     *
     * @param month 月份，格式 "2026-08"
     * @param userId 当前用户ID
     * @return key=日期（如 "2026-08-12"），value=该日有效记录数
     */
    Map<String, Integer> getCalendarDates(String month, UUID userId);
}
