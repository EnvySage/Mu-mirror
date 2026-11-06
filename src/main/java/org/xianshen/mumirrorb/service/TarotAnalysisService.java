package org.xianshen.mumirrorb.service;
import java.util.UUID;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;
public interface TarotAnalysisService {
    java.util.List<AnalysisVersion> versions(UUID user,UUID reading);
    AnalysisView submit(UUID user,UUID reading,AnalyzeInput input);
    AnalysisView get(UUID user,UUID analysis);
}
