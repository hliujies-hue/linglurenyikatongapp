package com.lingluren.yikatong.terminal;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.*;
import java.security.MessageDigest;
/** Run on the single worker, serialized with frame processing. */
public final class SyncWorker {
 private final TerminalApi api;private final LocalStore store;private final FaceEngine engine;
 public SyncWorker(TerminalApi a,LocalStore s,FaceEngine e){api=a;store=s;engine=e;}
 public void upload()throws Exception{
  JSONArray events=store.pending();
  if(events.length()>0){JSONObject r=api.post("events",new JSONObject().put("events",events));JSONArray ack=r.getJSONArray("acknowledgedEventIds");
   java.util.Set<String> sent=new java.util.HashSet<>();for(int i=0;i<events.length();i++)sent.add(events.getJSONObject(i).getString("eventId"));
   for(int i=0;i<ack.length();i++)if(!sent.contains(ack.getString(i)))throw new IllegalStateException("平台确认包含未发送事件");
   store.acknowledge(ack);
  }store.cleanup(System.currentTimeMillis());
 }
 public boolean syncPage()throws Exception{
  String cursor=store.meta("cursor","");
  JSONObject page=api.post("sync",new JSONObject().put("cursor",cursor).put("limit",25));
  JSONArray changes=page.getJSONArray("changes");
  if(changes.length()>25)throw new IllegalStateException("同步批次过大");
  for(int i=0;i<changes.length();i++){
   JSONObject row=changes.getJSONObject(i);String person=row.getString("subjectId");long revision=row.getLong("revision");
   long applied=Long.parseLong(store.meta("revision:"+person,"-1"));if(revision<=applied)continue;
   if("DELETE".equals(row.getString("operation")))store.deleteFace(person,engine);
   else if("UPSERT".equals(row.getString("operation"))){
    byte[] bytes=api.photo(row.getString("photoPath"));
    StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))digest.append(String.format("%02x",b&255));
    if(!digest.toString().equalsIgnoreCase(row.getString("photoSha256")))throw new IllegalStateException("照片校验失败");
    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    if(options.outWidth<=0||options.outHeight<=0||options.outWidth>16000||options.outHeight>16000)throw new IllegalArgumentException("照片尺寸无效");
    options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2000)options.inSampleSize*=2;
    options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    if(bitmap==null)throw new IllegalArgumentException("照片解码失败");
    try{store.putFace(person,revision,row.getLong("expiresAt"),engine.extract(bitmap),engine);}finally{bitmap.recycle();}
   }else throw new IllegalArgumentException("未知同步操作");
   store.metaPut("revision:"+person,Long.toString(revision));
  }
  String next=page.getString("nextCursor");
  api.post("sync/ack",new JSONObject().put("batchId",page.getString("batchId")).put("cursor",next));
  store.metaPut("cursor",next);
  return page.getBoolean("caughtUp");
 }
}
