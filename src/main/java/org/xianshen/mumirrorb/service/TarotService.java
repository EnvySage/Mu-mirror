package org.xianshen.mumirrorb.service;
import java.util.UUID;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;
public interface TarotService {
    DailyView daily(UUID user);
    DailyView drawDaily(UUID user,UUID request);
    DailyDraw findDailyRequest(UUID user,UUID request);
    ReadingView createReading(UUID user,UUID request);
    ReadingView getReading(UUID user,UUID reading);
    ReadingView latestReading(UUID user);
    CollectionView collection(UUID user);
    Preferences preferences(UUID user);
    Preferences updatePreferences(UUID user,PreferencesInput input);
}
