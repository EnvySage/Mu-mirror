package org.xianshen.mumirrorb.ledger.planning;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.xianshen.mumirrorb.ledger.planning.PlanningModels.Interval.MONTH;

class LedgerPlanningDatesTest {
    @Test
    void monthlyDatesUseOriginalAnchorEvenAfterShortMonth() {
        var anchor = LocalDate.of(2026, 1, 31);
        assertEquals(LocalDate.of(2026, 2, 28), PlanningDates.dueDate(anchor, MONTH, 1, 1));
        assertEquals(LocalDate.of(2026, 3, 31), PlanningDates.dueDate(anchor, MONTH, 1, 2));
    }

    @Test
    void fixedWeeksAdvanceWithoutOverlappingPreviousWeek() {
        var anchor = LocalDate.of(2026, 11, 8);
        assertEquals(new PlanningDates.Window(anchor, anchor.plusDays(6)), PlanningDates.fixedWindow(anchor, 7, anchor.plusDays(6)));
        assertEquals(new PlanningDates.Window(anchor.plusDays(7), anchor.plusDays(13)),
                PlanningDates.fixedWindow(anchor, 7, anchor.plusDays(7)));
    }
}
