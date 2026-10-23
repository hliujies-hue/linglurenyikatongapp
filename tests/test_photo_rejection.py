"""Run actual runtime photo-sync methods with deterministic SDK/network/storage doubles.
SQLite persistence/encryption is covered separately by the Android instrumented test.
"""
from pathlib import Path
import shutil, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'overlays/app/src/main/java/com/lingluren/yikatong/terminal/TerminalRuntime.java').read_text()
methods=source[source.index('    private String photoRevision('):source.index('    private void acknowledge(')]
harness=r"""
import java.util.*;
public class PhotoRejectionTest {
 static class JSONObject {
  Map<String,Object> data=new HashMap<>();
  JSONObject put(String k,Object v){data.put(k,v);return this;}
  String getString(String k){if(!data.containsKey(k))throw new IllegalStateException(k);return data.get(k).toString();}
  String optString(String k){return optString(k,"");}String optString(String k,String fallback){return data.containsKey(k)?getString(k):fallback;}
  long getLong(String k){return ((Number)data.get(k)).longValue();}long optLong(String k){return data.containsKey(k)?getLong(k):0;}
  boolean optBoolean(String k){return Boolean.TRUE.equals(data.get(k));}
  JSONArray getJSONArray(String k){return (JSONArray)data.get(k);}
 }
 static class JSONArray {
  List<JSONObject> rows=new ArrayList<>();JSONArray put(JSONObject o){rows.add(o);return this;}
  int length(){return rows.size();}JSONObject getJSONObject(int i){return rows.get(i);}
 }
 static class SystemClock {static long elapsedRealtime(){return 0;}}
 static class TerminalProtocol {static JSONObject ref(JSONObject s){return new JSONObject();}}
 static class Store {
  Map<String,String> rejected=new HashMap<>();boolean failSave;
  String key(String o,long u,String r){return o+":"+u+":"+r;}
  String rejectedPhoto(String o,long u,String r){return rejected.getOrDefault(key(o,u,r),"");}
  void rejectPhoto(String o,long u,String r,String reason){if(failSave)throw new IllegalStateException("disk");rejected.put(key(o,u,r),reason);}
  int faces(){return 0;}
 }
 interface Work<T>{T run()throws Exception;}
 static class Algorithms {<T>T call(Work<T>w)throws Exception{return w.run();}boolean stalled(){return false;}}
 static class TerminalFaceEngine {
  static class PhotoRejectedException extends Exception {PhotoRejectedException(){super("no face");}}
  int extracts,deletes;boolean bad=true,failDelete;Set<Long> faces=new HashSet<>();
  boolean reuse(Store s,long u,String h){return false;}
  void upsert(Store s,long u,String h,byte[] p)throws Exception{extracts++;if(bad)throw new PhotoRejectedException();faces.add(u);}
  void delete(Store s,long u)throws Exception{deletes++;if(failDelete)throw new IllegalStateException("cleanup");faces.remove(u);}
  boolean ready(){return true;}
 }
 static class PlatformClient {
  static class PhotoException extends java.io.IOException {boolean rejected;PhotoException(boolean r){super("photo source");rejected=r;}}
  JSONArray tasks=new JSONArray(),lastAcks;int downloads,ackCalls;boolean loseAck,networkFailure,permanentSource;
  JSONObject request(String path,JSONObject body)throws Exception {
   if(!path.endsWith("ack-batch"))return new JSONObject().put("items",tasks);
   ackCalls++;lastAcks=body.getJSONArray("items");if(loseAck)throw new java.io.IOException("lost ack");
   JSONArray results=new JSONArray();for(JSONObject ack:lastAcks.rows)results.put(new JSONObject().put("taskId",ack.getLong("taskId")).put("acknowledged",true));
   return new JSONObject().put("items",results);
  }
  byte[] photo(String u)throws Exception{downloads++;if(networkFailure||permanentSource)throw new PhotoException(permanentSource);return new byte[0];}
 }
 Store store=new Store();PlatformClient client=new PlatformClient();Algorithms algorithms=new Algorithms();TerminalFaceEngine engine=new TerminalFaceEngine();
 JSONObject session=new JSONObject().put("origin","https://one.test");boolean ready=true,pendingPeople=true;long lastPersonnelAckMs;String lastPhotoIssue;
 void emit(String s){}String safe(Throwable e){return e.getMessage();}
 __METHODS__
 static JSONObject task(long id,String revision,String hash,String op){return new JSONObject().put("taskId",id).put("userId",7L).put("receipt","receipt").put("grantGeneration",id).put("targetHash",hash).put("photoRevision",revision).put("operation",op).put("photoUrl","photo");}
 void set(JSONObject...tasks){client.tasks=new JSONArray();for(JSONObject t:tasks)client.tasks.put(t);}
 void attempt()throws Exception{try{syncPeopleBatch();}catch(java.io.IOException expected){}}
 static void check(boolean c,String why){if(!c)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  PhotoRejectionTest r=new PhotoRejectionTest();r.engine.faces.add(7L);r.set(task(1,"v1","hash1","UPSERT"));r.client.loseAck=true;r.attempt();
  check(r.client.downloads==1&&r.engine.extracts==1&&!r.engine.faces.contains(7L),"reject removes old face");
  r.attempt();check(r.client.downloads==1&&r.engine.extracts==1&&r.client.ackCalls==2,"lost ACK only resends receipt");
  r.client.loseAck=false;r.set(task(2,"v1","changed-grant","UPSERT"));r.attempt();
  check(r.client.downloads==1&&r.client.lastAcks.getJSONObject(0).optBoolean("photoRejected"),"same photo new generation remains rejected");
  PhotoRejectionTest restarted=new PhotoRejectionTest();restarted.store=r.store;restarted.set(task(3,"v1","new-session","UPSERT"));restarted.attempt();check(restarted.client.downloads==0,"new runtime retains persisted rejection");
  r.engine.bad=false;r.set(task(4,"v2","hash2","UPSERT"));r.attempt();check(r.client.downloads==2&&r.engine.faces.contains(7L)&&r.client.lastAcks.getJSONObject(0).optBoolean("applied"),"replacement resumes");
  r.set(task(5,"v1","delete","DELETE"));r.attempt();check(!r.engine.faces.contains(7L)&&r.client.lastAcks.getJSONObject(0).optBoolean("applied"),"revocation bypasses photo guard");
  r=new PhotoRejectionTest();r.client.networkFailure=true;r.set(task(1,"v1","h","UPSERT"));r.attempt();r.attempt();check(r.client.downloads==2&&r.store.rejected.isEmpty(),"transient failures retry");
  r=new PhotoRejectionTest();r.engine.failDelete=true;r.set(task(1,"v1","h","UPSERT"));try{r.syncPeopleBatch();}catch(IllegalStateException expected){}
  check(r.client.ackCalls==1&&!r.client.lastAcks.getJSONObject(0).optBoolean("photoRejected"),"cleanup failure not falsely acknowledged");
  r.engine.failDelete=false;r.attempt();check(r.client.downloads==1&&r.engine.extracts==1&&r.client.lastAcks.getJSONObject(0).optBoolean("localFaceRemoved"),"cleanup retries without photo");
  r=new PhotoRejectionTest();r.set(task(1,"v1","h","UPSERT"),task(2,"","delete","DELETE").put("userId",8L));r.attempt();check(r.client.lastAcks.length()==2,"bad photo does not starve following task");
  r=new PhotoRejectionTest();r.client.permanentSource=true;r.set(task(1,"v1","h","UPSERT"));r.attempt();r.attempt();check(r.client.downloads==1,"permanent source rejection cached");
  r=new PhotoRejectionTest();r.store.failSave=true;r.set(task(1,"v1","h","UPSERT"));try{r.syncPeopleBatch();}catch(IllegalStateException expected){}check(!r.client.lastAcks.getJSONObject(0).optBoolean("photoRejected"),"failed persistence not acknowledged as final rejection");
  System.out.println("PASS production sync: lost ACK, new grant/session, replacement, deletion, network retry, cleanup/save failure, batch fairness and permanent source");
 }
}
""".replace('__METHODS__',methods)
with tempfile.TemporaryDirectory(prefix='ykt-photo-') as directory:
 path=Path(directory)/'PhotoRejectionTest.java';path.write_text(harness)
 compiler=['javac'] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
 subprocess.run([*compiler,'-encoding','UTF-8','-d',directory,str(path)],check=True)
 subprocess.run(['java','-cp',directory,'PhotoRejectionTest'],check=True,timeout=20)
