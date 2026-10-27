package org.xianshen.mumirrorb.tarot;

/** Deliberately conservative: uncertainty means no unsolicited tarot. */
public final class TarotMentionPolicy {
    public enum Mode { NONE, EXPLICIT, CASUAL, SUPPRESS, RESUME }
    public static Mode evaluate(String question, boolean suppressed) {
        String q = (question == null ? "" : question).replaceAll("(?s)```.*?```", "")
                .replaceAll("(?m)^\\s*>.*$", "").strip();
        if (q.matches(".*(别|不要|停止|不想|不可以|不能).{0,6}(塔罗|日牌|运势).*")) return Mode.SUPPRESS;
        if (q.matches(".*(恢复|可以继续|重新开启|可以再提).{0,6}(塔罗|日牌|运势).*")
                || (suppressed && q.matches("(现在)?可以再提(了|啦)?[。！!]?"))) return Mode.RESUME;
        if (q.matches("(?is).*(代码|函数|编程|SQL|待办|标记|删除|查找|查询|搜索|查.{0,8}记录|帮我写|翻译|总结|制定|生成|提醒|安排).*") ) return Mode.NONE;
        if (q.matches(".*(塔罗|日牌|今日运势|今天.{0,4}(抽到|牌|运势)).*")) return Mode.EXPLICIT;
        if (!suppressed && q.length() <= 100 && q.matches(".*(想尝试|有点犹豫|期待|怕不会|最近感觉|最近觉得).*")) return Mode.CASUAL;
        return Mode.NONE;
    }
}
