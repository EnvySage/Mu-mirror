package org.xianshen.mumirrorb.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.TarotMapper;
import org.xianshen.mumirrorb.service.TarotService;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import java.time.*;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

@Service
public class TarotServiceImpl implements TarotService {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final TarotMapper repo; private final TarotCatalog catalog;
    private final TransactionTemplate tx; private final Clock clock; private final RandomGenerator random;
    @Autowired public TarotServiceImpl(TarotMapper repo,TarotCatalog catalog,PlatformTransactionManager manager) {
        this(repo,catalog,new TransactionTemplate(manager),Clock.systemUTC(),new SecureRandom());
    }
    public TarotServiceImpl(TarotMapper repo,TarotCatalog catalog,TransactionTemplate tx,Clock clock,RandomGenerator random) {
        this.repo=repo;this.catalog=catalog;this.tx=tx;this.clock=clock;this.random=random;
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZONE));}
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZONE);}
    private DailyView view(DailyDraw draw,boolean added,boolean collected) {
        var day=today();return new DailyView(day,ZONE.getId(),day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime(),draw,added,collected);
    }
    private String pick(){var cards=catalog.activeCards();return cards.get(random.nextInt(cards.size())).id();}
    public DailyView daily(UUID user){return view(repo.daily(user,today()),false,false);}
    public DailyView drawDaily(UUID user,UUID request) {
        requireRequest(request);
        return tx.execute(status->{
            repo.lock(user,"daily:"+request);
            var prior=repo.request(user,request);
            if(prior!=null)return view(prior,false,false);
            var day=today(); var draw=repo.daily(user,day); boolean added=false,collected=false;
            if(draw==null){
                var candidate=new DailyDraw(UUID.randomUUID(),day,pick(),catalog.version(),now());
                added=repo.insertDraw(user,request,candidate);
                draw=added?candidate:repo.daily(user,day);
                if(added)collected=repo.collect(user,draw);
            }
            repo.alias(user,request,draw.id());
            return view(draw,added,collected);
        });
    }
    public DailyDraw findDailyRequest(UUID user,UUID request){var draw=repo.request(user,request);if(draw==null)throw missing();return draw;}
    public ReadingView createReading(UUID user,UUID request) {
        requireRequest(request);
        return tx.execute(status->{repo.lock(user,"reading:"+request);var prior=repo.readingRequest(user,request);if(prior!=null)return prior;
            var reading=new ReadingView(UUID.randomUUID(),pick(),catalog.version(),now(),null);repo.insertReading(user,request,reading);return reading;});
    }
    public ReadingView getReading(UUID user,UUID reading){var result=repo.reading(user,reading);if(result==null)throw missing();return result;}
    public ReadingView latestReading(UUID user){return repo.latest(user);}
    public CollectionView collection(UUID user){var entries=repo.collection(user);return new CollectionView(catalog.activeCards().size(),entries.size(),entries);}
    public Preferences preferences(UUID user){return repo.preferences(user);}
    public Preferences updatePreferences(UUID user,PreferencesInput input){
        if(input==null||!input.options().valid()||input.expectedVersion()<0)throw new BusinessException(400,"请选择 7、30 或 90 天的日记范围");
        return tx.execute(status->{repo.preferences(user);if(!repo.updatePreferences(user,input))throw new BusinessException(409,"设置已在其他页面更新，请刷新后重试");return repo.preferences(user);});
    }
    private static void requireRequest(UUID request){if(request==null)throw new BusinessException(400,"缺少请求标识，请重试");}
    private static BusinessException missing(){return new BusinessException(404,"没有找到这次抽牌");}
}
