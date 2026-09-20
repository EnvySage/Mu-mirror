package org.xianshen.mumirrorb.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 时间词替换执行器单测
 *
 * <p>覆盖：零回归（无替换表原样返回）、多时间词、长词优先（"大后天" vs "后天"）、
 * 脏值丢弃（original 不存在 / resolved 非日期 / 重复 original / 超上限）、参照日期时区换算。</p>
 */
class TimeSubstitutionApplierTest {

    private static RecordProcessorProto.TimeSubstitution sub(String original, String resolved) {
        return RecordProcessorProto.TimeSubstitution.newBuilder()
                .setOriginal(original)
                .setResolved(resolved)
                .build();
    }

    @Test
    @DisplayName("无替换表 → 原样返回（AI 未识别时间词，零回归到改动前行为）")
    void noSubs_returnsOriginal() {
        String text = "今天开了组会";
        assertSame(text, TimeSubstitutionApplier.apply(text, null));
        assertSame(text, TimeSubstitutionApplier.apply(text, List.of()));
    }

    @Test
    @DisplayName("空文本 / null → 原样返回")
    void blankText_returnsOriginal() {
        assertNull(TimeSubstitutionApplier.apply(null, List.of(sub("今天", "9月19日"))));
        assertEquals("", TimeSubstitutionApplier.apply("", List.of(sub("今天", "9月19日"))));
    }

    @Test
    @DisplayName("单个时间词替换")
    void singleSubstitution() {
        assertEquals("9月19日开了组会",
                TimeSubstitutionApplier.apply("今天开了组会", List.of(sub("今天", "9月19日"))));
    }

    @Test
    @DisplayName("一段话多个时间词 → 全部替换")
    void multipleSubstitutions() {
        String text = "今天开了组会，明天要交报告，上周三说要改方案";
        List<RecordProcessorProto.TimeSubstitution> subs = List.of(
                sub("今天", "9月19日"),
                sub("明天", "9月20日"),
                sub("上周三", "9月9日"));
        assertEquals("9月19日开了组会，9月20日要交报告，9月9日说要改方案",
                TimeSubstitutionApplier.apply(text, subs));
    }

    @Test
    @DisplayName("同一时间词出现多次 → 全部替换")
    void repeatedSameWord_replacesAll() {
        assertEquals("9月19日上午开会，9月19日下午加班",
                TimeSubstitutionApplier.apply("今天上午开会，今天下午加班",
                        List.of(sub("今天", "9月19日"))));
    }

    @Test
    @DisplayName("长词优先：'大后天' 不被 '后天' 吃掉（顺序打乱也不出错）")
    void longerFirst_preventsPartialMatch() {
        String text = "大后天交报告";
        // LLM 若同时给出"后天"（错的那条）且排在前面，长词优先保证"大后天"先被替换
        List<RecordProcessorProto.TimeSubstitution> subs = List.of(
                sub("后天", "9月21日"),
                sub("大后天", "9月22日"));
        assertEquals("9月22日交报告", TimeSubstitutionApplier.apply(text, subs));
    }

    @Test
    @DisplayName("逐条存在性检查：已被更长词覆盖的时间词自动跳过")
    void overlappedWord_skipped() {
        // "后天，大后天"：先替换"大后天"，再检查"后天"仍存在（前者那个），两者各得其所
        String text = "后天开会，大后天交报告";
        List<RecordProcessorProto.TimeSubstitution> subs = List.of(
                sub("后天", "9月21日"),
                sub("大后天", "9月22日"));
        assertEquals("9月21日开会，9月22日交报告", TimeSubstitutionApplier.apply(text, subs));
    }

    @Test
    @DisplayName("脏值：original 不在文本中 → 跳过，其余照常替换")
    void originalNotFound_skipped() {
        String text = "今天开了组会";
        List<RecordProcessorProto.TimeSubstitution> subs = List.of(
                sub("明天", "9月20日"),   // 文本里没有"明天"
                sub("今天", "9月19日"));
        assertEquals("9月19日开了组会", TimeSubstitutionApplier.apply(text, subs));
    }

    @Test
    @DisplayName("脏值：resolved 不是算出来的日期（'下周一'）→ 丢弃不替换")
    void nonDateResolved_dropped() {
        // 替换成"下周一"等于没消解，还可能把文本改乱 → 必须丢弃
        assertEquals("今天要交报告",
                TimeSubstitutionApplier.apply("今天要交报告", List.of(sub("今天", "下周一"))));
    }

    @Test
    @DisplayName("ISO 格式 resolved（2026-09-19）同样接受")
    void isoDateResolved_accepted() {
        assertEquals("2026-09-19 开了组会",
                TimeSubstitutionApplier.apply("今天 开了组会", List.of(sub("今天", "2026-09-19"))));
    }

    @Test
    @DisplayName("脏值：resolved 为空 / original 为空 → 丢弃")
    void blankFields_dropped() {
        assertEquals("今天开会",
                TimeSubstitutionApplier.apply("今天开会", List.of(sub("今天", ""))));
        assertEquals("今天开会",
                TimeSubstitutionApplier.apply("今天开会", List.of(sub("", "9月19日"))));
    }

    @Test
    @DisplayName("同一 original 重复给出 → 只认第一条")
    void duplicateOriginal_firstWins() {
        assertEquals("9月19日开会",
                TimeSubstitutionApplier.apply("今天开会", List.of(
                        sub("今天", "9月19日"),
                        sub("今天", "9月20日"))));
    }

    @Test
    @DisplayName("替换条数上限 5，超出丢弃（防 LLM 抽风把文本搞乱）")
    void maxSubstitutions_capped() {
        String text = "一 二 三 四 五 六 七";
        List<RecordProcessorProto.TimeSubstitution> subs = new ArrayList<>();
        String[] words = {"一", "二", "三", "四", "五", "六", "七"};
        for (String w : words) {
            subs.add(sub(w, "9月" + (19 + subs.size()) + "日"));
        }
        String result = TimeSubstitutionApplier.apply(text, subs);
        // 前 5 条生效，第 6/7 条被丢弃
        assertEquals("9月19日 9月20日 9月21日 9月22日 9月23日 六 七", result);
    }

    @Test
    @DisplayName("参照日期按 Asia/Shanghai 取日期部分")
    void referenceDateOf_shanghaiZone() {
        // +08:00 晚间 → 当天
        assertEquals("2026-09-19", TimeSubstitutionApplier.referenceDateOf(
                OffsetDateTime.of(2026, 9, 19, 23, 30, 0, 0, ZoneOffset.ofHours(8))));
        // UTC 晚间 → 上海已是次日（口径须与 SQL AT TIME ZONE 'Asia/Shanghai' 一致）
        assertEquals("2026-09-20", TimeSubstitutionApplier.referenceDateOf(
                OffsetDateTime.of(2026, 9, 19, 23, 30, 0, 0, ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("参照日期为空 → null（AI 侧按无参照日期处理，退化为不消解）")
    void referenceDateOf_null() {
        assertNull(TimeSubstitutionApplier.referenceDateOf(null));
    }
}
