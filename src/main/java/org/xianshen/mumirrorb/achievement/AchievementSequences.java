package org.xianshen.mumirrorb.achievement;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

final class AchievementSequences {
    private AchievementSequences() {}

    static long editedStreak(Collection<LocalDate> recorded, Set<LocalDate> edited) {
        Set<LocalDate> days=new HashSet<>(recorded);
        for (LocalDate end : days) {
            int edits=0;
            boolean complete=true;
            for (int i=0;i<7;i++) {
                LocalDate day=end.minusDays(i);
                if (!days.contains(day)) { complete=false; break; }
                if (edited.contains(day)) edits++;
            }
            if (complete && edits>=3) return 7;
        }
        return 0;
    }

    static long filteredMonthStreak(Collection<YearMonth> recorded, Set<YearMonth> filtered) {
        Set<YearMonth> months=new HashSet<>(recorded);
        for (YearMonth end : months) {
            int observations=0;
            boolean complete=true;
            for (int i=0;i<12;i++) {
                YearMonth month=end.minusMonths(i);
                if (!months.contains(month)) { complete=false; break; }
                if (filtered.contains(month)) observations++;
            }
            if (complete && observations>=6) return 12;
        }
        return 0;
    }

    static long roundTrips(List<String> successfulModels) {
        List<String> changes=new ArrayList<>();
        for (String model : successfulModels) {
            if (model!=null && !model.isBlank() && (changes.isEmpty() || !changes.get(changes.size()-1).equals(model))) changes.add(model);
        }
        for (int i=2;i<changes.size();i++) if (changes.get(i).equals(changes.get(i-2))) return 1;
        return 0;
    }
}
