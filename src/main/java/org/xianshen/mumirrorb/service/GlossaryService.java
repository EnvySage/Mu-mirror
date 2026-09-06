package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.GlossaryCreateDTO;
import org.xianshen.mumirrorb.pojo.DTO.GlossaryUpdateDTO;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;

import java.util.List;
import java.util.UUID;

/**
 * 个人词典服务（lexicon-design.md 第 2/4/5c 节）
 *
 * <p>职责：词条 CRUD 与状态机（pending/confirmed/dismissed）、confirmed 注入列表
 * （top 30 截断 + 60s 进程内缓存）、ExtractIntent query 侧匹配、ExtractTerms 抽取调度（手动触发）。</p>
 */
public interface GlossaryService {

    /**
     * 三组分好列表（5b 设置页"个人词典"卡）
     *
     * <p>confirmed 项附 queryHitCount 与"近30天相关记录数"。</p>
     */
    List<UserTermVO> list(UUID userId);

    /**
     * 三组分好列表
     */
    GlossaryGroupVO listGrouped(UUID userId);

    /**
     * 手动新增（"教镜子一个词"）：直接 confirmed
     *
     * <p>term 与用户已有词条重复 → PARAM_ERROR（唯一约束 user_id+term）。</p>
     */
    UserTermVO create(UUID userId, GlossaryCreateDTO dto);

    /**
     * 编辑词条/别名/解释（解释更新后回 confirmed 并刷新 last_confirmed_at，重新计时注入）
     */
    UserTermVO update(Long id, GlossaryUpdateDTO dto, UUID userId);

    /**
     * 删除词条（物理删除；CRUD 入口的删除与 dismiss 不同：dismiss 沉底保留，删除真删）
     */
    void delete(Long id, UUID userId);

    /**
     * 确认候选：pending/dismissed → confirmed（记录 last_confirmed_at；update 建议确认时覆盖解释）
     *
     * <p>状态机不合法（confirmed 重复确认）→ PARAM_ERROR。</p>
     *
     * @param newDescription 可选：update 候选的新解释建议（null 时保留原解释）
     * @param newAliases     可选：合并建议新增的别名（null 时保留原别名）
     */
    UserTermVO confirm(Long id, UUID userId, String newDescription, List<String> newAliases);

    /**
     * 忽略候选：pending/confirmed → dismissed（不删行，30 天后可重新浮现）
     */
    UserTermVO dismiss(Long id, UUID userId);

    /**
     * confirmed 注入列表：按 query_hit_count 排序 top 30 截断，60s 进程内缓存
     *
     * <p>Classify/GenerateProfile/Chat 传全量；ExtractIntent 未命中时也传它作 grounding。</p>
     */
    List<UserTermVO> confirmedForInjection(UUID userId);

    /**
     * ExtractIntent query 侧匹配：term/alias 与 query 字符串包含匹配
     *
     * <p>命中词条 query_hit_count++（落库）；返回命中词条（空时返回 top 高频词 grounding，
     * 由调用方决定——本方法返回命中子集，可空）。</p>
     */
    List<UserTermVO> matchQueryTerms(UUID userId, String query);

    /**
     * 手动触发候选抽取（5c：POST /api/glossary/extract，懒人立即出候选）
     *
     * <p>语料=近 14 天用户日记 chunks（fix-batch B2：status='done' AND source='user' 收口），
     * 30 天去重窗。Python 侧 ExtractTerms 未上线时降级为空（见实现）。</p>
     *
     * @return 本次新增 pending 候选词条卡列表（fix-batch C5 F 契约：响应对齐 {candidates:[...]}）
     */
    List<org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO> extractForUser(UUID userId);

    /**
     * 定时抽取入口（DailySummaryScheduler 01:00 顺路调用）
     *
     * <p>失败只打日志，绝不影响每日总结主流程。</p>
     */
    void extractScheduled(UUID userId);

    /**
     * 月度维护入口（MonthlyMirrorScheduler 每月 1 号顺路调用）：
     * ① 词条合并（重复/矛盾词生成 aliases 合并建议 → pending 复核）
     * ② 漂移审计（confirmed 解释 vs 近 30 天语料，不一致打回 pending + 新解释建议）
     */
    void monthlyMaintenance(UUID userId);
}
