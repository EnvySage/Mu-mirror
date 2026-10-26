package org.xianshen.mumirrorb.service;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;
public interface TarotContextService {
    record Bundle(List<Source> materials,List<String> notices) {}
    Bundle build(UUID user,String question,ReferenceOptions options,Instant now);
    boolean validate(UUID user,List<Source> sources);
    List<Source> hydrate(UUID user,List<Source> sources);
}
