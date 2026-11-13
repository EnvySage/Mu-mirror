package org.xianshen.mumirrorb.ledger.planning;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.xianshen.mumirrorb.ledger.planning.PlanningModels.Interval;

final class PlanningDates {
    static final String BUSINESS_ZONE = "Asia/Shanghai";
    private static final ZoneId ZONE = ZoneId.of(BUSINESS_ZONE);

    private PlanningDates() {}

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    static LocalDate dueDate(LocalDate anchor, Interval interval, int intervalCount, long occurrence) {
        long step = Math.multiplyExact(intervalCount, occurrence);
        return switch (interval) {
            case DAY -> anchor.plusDays(step);
            case WEEK -> anchor.plusWeeks(step);
            case MONTH -> anchor.plusMonths(step);
        };
    }

    static Window fixedWindow(LocalDate anchor, int days, LocalDate date) {
        long offset = Math.max(0, ChronoUnit.DAYS.between(anchor, date)) / days;
        LocalDate start = anchor.plusDays(offset * days);
        return new Window(start, start.plusDays(days - 1L));
    }

    static Window rollingWindow(LocalDate anchor, int days, LocalDate end) {
        LocalDate start = end.minusDays(days - 1L);
        return new Window(start.isBefore(anchor) ? anchor : start, end);
    }

    record Window(LocalDate start, LocalDate end) {}
}
