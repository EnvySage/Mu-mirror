package org.xianshen.mumirrorb.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 时间词替换执行器：把 AI 判别出的相对时间词替换成绝对日期
 *
 * <p><b>分工</b>：AI 负责"识别 + 计算"（{@code ClassifyItem.time_substitutions}），
 * B 侧只负责"执行替换"。不让 LLM 直接吐改写后的全文——它会顺手润色掉非时间内容，
 * 而 segment 既要展示给用户、又要进 embedding，改坏了代价大。</p>
 *
 * <p><b>零回归</b>：AI 未返回 / 全部脏值 / 词在文本里不存在 → 原样返回，绝不阻断分类，
 * 退化为"『今天』仍是原词"（与本次改动前一致）。</p>
 *
 * <p><b>两个必须处理的坑</b>：</p>
 * <ol>
 *   <li><b>长词优先</b>：{@code "大后天"} 若先被 {@code "后天"} 替换会变成 {@code "大9月21日"}。
 *       故按 original 长度降序替换。</li>
 *   <li><b>逐条存在性检查</b>：每替换一条后重新检查下一个 original 是否仍在<b>当前</b>文本中，
 *       已被前一条覆盖的自然跳过（对重叠词是第二道保险）。</li>
 * </ol>
 */
@Slf4j
public final class TimeSubstitutionApplier {

    /** 单片段接受的替换条数上限（防 LLM 抽风返回几十条把文本搞乱） */
    private static final int MAX_SUBSTITUTIONS = 5;

    /** original 最大长度（超过文本本身长度的直接丢弃） */
    private static final int MAX_ORIGINAL_LEN = 20;

    /** resolved 最大长度（"2026年9月22日" 约 12 字，留余量；超过说明 LLM 吐了非日期内容） */
    private static final int MAX_RESOLVED_LEN = 20;

    /** ISO 日期 yyyy-MM-dd */
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");

    private TimeSubstitutionApplier() {
    }

    /** 与 SQL 侧 TO_CHAR(... AT TIME ZONE 'Asia/Shanghai') 同口径 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 相对时间消解的参照日期（yyyy-MM-dd，Asia/Shanghai）
     *
     * <p>取 {@code record.created_at} 而非 {@code now()}：用户可能隔天才来确认，
     * 那时"今天"早就不指今天了。未设计补记功能，故"写入日期"即"日记本体日期"，
     * 无需另设 record_date 字段。</p>
     *
     * @return yyyy-MM-dd；createdAt 为空时返回 null（AI 侧按无参照日期处理，退化为不消解）
     */
    public static String referenceDateOf(java.time.OffsetDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.atZoneSameInstant(ZONE).toLocalDate().toString();
    }

    /**
     * 对文本执行时间词替换
     *
     * @param text 待替换文本（原文片段 / segment）
     * @param subs AI 给出的替换表（可空 = 未识别到时间词）
     * @return 替换后的文本（无任何有效替换时返回原文本身）
     */
    public static String apply(String text, List<RecordProcessorProto.TimeSubstitution> subs) {
        if (text == null || text.isBlank() || subs == null || subs.isEmpty()) {
            return text;
        }
        List<RecordProcessorProto.TimeSubstitution> valid = collectValid(text, subs);
        if (valid.isEmpty()) {
            return text;
        }
        // 长词优先："大后天" 必须先于 "后天"，否则会被短词吃掉一部分
        valid.sort(Comparator.comparingInt(
                (RecordProcessorProto.TimeSubstitution s) -> s.getOriginal().length()).reversed());

        String result = text;
        for (RecordProcessorProto.TimeSubstitution s : valid) {
            String original = s.getOriginal();
            // 逐条重新检查：前一条替换可能已经把它覆盖掉了
            if (!result.contains(original)) {
                log.debug("时间词已不存在（可能被更长的时间词覆盖），跳过: {}", original);
                continue;
            }
            result = result.replace(original, s.getResolved());
        }
        if (!result.equals(text)) {
            log.info("时间词消解: {} 处替换，片段长度 {} → {}", valid.size(), text.length(), result.length());
        }
        return result;
    }

    /**
     * 收集有效替换项：脏值丢弃（口径同 TodoRef：LLM 判别输出，接收侧校验，脏值丢弃 → 不设值）
     */
    private static List<RecordProcessorProto.TimeSubstitution> collectValid(
            String text, List<RecordProcessorProto.TimeSubstitution> subs) {
        List<RecordProcessorProto.TimeSubstitution> valid = new ArrayList<>();
        Set<String> seenOriginal = new LinkedHashSet<>();
        for (RecordProcessorProto.TimeSubstitution s : subs) {
            if (s == null) {
                continue;
            }
            String original = s.getOriginal();
            String resolved = s.getResolved();
            if (isBlank(original) || isBlank(resolved)) {
                continue;
            }
            if (original.length() > MAX_ORIGINAL_LEN || original.length() > text.length()) {
                continue;
            }
            if (resolved.length() > MAX_RESOLVED_LEN) {
                continue;
            }
            // resolved 必须是"算出来了"的日期：含数字 + （中文"日" 或 ISO 格式）。
            // "下周一" 这类没数字的原样退回——替换了等于没消解，还可能把文本改乱。
            if (!looksLikeDate(resolved)) {
                log.debug("时间词消解结果非日期格式，丢弃: {} → {}", original, resolved);
                continue;
            }
            // 同一 original 只认第一条（LLM 重复给出时避免二次替换产生歧义）
            if (!seenOriginal.add(original)) {
                continue;
            }
            valid.add(s);
            if (valid.size() >= MAX_SUBSTITUTIONS) {
                log.debug("时间词替换条数达上限 {}，丢弃剩余", MAX_SUBSTITUTIONS);
                break;
            }
        }
        return valid;
    }

    /** 是否"像"一个算出来的日期：含数字，且形如 9月22日 / 2026年9月22日 / 2026-09-22 */
    private static boolean looksLikeDate(String resolved) {
        if (resolved == null || resolved.isBlank()) {
            return false;
        }
        boolean hasDigit = resolved.chars().anyMatch(Character::isDigit);
        if (!hasDigit) {
            return false;
        }
        return resolved.contains("日") || ISO_DATE.matcher(resolved).find();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
