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
    interface MigrationListener {void progress(String table,long migrated);}
    private MigrationListener migration;
    void migrationListener(MigrationListener listener){migration=listener;}
    private final TerminalStorageCrypto crypto;
    public TerminalStore(Context context) { this(context,"terminal-v2.db"); }
    TerminalStore(Context context,String database) { super(context,database,null,2);crypto=new TerminalStorageCrypto(context.getApplicationContext(),database); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true);try(Cursor secure=db.rawQuery("PRAGMA secure_delete=ON",null)){secure.moveToFirst();} }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, snapshot TEXT NOT NULL, state TEXT NOT NULL, last_sequence INTEGER NOT NULL DEFAULT 0, reset_done INTEGER NOT NULL DEFAULT 0,active_slot INTEGER UNIQUE DEFAULT 1)");
        db.execSQL("CREATE TABLE events(session_id TEXT NOT NULL REFERENCES sessions(id),sequence INTEGER NOT NULL,event_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,response TEXT,response_result TEXT,response_reason TEXT,confirmed_at INTEGER,created_at INTEGER NOT NULL,PRIMARY KEY(session_id,sequence))");
        db.execSQL("CREATE INDEX event_pending ON events(session_id,confirmed_at,sequence)");
        db.execSQL("CREATE TABLE faces(local_id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL UNIQUE,target_hash TEXT NOT NULL,feature BLOB NOT NULL)");
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { if(oldVersion==1&&newVersion==2){db.execSQL("ALTER TABLE events ADD COLUMN response_result TEXT");db.execSQL("ALTER TABLE events ADD COLUMN response_reason TEXT");return;}throw new IllegalStateException("不支持的记录库版本，请联系平台，禁止清除应用数据"); }
    @Override public void onOpen(SQLiteDatabase db){
        super.onOpen(db);
        db.execSQL("CREATE TABLE IF NOT EXISTS crypto_state(name TEXT PRIMARY KEY,value INTEGER NOT NULL)");
        boolean existing=DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM crypto_state WHERE name='key'",null)>0;
        crypto.initialize(existing);
        db.execSQL("INSERT OR IGNORE INTO crypto_state(name,value) VALUES('key',1)");
        if(DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM crypto_state WHERE name='complete'",null)==0){
            migrateTable(db,"sessions",new String[]{"snapshot"});
            migrateTable(db,"events",new String[]{"payload","response"});
            migrateTable(db,"faces",new String[]{"feature"});
            migrateTable(db,"metadata",new String[]{"value"});
            db.execSQL("INSERT INTO crypto_state(name,value) VALUES('complete',1)");
        }
        if(DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM crypto_state WHERE name='compacted'",null)==0){
            // Drop free pages containing pre-upgrade plaintext. No user data is deleted.
            try(Cursor checkpoint=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){if(checkpoint.moveToFirst()&&checkpoint.getInt(0)!=0)throw new IllegalStateException("加密迁移日志仍被占用，请关闭应用后重试；原记录已保留");}
            db.execSQL("VACUUM");
            try(Cursor checkpoint=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){if(checkpoint.moveToFirst()&&checkpoint.getInt(0)!=0)throw new IllegalStateException("加密迁移日志整理未完成，请重启应用重试；原记录已保留");}
            db.execSQL("INSERT INTO crypto_state(name,value) VALUES('compacted',1)");
        }
    }
    private void migrateTable(SQLiteDatabase db,String table,String[] columns){
        long after=0;
        try(Cursor checkpoint=db.rawQuery("SELECT value FROM crypto_state WHERE name=?",new String[]{table})){if(checkpoint.moveToFirst())after=checkpoint.getLong(0);}
        while(true){
            int count=0;db.beginTransaction();
            try(Cursor rows=db.rawQuery("SELECT rowid AS migration_id,* FROM "+table+" WHERE rowid>? ORDER BY rowid LIMIT 128",new String[]{Long.toString(after)})){
                while(rows.moveToNext()){
                    long id=rows.getLong(0);ContentValues values=new ContentValues();
                    for(String column:columns){
                        int col=rows.getColumnIndexOrThrow(column);if(rows.isNull(col))continue;
                        if("feature".equals(column)){
                            String aad="feature:"+rows.getLong(rows.getColumnIndexOrThrow("user_id"))+":"+rows.getString(rows.getColumnIndexOrThrow("target_hash"));
                            values.put(column,crypto.encrypt(rows.getBlob(col),aad));
                        }else{
                            String value=rows.getString(col),aad="value".equals(column)?"metadata:"+rows.getString(rows.getColumnIndexOrThrow("name")):column;
                            values.put(column,crypto.seal(value,aad));
                            if("response".equals(column))try{JSONObject reply=new JSONObject(value);values.put("response_result",reply.optString("result"));values.put("response_reason",reply.optString("reason"));}catch(Exception e){throw new IllegalStateException("旧版核验回执格式损坏，迁移已暂停；请保留应用数据",e);}
                        }
                    }
                    db.update(table,values,"rowid=?",new String[]{Long.toString(id)});after=id;count++;
                }
                ContentValues checkpoint=new ContentValues();checkpoint.put("name",table);checkpoint.put("value",after);db.insertWithOnConflict("crypto_state",null,checkpoint,SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            if(migration!=null&&count>0)migration.progress(table,after);
            if(count<128)return;
        }
    }
    private String seal(String value,String field){getWritableDatabase();return crypto.seal(value,field);}
    private byte[] encrypt(byte[] value,String field){getWritableDatabase();return crypto.encrypt(value,field);}
    public JSONObject active() throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT snapshot,state,last_sequence,reset_done FROM sessions WHERE state<>'RELEASED'",null)) {
            if(!c.moveToFirst())return null;
            return new JSONObject(crypto.open(c.getString(0),"snapshot")).put("state",c.getString(1)).put("lastSequence",c.getLong(2)).put("resetDone",c.getInt(3)==1);
        }
    }
    public JSONObject presenceProof(String origin) throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT snapshot FROM sessions ORDER BY rowid DESC",null)) {
            while(c.moveToNext()){JSONObject value=new JSONObject(crypto.open(c.getString(0),"snapshot"));if(origin.equals(value.optString("origin")))return value;}
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
            return !c.moveToFirst()||origin.equals(new JSONObject(crypto.open(c.getString(0),"snapshot")).optString("origin"));
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
            ContentValues v=new ContentValues();v.put("snapshot",seal(session.toString(),"snapshot"));v.put("state",old!=null&&!"ACTIVE".equals(old.getString("state"))?old.getString("state"):session.getString("state"));
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
            ContentValues v=new ContentValues();v.put("session_id",session.getString("sessionId"));v.put("sequence",sequence);v.put("event_id",out.getString("eventId"));v.put("payload",seal(out.toString(),"payload"));v.put("created_at",now);db.insertOrThrow("events",null,v);
            v=new ContentValues();v.put("last_sequence",sequence);db.update("sessions",v,"id=?",new String[]{session.getString("sessionId")});
            db.setTransactionSuccessful();return out;
        }finally{db.endTransaction();}
    }
    public List<JSONObject> pending(String id) throws Exception {
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,response FROM events WHERE session_id=? AND confirmed_at IS NULL ORDER BY sequence LIMIT 20",new String[]{id})){
            while(c.moveToNext()){JSONObject e=new JSONObject(crypto.open(c.getString(0),"payload"));if(!c.isNull(1))e.put("savedResponse",new JSONObject(crypto.open(c.getString(1),"response")));out.add(e);}
        }return out;
    }
    public long pendingCount(String id){return DatabaseUtils.longForQuery(getReadableDatabase(),"SELECT COUNT(*) FROM events WHERE session_id=? AND confirmed_at IS NULL",new String[]{id});}
    public void receipt(JSONObject event,JSONObject response) throws Exception {
        TerminalProtocol.validateReceipt(event,response);
        ContentValues v=new ContentValues();v.put("response",seal(response.toString(),"response"));v.put("response_result",response.optString("result"));v.put("response_reason",response.optString("reason"));
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
    public String metadata(String name,String fallback){try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE name=?",new String[]{name})){return c.moveToFirst()?crypto.open(c.getString(0),"metadata:"+name):fallback;}}
    public void metadata(String name,String value,boolean write){ContentValues v=new ContentValues();v.put("name",name);v.put("value",seal(value,"metadata:"+name));getWritableDatabase().insertWithOnConflict("metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public void clearFaces(){getWritableDatabase().delete("faces",null,null);}
    public long faces(){return DatabaseUtils.queryNumEntries(getReadableDatabase(),"faces");}
    public Cursor faceBatch(int afterId,int limit){
        if(afterId<0||limit<1||limit>128)throw new IllegalArgumentException("无效的人员加载批次");
        return new android.database.CursorWrapper(getReadableDatabase().rawQuery("SELECT local_id,feature,user_id,target_hash FROM faces WHERE local_id>? ORDER BY local_id LIMIT ?",new String[]{String.valueOf(afterId),String.valueOf(limit)})){
            @Override public byte[] getBlob(int column){return column==1?crypto.decrypt(super.getBlob(1),"feature:"+getLong(2)+":"+getString(3)):super.getBlob(column);}
        };
    }
    public int faceId(long user){try(Cursor c=getReadableDatabase().rawQuery("SELECT local_id FROM faces WHERE user_id=?",new String[]{Long.toString(user)})){return c.moveToFirst()?c.getInt(0):0;}}
    public long userForFace(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT user_id FROM faces WHERE local_id=?",new String[]{Integer.toString(id)})){return c.moveToFirst()?c.getLong(0):0;}}
    public int saveFace(long user,String hash,byte[] feature){
        ContentValues v=new ContentValues();v.put("user_id",user);v.put("target_hash",hash);v.put("feature",encrypt(feature,"feature:"+user+":"+hash));
        int id=faceId(user);if(id==0){long next=getWritableDatabase().insertOrThrow("faces",null,v);if(next>Integer.MAX_VALUE)throw new IllegalStateException("本地人脸编号达到上限");return (int)next;}
        getWritableDatabase().update("faces",v,"local_id=?",new String[]{Integer.toString(id)});return id;
    }
    public JSONObject backupRecords(String origin,String fingerprint)throws Exception {
        JSONObject active=active();if(active==null)throw new IllegalStateException("请先登录原账号后备份记录");
        if(!origin.equals(active.optString("origin"))||!fingerprint.equals(active.optString("fingerprint")))throw new IllegalStateException("设备或平台不一致");
        org.json.JSONArray rows=new org.json.JSONArray();long bytes=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT sequence,event_id,payload,response,confirmed_at,created_at FROM events WHERE session_id=? ORDER BY sequence",new String[]{active.getString("sessionId")})){
            while(c.moveToNext()){
                JSONObject row=new JSONObject().put("sequence",c.getLong(0)).put("eventId",c.getString(1)).put("payload",crypto.open(c.getString(2),"payload")).put("response",c.isNull(3)?JSONObject.NULL:crypto.open(c.getString(3),"response")).put("confirmedAt",c.isNull(4)?JSONObject.NULL:c.getLong(4)).put("createdAt",c.getLong(5));
                bytes+=row.toString().getBytes("UTF-8").length;if(bytes>24L*1024*1024)throw new IllegalStateException("记录超过备份容量，请联系管理员归档");rows.put(row);
            }
        }
        return new JSONObject().put("format",1).put("origin",origin).put("fingerprint",fingerprint).put("sessionId",active.getString("sessionId")).put("createdAt",System.currentTimeMillis()).put("events",rows);
    }
    public int restoreRecords(JSONObject archive,String origin,String fingerprint)throws Exception {
        JSONObject active=active();if(active==null)throw new IllegalStateException("请先登录原账号；本功能不恢复卸载后丢失的会话");
        String id=active.getString("sessionId");
        if(archive.optInt("format")!=1||!origin.equals(archive.optString("origin"))||!fingerprint.equals(archive.optString("fingerprint"))||!id.equals(archive.optString("sessionId"))||!origin.equals(active.optString("origin"))||!fingerprint.equals(active.optString("fingerprint")))throw new IllegalStateException("备份必须属于本设备、本平台及当前会话");
        org.json.JSONArray rows=archive.getJSONArray("events");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();int added=0;
        try{for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),event=new JSONObject(row.getString("payload"));long seq=row.getLong("sequence");
            if(seq<1||seq>active.getLong("lastSequence")||!id.equals(event.optString("sessionId"))||seq!=event.optLong("sequence")||!row.getString("eventId").equals(event.optString("eventId")))throw new IllegalStateException("备份记录序号或会话不匹配，未恢复任何记录");
            try(Cursor existing=db.rawQuery("SELECT event_id,payload FROM events WHERE session_id=? AND sequence=?",new String[]{id,Long.toString(seq)})){
                if(existing.moveToFirst()){if(!row.getString("eventId").equals(existing.getString(0))||!row.getString("payload").equals(crypto.open(existing.getString(1),"payload")))throw new IllegalStateException("备份与现有记录冲突，未覆盖原记录");continue;}
            }
            if(!row.isNull("response"))TerminalProtocol.validateReceipt(event,new JSONObject(row.getString("response")));
            ContentValues v=new ContentValues();v.put("session_id",id);v.put("sequence",seq);v.put("event_id",row.getString("eventId"));v.put("payload",seal(row.getString("payload"),"payload"));v.put("created_at",row.getLong("createdAt"));
            if(!row.isNull("response")){JSONObject reply=new JSONObject(row.getString("response"));v.put("response",seal(reply.toString(),"response"));v.put("response_result",reply.optString("result"));v.put("response_reason",reply.optString("reason"));}
            // Reconfirm restored receipts with the platform. Never treat restoration as new admission.
            db.insertOrThrow("events",null,v);added++;
        }db.setTransactionSuccessful();return added;}finally{db.endTransaction();}
    }
    public JSONObject dashboard(String sessionID) throws Exception {
        java.util.Calendar day=java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        day.set(java.util.Calendar.HOUR_OF_DAY,0);day.set(java.util.Calendar.MINUTE,0);day.set(java.util.Calendar.SECOND,0);day.set(java.util.Calendar.MILLISECOND,0);
        JSONObject data=new JSONObject().put("localFaces",faces()).put("pending",pendingCount(sessionID));
        // Non-identifying result codes are indexed separately from encrypted receipts.
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN response_result='PASS' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN response IS NULL THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN response_reason IN ('NOT_EFFECTIVE','NOT_YET_EFFECTIVE') THEN 1 ELSE 0 END),0) FROM events WHERE session_id=? AND created_at>=?",new String[]{sessionID,Long.toString(day.getTimeInMillis())})){
            c.moveToFirst();data.put("today",c.getLong(0)).put("passed",c.getLong(1)).put("awaiting",c.getLong(2)).put("notEffective",c.getLong(3)).put("denied",c.getLong(0)-c.getLong(1)-c.getLong(2)-c.getLong(3));
        }
        java.util.HashSet<Long> pendingPeople=new java.util.HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,response FROM events WHERE session_id=? AND created_at>=? AND response IS NOT NULL",new String[]{sessionID,Long.toString(day.getTimeInMillis())})){
            while(c.moveToNext()) { JSONObject reply=new JSONObject(crypto.open(c.getString(1),"response"));String reason=reply.optString("reason");long user=new JSONObject(crypto.open(c.getString(0),"payload")).optLong("userId");
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
                else {JSONObject reply=new JSONObject(crypto.open(c.getString(1),"response")),event=new JSONObject(crypto.open(c.getString(0),"payload"));
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
            return c.moveToFirst()?crypto.decrypt(c.getBlob(0),"feature:"+user+":"+hash):null;
        }
    }
    public void deleteFace(long user){getWritableDatabase().delete("faces","user_id=?",new String[]{Long.toString(user)});}
}
