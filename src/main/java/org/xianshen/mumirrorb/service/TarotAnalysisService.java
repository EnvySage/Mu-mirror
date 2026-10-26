package org.xianshen.mumirrorb.service;
import java.util.UUID;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;
public interface TarotAnalysisService {
    AnalysisView submit(UUID user,UUID reading,AnalyzeInput input);
    AnalysisView get(UUID user,UUID analysis);
}
