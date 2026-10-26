package org.xianshen.mumirrorb.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import org.xianshen.mumirrorb.tarot.TarotMentionPolicy;
import java.util.UUID;

@Service
public class TarotChatContextService {
    private final JdbcTemplate db; private final TarotService tarot; private final TarotCatalog catalog;
    private final TransactionTemplate tx;
    public TarotChatContextService(JdbcTemplate db,TarotService tarot,TarotCatalog catalog,PlatformTransactionManager manager) {
        this.db=db;this.tarot=tarot;this.catalog=catalog;this.tx=new TransactionTemplate(manager);
    }
    public CommonProto.AssistantContext attach(UUID user,UUID session,UUID run,String question,CommonProto.AssistantContext base,boolean shadow) {
        var clean=base.toBuilder().clearDivination().build();
        if(shadow)return clean;
        return tx.execute(status->{
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM chat_sessions WHERE id=? AND user_id=?)",Boolean.class,session,user)))return clean;
            db.update("INSERT INTO tarot_chat_state(user_id,session_id) VALUES(?,?) ON CONFLICT DO NOTHING",user,session);
            var state=db.queryForMap("SELECT * FROM tarot_chat_state WHERE user_id=? AND session_id=? FOR UPDATE",user,session);
            var mode=TarotMentionPolicy.evaluate(question,Boolean.TRUE.equals(state.get("suppressed")));
            if(mode==TarotMentionPolicy.Mode.SUPPRESS||mode==TarotMentionPolicy.Mode.RESUME){
                db.update("UPDATE tarot_chat_state SET suppressed=? WHERE user_id=? AND session_id=?",mode==TarotMentionPolicy.Mode.SUPPRESS,user,session);return clean;
            }
            if(mode==TarotMentionPolicy.Mode.NONE)return clean;
            var daily=tarot.daily(user); var draw=daily.draw();
            if(draw==null)return clean;
            if(mode==TarotMentionPolicy.Mode.CASUAL){
                var offered=state.get("last_offered_date");
                if(offered!=null && daily.serverDate().toString().equals(offered.toString()) && !run.equals(state.get("last_offered_run_id")))return clean;
                db.update("UPDATE tarot_chat_state SET last_offered_date=?,last_offered_run_id=? WHERE user_id=? AND session_id=?",daily.serverDate(),run,user,session);
            }
            var card=catalog.byId(draw.cardId());
            var context=CommonProto.DivinationContext.newBuilder().setDrawId(draw.id().toString())
                    .setDrawnAt(draw.createdAt().toString()).setDrawDate(draw.drawDate().toString())
                    .setEntertainmentOnly(true).setMentionMode(mode==TarotMentionPolicy.Mode.EXPLICIT?"explicit":"casual")
                    .addCards(CommonProto.DivinationCard.newBuilder().setCardName(card.name()).setOrientation("upright").setInterpretation(card.meaning()));
            return clean.toBuilder().setDivination(context).build();
        });
    }
}
