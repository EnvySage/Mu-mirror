package org.xianshen.mumirrorb.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

/** Explicit SQL keeps request aliases, day uniqueness and collection updates in one transaction. */
@Repository
public class TarotMapper {
    private final JdbcTemplate db;
    public TarotMapper(JdbcTemplate db, ObjectMapper json) { this.db=db; }
    private static final RowMapper<DailyDraw> DRAW=(r,n)->new DailyDraw(r.getObject("id",UUID.class),r.getObject("draw_date",LocalDate.class),r.getString("card_id"),r.getString("catalog_version"),r.getObject("created_at",OffsetDateTime.class));
    private static final RowMapper<ReadingView> READING=(r,n)->new ReadingView(r.getObject("id",UUID.class),r.getString("card_id"),r.getString("catalog_version"),r.getObject("created_at",OffsetDateTime.class),r.getObject("latest_analysis_id",UUID.class));
    public void lock(UUID user,String key) { db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(r,n)->0,user+":"+key); }
    public DailyDraw daily(UUID user,LocalDate day) { return first(db.query("SELECT * FROM tarot_daily_draw WHERE user_id=? AND draw_date=?",DRAW,user,day)); }
    public DailyDraw request(UUID user,UUID request) {
        return first(db.query("SELECT d.* FROM tarot_daily_draw d JOIN tarot_daily_request q ON q.user_id=d.user_id AND q.draw_id=d.id WHERE q.user_id=? AND q.client_request_id=?",DRAW,user,request));
    }
    public boolean insertDraw(UUID user,UUID request,DailyDraw draw) {
        return db.update("INSERT INTO tarot_daily_draw(id,user_id,draw_date,card_id,catalog_version,client_request_id,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(user_id,draw_date) DO NOTHING",draw.id(),user,draw.drawDate(),draw.cardId(),draw.catalogVersion(),request,draw.createdAt())==1;
    }
    public void alias(UUID user,UUID request,UUID draw) { db.update("INSERT INTO tarot_daily_request(user_id,client_request_id,draw_id) VALUES(?,?,?)",user,request,draw); }
    public boolean collect(UUID user,DailyDraw draw) {
        int added=db.update("INSERT INTO tarot_collection(user_id,card_id,first_obtained_at,daily_draw_count) VALUES(?,?,?,1) ON CONFLICT DO NOTHING",user,draw.cardId(),draw.createdAt());
        if(added==0) db.update("UPDATE tarot_collection SET daily_draw_count=daily_draw_count+1 WHERE user_id=? AND card_id=?",user,draw.cardId());
        return added==1;
    }
    public List<CollectionEntry> collection(UUID user) {
        return db.query("SELECT * FROM tarot_collection WHERE user_id=? ORDER BY first_obtained_at,card_id",(r,n)->new CollectionEntry(r.getString("card_id"),r.getObject("first_obtained_at",OffsetDateTime.class),r.getLong("daily_draw_count")),user);
    }
    public ReadingView reading(UUID user,UUID id) { return first(db.query("SELECT * FROM tarot_reading WHERE user_id=? AND id=?",READING,user,id)); }
    public ReadingView readingRequest(UUID user,UUID request) { return first(db.query("SELECT * FROM tarot_reading WHERE user_id=? AND client_request_id=?",READING,user,request)); }
    public ReadingView latest(UUID user) { return first(db.query("SELECT * FROM tarot_reading WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",READING,user)); }
    public void insertReading(UUID user,UUID request,ReadingView reading) {
        db.update("INSERT INTO tarot_reading(id,user_id,card_id,catalog_version,client_request_id,created_at) VALUES(?,?,?,?,?,?)",reading.readingId(),user,reading.cardId(),reading.catalogVersion(),request,reading.createdAt());
    }
    public Preferences preferences(UUID user) {
        db.update("INSERT INTO tarot_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",user);
        return db.queryForObject("SELECT * FROM tarot_preferences WHERE user_id=?",(r,n)->new Preferences(r.getBoolean("diary_enabled"),r.getInt("diary_days"),r.getBoolean("todos_enabled"),r.getBoolean("mirror_enabled"),r.getLong("version")),user);
    }
    public boolean updatePreferences(UUID user,PreferencesInput input) {
        return db.update("UPDATE tarot_preferences SET diary_enabled=?,diary_days=?,todos_enabled=?,mirror_enabled=?,version=version+1,updated_at=now() WHERE user_id=? AND version=?",input.diaryEnabled(),input.diaryDays(),input.todosEnabled(),input.mirrorEnabled(),user,input.expectedVersion())==1;
    }
    private static <T> T first(List<T> items){return items.isEmpty()?null:items.get(0);}
}
