package com.lingluren.yikatong.terminal;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.*;
import com.lingluren.yikatong.core.EventPolicy;
/** No public record-detail screen. Only encrypted upload cache and encrypted templates. */
public final class LocalStore extends SQLiteOpenHelper {
 private final Vault vault;
 public LocalStore(Context c,Vault v){super(c,"terminal.db",null,1);vault=v;setWriteAheadLoggingEnabled(true);}
 public void onCreate(SQLiteDatabase db){
  db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY, created INTEGER NOT NULL, ack INTEGER NOT NULL DEFAULT 0, payload BLOB NOT NULL)");
  db.execSQL("CREATE INDEX pending_events ON events(ack,created)");
  db.execSQL("CREATE TABLE faces(id INTEGER PRIMARY KEY AUTOINCREMENT, subject TEXT UNIQUE NOT NULL, revision INTEGER NOT NULL, expires INTEGER NOT NULL, feature BLOB NOT NULL)");
  db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
 }
 public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Migration required; never drop pending records");}
 public synchronized void queue(JSONObject event)throws Exception{
  ContentValues v=new ContentValues();v.put("id",event.getString("eventId"));v.put("created",System.currentTimeMillis());
  v.put("payload",vault.seal(event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  getWritableDatabase().insertOrThrow("events",null,v);
 }
 public synchronized JSONArray pending()throws Exception{
  JSONArray items=new JSONArray();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM events WHERE ack=0 ORDER BY created LIMIT 100",null)){
   while(c.moveToNext()) items.put(new JSONObject(new String(vault.open(c.getBlob(0)),java.nio.charset.StandardCharsets.UTF_8)));
  }return items;
 }
 public synchronized void acknowledge(JSONArray ids)throws Exception{
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
   for(int i=0;i<ids.length();i++){ContentValues v=new ContentValues();v.put("ack",1);db.update("events",v,"id=?",new String[]{ids.getString(i)});}
   db.setTransactionSuccessful();
  }finally{db.endTransaction();}
 }
 public synchronized void cleanup(long now){
  long last=Long.parseLong(meta("cleanup","0"));
  if(last!=0 && now-last<EventPolicy.SEVEN_DAYS)return;
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
   db.delete("events","ack=1 AND created<=?",new String[]{Long.toString(now-EventPolicy.SEVEN_DAYS)});
   metaPut("cleanup",Long.toString(now));db.setTransactionSuccessful();
  }finally{db.endTransaction();}
 }
 public synchronized long pendingCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM events WHERE ack=0",null)){c.moveToFirst();return c.getLong(0);}}
 public synchronized int faceCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM faces",null)){c.moveToFirst();return c.getInt(0);}}
 public synchronized String subject(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT subject FROM faces WHERE id=?",new String[]{""+id})){return c.moveToFirst()?c.getString(0):"";}}
 public synchronized String meta(String name,String fallback){try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE name=?",new String[]{name})){return c.moveToFirst()?c.getString(0):fallback;}}
 public synchronized void metaPut(String name,String value){ContentValues v=new ContentValues();v.put("name",name);v.put("value",value);getWritableDatabase().insertWithOnConflict("metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
 public synchronized void load(FaceEngine engine)throws Exception{
  engine.clear();
  // Cursor streaming: never materialize 300,000 Java User objects at startup.
  try(Cursor c=getReadableDatabase().rawQuery("SELECT id,feature FROM faces ORDER BY id",null)){
   while(c.moveToNext())engine.put(c.getInt(0),vault.open(c.getBlob(1)));
  }
 }
 public synchronized void putFace(String subject,long revision,long expires,byte[] feature,FaceEngine engine)throws Exception{
  SQLiteDatabase db=getWritableDatabase();int id=0;long previous=-1;
  try(Cursor c=db.rawQuery("SELECT id,revision FROM faces WHERE subject=?",new String[]{subject})){if(c.moveToFirst()){id=c.getInt(0);previous=c.getLong(1);}}
  if(previous>=revision)return;
  db.beginTransaction();try{
   ContentValues v=new ContentValues();v.put("subject",subject);v.put("revision",revision);v.put("expires",expires);v.put("feature",vault.seal(feature));
   if(id==0){long next=db.insertOrThrow("faces",null,v);if(next>Integer.MAX_VALUE)throw new IllegalStateException("SDK ID exhausted");id=(int)next;}
   else{engine.remove(id);db.update("faces",v,"id=?",new String[]{""+id});}
   engine.put(id,feature);db.setTransactionSuccessful();
  }finally{db.endTransaction();}
  // Caller must block recognition and rebuild from DB if a native index mutation fails.
 }
 public synchronized void deleteFace(String subject,FaceEngine engine){
  SQLiteDatabase db=getWritableDatabase();
  try(Cursor c=db.rawQuery("SELECT id FROM faces WHERE subject=?",new String[]{subject})){if(c.moveToFirst())engine.remove(c.getInt(0));}
  db.delete("faces","subject=?",new String[]{subject});
 }
}
