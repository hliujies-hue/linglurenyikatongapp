package com.lingluren.yikatong.terminal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Private, durable session journal. UI exposes current-session outcomes only, without identities or photographs. */
public final class TerminalStore extends SQLiteOpenHelper {
    public TerminalStore(Context context) { this(context,"terminal-v2.db"); }
    TerminalStore(Context context,String database) { super(context,database,null,1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, snapshot TEXT NOT NULL, state TEXT NOT NULL, last_sequence INTEGER NOT NULL DEFAULT 0, reset_done INTEGER NOT NULL DEFAULT 0,active_slot INTEGER UNIQUE DEFAULT 1)");
        db.execSQL("CREATE TABLE events(session_id TEXT NOT NULL REFERENCES sessions(id),sequence INTEGER NOT NULL,event_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,response TEXT,confirmed_at INTEGER,created_at INTEGER NOT NULL,PRIMARY KEY(session_id,sequence))");
        db.execSQL("CREATE INDEX event_pending ON events(session_id,confirmed_at,sequence)");
        db.execSQL("CREATE TABLE faces(local_id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL UNIQUE,target_hash TEXT NOT NULL,feature BLOB NOT NULL)");
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("不支持的记录库版本，请联系平台，禁止清除应用数据"); }
    public JSONObject active() throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT snapshot,state,last_sequence,reset_done FROM sessions WHERE state<>'RELEASED'",null)) {
            if(!c.moveToFirst())return null;
            return new JSONObject(c.getString(0)).put("state",c.getString(1)).put("lastSequence",c.getLong(2)).put("resetDone",c.getInt(3)==1);
        }
    }
    public JSONObject presenceProof(String origin) throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT snapshot FROM sessions ORDER BY rowid DESC",null)) {
            while(c.moveToNext()){JSONObject value=new JSONObject(c.getString(0));if(origin.equals(value.optString("origin")))return value;}
            return null;
        }
    }
    public String libraryId(String origin) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            String owner=metadata("library_origin","");
            if(!owner.isEmpty()&&!owner.equals(origin))throw new IllegalStateException("人员库属于其他平台，请完成平台切换初始化");
            String id=metadata("library_id","");
            if(id.isEmpty()){id=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");metadata("library_id",id,true);}
            metadata("library_origin",origin,true);db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    public boolean libraryBelongsTo(String origin) throws Exception {
        String owner=metadata("library_origin","");
        if(!owner.isEmpty())return owner.equals(origin);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT snapshot FROM sessions ORDER BY rowid DESC LIMIT 1",null)){
            return !c.moveToFirst()||origin.equals(new JSONObject(c.getString(0)).optString("origin"));
        }
    }
    public void switchLibrary(String origin) throws Exception {
        if(active()!=null)throw new IllegalStateException("原账号尚未退出");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            clearFaces();db.delete("metadata","name IN ('library_origin','library_id')",null);
            libraryId(origin);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void bind(JSONObject session) throws Exception {
        TerminalProtocol.validateSession(session);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            JSONObject old=active();
            if(old!=null&&!old.getString("sessionId").equals(session.getString("sessionId")))throw new IllegalStateException("原账号未完成同步退出，不能覆盖");
            if(old==null && (session.optBoolean("resumed") || !session.getBoolean("resetRequired") || session.optLong("lastSequence",0)!=0 || !"ACTIVE".equals(session.getString("state"))))
                throw new IllegalStateException("平台仍有原会话，但本机记录缺失；请联系平台核对，禁止清除应用数据");
            ContentValues v=new ContentValues();v.put("snapshot",session.toString());v.put("state",old!=null&&!"ACTIVE".equals(old.getString("state"))?old.getString("state"):session.getString("state"));
            if(old==null){v.put("id",session.getString("sessionId"));db.insertOrThrow("sessions",null,v);}
            else {
                if(!old.optString("libraryMode").equals(session.optString("libraryMode")))v.put("reset_done",0);
                db.update("sessions",v,"id=?",new String[]{session.getString("sessionId")});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void state(String id,String state) { ContentValues v=new ContentValues();v.put("state",state);if("RELEASED".equals(state))v.putNull("active_slot");getWritableDatabase().update("sessions",v,"id=?",new String[]{id}); }
    public void resetDone(String id) { ContentValues v=new ContentValues();v.put("reset_done",1);getWritableDatabase().update("sessions",v,"id=?",new String[]{id}); }
    public JSONObject event(long userId,String failure) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            JSONObject session=active();
            if(session==null||!"ACTIVE".equals(session.getString("state")))throw new IllegalStateException("已暂停核验");
            long sequence=session.getLong("lastSequence")+1;
            if(sequence>1000000000L)throw new IllegalStateException("记录序号已达上限，请完成同步后重新登录");
            long now=System.currentTimeMillis();SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject out=TerminalProtocol.ref(session).put("sequence",sequence).put("eventId",UUID.randomUUID().toString()).put("userId",userId).put("occurredAt",fmt.format(new Date(now))).put("failure",failure);
            ContentValues v=new ContentValues();v.put("session_id",session.getString("sessionId"));v.put("sequence",sequence);v.put("event_id",out.getString("eventId"));v.put("payload",out.toString());v.put("created_at",now);db.insertOrThrow("events",null,v);
            v=new ContentValues();v.put("last_sequence",sequence);db.update("sessions",v,"id=?",new String[]{session.getString("sessionId")});
            db.setTransactionSuccessful();return out;
        }finally{db.endTransaction();}
    }
    public List<JSONObject> pending(String id) throws Exception {
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,response FROM events WHERE session_id=? AND confirmed_at IS NULL ORDER BY sequence LIMIT 20",new String[]{id})){
            while(c.moveToNext()){JSONObject e=new JSONObject(c.getString(0));if(!c.isNull(1))e.put("savedResponse",new JSONObject(c.getString(1)));out.add(e);}
        }return out;
    }
    public long pendingCount(String id){return DatabaseUtils.longForQuery(getReadableDatabase(),"SELECT COUNT(*) FROM events WHERE session_id=? AND confirmed_at IS NULL",new String[]{id});}
    public void receipt(JSONObject event,JSONObject response) throws Exception {
        TerminalProtocol.validateReceipt(event,response);
        ContentValues v=new ContentValues();v.put("response",response.toString());
        if(getWritableDatabase().update("events",v,"session_id=? AND sequence=? AND event_id=?",new String[]{event.getString("sessionId"),event.getString("sequence"),event.getString("eventId")})!=1)throw new IllegalStateException("核验回执保存失败，禁止通行");
    }
    public void confirmed(JSONObject event) throws Exception {
        ContentValues v=new ContentValues();v.put("confirmed_at",System.currentTimeMillis());
        if(getWritableDatabase().update("events",v,"session_id=? AND sequence=? AND response IS NOT NULL",new String[]{event.getString("sessionId"),event.getString("sequence")})!=1)throw new IllegalStateException("确认回执保存失败");
    }
    public void cleanup(long now) {
        long last=Long.parseLong(metadata("last_cleanup","0"));
        if(now-last<7L*86400000)return;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try {
            db.delete("events","confirmed_at IS NOT NULL AND created_at<=?",new String[]{Long.toString(now-7L*86400000)});
            metadata("last_cleanup",Long.toString(now),true);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public String metadata(String name,String fallback){try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE name=?",new String[]{name})){return c.moveToFirst()?c.getString(0):fallback;}}
    public void metadata(String name,String value,boolean write){ContentValues v=new ContentValues();v.put("name",name);v.put("value",value);getWritableDatabase().insertWithOnConflict("metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public void clearFaces(){getWritableDatabase().delete("faces",null,null);}
    public long faces(){return DatabaseUtils.queryNumEntries(getReadableDatabase(),"faces");}
    public Cursor allFaces(){return getReadableDatabase().rawQuery("SELECT local_id,feature FROM faces ORDER BY local_id",null);}
    public int faceId(long user){try(Cursor c=getReadableDatabase().rawQuery("SELECT local_id FROM faces WHERE user_id=?",new String[]{Long.toString(user)})){return c.moveToFirst()?c.getInt(0):0;}}
    public long userForFace(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT user_id FROM faces WHERE local_id=?",new String[]{Integer.toString(id)})){return c.moveToFirst()?c.getLong(0):0;}}
    public int saveFace(long user,String hash,byte[] feature){
        ContentValues v=new ContentValues();v.put("user_id",user);v.put("target_hash",hash);v.put("feature",feature);
        int id=faceId(user);if(id==0){long next=getWritableDatabase().insertOrThrow("faces",null,v);if(next>Integer.MAX_VALUE)throw new IllegalStateException("本地人脸编号达到上限");return (int)next;}
        getWritableDatabase().update("faces",v,"local_id=?",new String[]{Integer.toString(id)});return id;
    }
    public JSONObject dashboard(String sessionID) throws Exception {
        java.util.Calendar day=java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        day.set(java.util.Calendar.HOUR_OF_DAY,0);day.set(java.util.Calendar.MINUTE,0);day.set(java.util.Calendar.SECOND,0);day.set(java.util.Calendar.MILLISECOND,0);
        JSONObject data=new JSONObject().put("localFaces",faces()).put("pending",pendingCount(sessionID));
        // Stored responses are JSONObject.toString(): compact JSON, never arbitrary user text.
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN instr(response,'\"result\":\"PASS\"')>0 THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN response IS NULL THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN instr(response,'\"reason\":\"NOT_EFFECTIVE\"')>0 OR instr(response,'\"reason\":\"NOT_YET_EFFECTIVE\"')>0 THEN 1 ELSE 0 END),0) FROM events WHERE session_id=? AND created_at>=?",new String[]{sessionID,Long.toString(day.getTimeInMillis())})){
            c.moveToFirst();data.put("today",c.getLong(0)).put("passed",c.getLong(1)).put("awaiting",c.getLong(2)).put("notEffective",c.getLong(3)).put("denied",c.getLong(0)-c.getLong(1)-c.getLong(2)-c.getLong(3));
        }
        java.util.HashSet<Long> pendingPeople=new java.util.HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,response FROM events WHERE session_id=? AND created_at>=? AND response IS NOT NULL",new String[]{sessionID,Long.toString(day.getTimeInMillis())})){
            while(c.moveToNext()) { JSONObject reply=new JSONObject(c.getString(1));String reason=reply.optString("reason");long user=new JSONObject(c.getString(0)).optLong("userId");
                if(user>0 && ("NOT_EFFECTIVE".equals(reason)||"NOT_YET_EFFECTIVE".equals(reason)))pendingPeople.add(user);
            }
        }
        data.put("notEffectivePeople",pendingPeople.size());
        java.util.HashSet<String> shownPending=new java.util.HashSet<>();
        org.json.JSONArray items=new org.json.JSONArray();
        SimpleDateFormat format=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA);format.setTimeZone(day.getTimeZone());
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,response,confirmed_at,created_at FROM events WHERE session_id=? ORDER BY sequence DESC",new String[]{sessionID})){
            while(items.length()<20 && c.moveToNext()){
                JSONObject row=new JSONObject().put("time",format.format(new Date(c.getLong(3)))).put("confirmed",!c.isNull(2));
                if(c.isNull(1))row.put("text","等待平台处理 / 补传");
                else {JSONObject reply=new JSONObject(c.getString(1)),event=new JSONObject(c.getString(0));
                    String reason=reply.optString("reason");long user=event.optLong("userId");
                    String dayKey=Long.toString((c.getLong(3)+8L*60*60*1000)/(24L*60*60*1000));
                    if(user>0 && !c.isNull(2) && ("NOT_EFFECTIVE".equals(reason)||"NOT_YET_EFFECTIVE".equals(reason)) && !shownPending.add(dayKey+":"+user))continue;
                    row.put("pass","PASS".equals(reply.optString("result"))).put("title",TerminalResultType.title(TerminalResultType.of(reply.optString("result"),reply.optString("reason"),false)));
                    row.put("text","PASS".equals(reply.optString("result"))?"平台记录：核验通过":TerminalDecisionText.describe(event.optLong("userId")>0,reply.optString("reason"),reply.optString("displayText","未放行")));
                }
                items.put(row);
            }
        }
        return data.put("items",items);
    }
    public byte[] faceFeature(long user,String hash){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT feature FROM faces WHERE user_id=? AND target_hash=?",new String[]{Long.toString(user),hash})){
            return c.moveToFirst()?c.getBlob(0):null;
        }
    }
    public void deleteFace(long user){getWritableDatabase().delete("faces","user_id=?",new String[]{Long.toString(user)});}
}
