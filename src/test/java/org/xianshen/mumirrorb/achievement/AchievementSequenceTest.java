package org.xianshen.mumirrorb.achievement;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AchievementSequenceTest {
    @Test void editingMustFallInsideTheSameSevenDayRecordingWindow() {
        var days=List.of("2026-01-01","2026-01-02","2026-01-03","2026-01-04","2026-01-05","2026-01-06","2026-01-07")
                .stream().map(LocalDate::parse).toList();
        assertEquals(7,AchievementSequences.editedStreak(days,Set.of(LocalDate.parse("2026-01-02"),LocalDate.parse("2026-01-04"),LocalDate.parse("2026-01-07"))));
        assertEquals(0,AchievementSequences.editedStreak(days,Set.of(LocalDate.parse("2026-02-02"),LocalDate.parse("2026-02-04"),LocalDate.parse("2026-02-07"))));
    }

    @Test void modelSwitchRequiresSuccessfulAbaRatherThanTwoUnrelatedModels() {
        assertEquals(0,AchievementSequences.roundTrips(List.of("a","a","b","b")));
        assertEquals(1,AchievementSequences.roundTrips(List.of("a","a","b","b","a")));
        assertEquals(0,AchievementSequences.roundTrips(List.of("a","b","c")));
    }

    @Test void monthStreakMustKeepSixObservedMonthsInsideItsTwelveMonthWindow() {
        var months=java.util.stream.LongStream.range(0,12).mapToObj(i->java.time.YearMonth.of(2025,7).plusMonths(i)).toList();
        assertEquals(12,AchievementSequences.filteredMonthStreak(months,Set.copyOf(months.subList(0,6))));
        assertEquals(0,AchievementSequences.filteredMonthStreak(months,Set.of(java.time.YearMonth.of(2020,1))));
    }
}
