package org.xianshen.mumirrorb.tarot;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Wire models shared by tarot endpoints. Persisted draws are always server-owned. */
public final class TarotTypes {
    private TarotTypes() {}
    public record CardDefinition(String id, int ordinal, String number, String name, String en,
            String keyword, String headline, String meaning, String action,
            String imageUrl, String backUrl, boolean active) {}
    public record CatalogView(String catalogVersion, int activeCount, List<CardDefinition> cards) {}
    public record DrawInput(UUID clientRequestId) {}
    public record DailyDraw(UUID id, LocalDate drawDate, String cardId, String catalogVersion, OffsetDateTime createdAt) {}
    public record DailyView(LocalDate serverDate, String timezone, OffsetDateTime nextResetAt,
            DailyDraw draw, boolean isNewDraw, boolean newlyCollected) {}
    public record ReadingView(UUID readingId, String cardId, String catalogVersion,
            OffsetDateTime createdAt, UUID latestAnalysisId) {}
    public record CollectionEntry(String cardId, OffsetDateTime firstObtainedAt, long dailyDrawCount) {}
    public record CollectionView(int activeCount, int unlockedCount, List<CollectionEntry> entries) {}
    public record ReferenceOptions(boolean diaryEnabled, int diaryDays, boolean todosEnabled, boolean mirrorEnabled) {
        public static ReferenceOptions defaults() { return new ReferenceOptions(true, 7, false, false); }
        public boolean valid() { return diaryDays == 7 || diaryDays == 30 || diaryDays == 90; }
    }
    public record Preferences(boolean diaryEnabled, int diaryDays, boolean todosEnabled, boolean mirrorEnabled, long version) {}
    public record PreferencesInput(boolean diaryEnabled, int diaryDays, boolean todosEnabled, boolean mirrorEnabled, long expectedVersion) {
        public ReferenceOptions options() { return new ReferenceOptions(diaryEnabled, diaryDays, todosEnabled, mirrorEnabled); }
    }
    public record AnalyzeInput(UUID clientRequestId, String question, ReferenceOptions options) {}
    public record Source(String ref, String kind, String sourceId, String title, String date, String excerpt, String version) {}
    public record AnalysisView(UUID analysisId, UUID readingId, String status, String text, List<Source> sources,
            List<String> notices, ReferenceOptions options, String question, String errorCode, long pollAfterMs) {}
}
