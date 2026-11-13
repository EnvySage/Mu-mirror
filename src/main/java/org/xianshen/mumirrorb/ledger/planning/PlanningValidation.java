package org.xianshen.mumirrorb.ledger.planning;

import org.xianshen.mumirrorb.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;

final class PlanningValidation {
    private static final BigDecimal MAX_MONEY = new BigDecimal("99999999999.99");

    private PlanningValidation() {}

    static BigDecimal money(BigDecimal amount, boolean allowZero) {
        if (amount == null || amount.signum() < 0 || (!allowZero && amount.signum() == 0)
                || amount.compareTo(MAX_MONEY) > 0) {
            throw new BusinessException(400, allowZero ? "预算金额无效" : "请输入有效的正数金额");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BusinessException(400, "金额最多保留两位小数");
        }
    }

    static String title(String value) {
        String title = value == null ? "" : value.strip();
        if (title.isBlank() || title.length() > 120) {
            throw new BusinessException(400, "标题需为 1–120 个字符");
        }
        return title;
    }

    static String note(String value) {
        String note = value == null ? "" : value.strip();
        if (note.length() > 500) throw new BusinessException(400, "备注最多 500 个字符");
        return note;
    }

    static String link(String value) {
        if (value == null || value.isBlank()) return null;
        String link = value.strip();
        try {
            URI url = URI.create(link);
            String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
            if (link.length() <= 2048 && (scheme.equals("https") || scheme.equals("http")) && url.getHost() != null) {
                return link;
            }
        } catch (IllegalArgumentException ignored) {
            // Return the same field error for all invalid links.
        }
        throw new BusinessException(400, "请输入有效的 HTTP 或 HTTPS 链接");
    }

    static LocalDate start(LocalDate startOn, LocalDate today, int yearsBack) {
        if (startOn == null || startOn.isBefore(today.minusYears(yearsBack)) || startOn.isAfter(today.plusYears(10))) {
            throw new BusinessException(400, "开始日期超出可设置的范围");
        }
        return startOn;
    }
}
