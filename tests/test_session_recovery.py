"""Execute the production recovery methods with deterministic storage/network doubles.

This compiles methods copied directly from TerminalRuntime, not a reimplementation.
It does not certify Android lifecycle, SQLite encryption, HTTP or the face SDK.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / "overlays/app/src/main/java/com/lingluren/yikatong/terminal/TerminalRuntime.java").read_text()
methods = source[source.index("    private boolean markLoginExpired("):source.index('    private volatile String algorithmStallReason')]
harness = r'''
import java.util.*;
import java.util.concurrent.Executor;
public class SessionRecoveryTest {
 interface Done { void done(String error); }
 static class JSONObject {
  final Map<String,String> data=new HashMap<>();
  JSONObject put(String k,String v){data.put(k,v);return this;}
  String optString(String k){return data.getOrDefault(k,"");}
  String getString(String k){if(!data.containsKey(k))throw new IllegalStateException(k);return data.get(k);}
 }
 static class PlatformClient {
  static Exception failure; static String returnedUser="staff",returnedToken="new-token";static int requests;
  String credential="old-token";
  PlatformClient(String origin){}
  static class ApiException extends Exception {int status;ApiException(int s){super("HTTP "+s);status=s;}}
  JSONObject request(String path,JSONObject body)throws Exception {
   requests++;if(failure!=null)throw failure;
   return new JSONObject().put("username",returnedUser).put("accessToken",returnedToken);
  }
  void token(String token){credential=token;}
 }
 static class Store {
  JSONObject current;boolean failSave;int writes;
  void updateAccessToken(String id,String token){
   if(failSave)throw new IllegalStateException("disk failure");
   if(current==null||!id.equals(current.getString("sessionId")))throw new IllegalStateException("changed");
   writes++;current.put("accessToken",token);
  }
  JSONObject active(){return current;}
 }
 final ArrayDeque<Runnable> jobs=new ArrayDeque<>();final Executor actor=jobs::add;
 final Store store=new Store();JSONObject session;PlatformClient client=new PlatformClient("https://example.test");
 boolean signingIn,ready,platformSynced,loginExpired,paused;long nextTick=99;int syncFailures=3,ticks;
 String lastSyncError="expired",lastStatus="";String completion="not-called";boolean callbackBusy;
 SessionRecoveryTest(){session=new JSONObject().put("sessionId","original").put("username","staff").put("origin","https://example.test").put("accessToken","old-token").put("state","ACTIVE").put("lastSequence","7");store.current=session;}
 void pauseCamera(){paused=true;}void emit(String s){lastStatus=s;}
 void complete(Done done,String error){if(done!=null)done.done(error);}
 String safe(Throwable e){return e.getMessage();}
 void tick(){if(signingIn||loginExpired)return;ticks++;}
 void finish(){while(!jobs.isEmpty())jobs.remove().run();}
 Done callback(){return e->{completion=e;callbackBusy=signingIn;};}
 // PRODUCTION METHODS
 __METHODS__
 static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
 static SessionRecoveryTest fresh(){PlatformClient.failure=null;PlatformClient.requests=0;PlatformClient.returnedUser="staff";PlatformClient.returnedToken="new-token";return new SessionRecoveryTest();}
 public static void main(String[] args)throws Exception {
  for(String state:new String[]{"ACTIVE","PAUSING","DRAINING"}){
   SessionRecoveryTest r=fresh();r.session.put("state",state);r.loginExpired=true;
   r.reauthenticate("original","password",r.callback());check(r.signingIn&&r.paused,"synchronous login gate");r.finish();
   check(r.completion==null&&!r.callbackBusy&&!r.signingIn,"completion sees released login gate");
   check(r.ticks==1&&!r.loginExpired&&!r.platformSynced&&!r.ready,"immediate sync, no premature admission");
   check(r.session.getString("state").equals(state)&&r.session.getString("lastSequence").equals("7"),"state and sequence unchanged");
   check(r.store.writes==1&&r.client.credential.equals("new-token"),"saved token installed");
  }
  for(int status:new int[]{401,403,429,503}){
   SessionRecoveryTest r=fresh();r.loginExpired=true;PlatformClient.failure=new PlatformClient.ApiException(status);
   r.reauthenticate("original","wrong",r.callback());r.finish();
   check(r.completion!=null&&!r.callbackBusy&&r.loginExpired,"failed login stays expired");
   check(r.store.writes==0&&r.client.credential.equals("old-token")&&r.ticks==0,"failed login preserves credential");
  }
  SessionRecoveryTest r=fresh();r.reauthenticate("original","one",r.callback());r.reauthenticate("original","two",e->check(e!=null,"parallel login rejected"));r.finish();check(PlatformClient.requests==1,"one network request");
  r=fresh();r.reauthenticate("original","password",r.callback());r.session=new JSONObject().put("sessionId","replacement");r.finish();check(PlatformClient.requests==0&&r.store.writes==0,"queued recovery cannot target replacement session");
  r=fresh();PlatformClient.returnedUser="other";r.reauthenticate("original","password",r.callback());r.finish();check(r.store.writes==0&&r.completion!=null,"mismatched account rejected");
  r=fresh();PlatformClient.returnedToken="";r.reauthenticate("original","password",r.callback());r.finish();check(r.store.writes==0&&r.completion!=null,"empty token rejected");
  r=fresh();r.store.failSave=true;r.reauthenticate("original","password",r.callback());r.finish();check(r.completion!=null&&r.client.credential.equals("old-token")&&r.ticks==0,"disk failure preserves old client");
  r=fresh();r.reauthenticate("original","",r.callback());check(!r.signingIn&&r.jobs.isEmpty()&&r.completion!=null,"empty password rejected");
  for(int status:new int[]{401,403,429,503}){r=fresh();r.ready=true;r.platformSynced=true;boolean marked=r.markLoginExpired(new PlatformClient.ApiException(status));check(marked==(status==401),"only 401 expires login");if(marked)check(!r.ready&&!r.platformSynced&&r.paused&&r.loginExpired,"expired state stops camera");}
  r=fresh();check(!r.markLoginExpired(new java.io.IOException("offline")),"network failure is not login expiry");
  System.out.println("PASS: production recovery methods: immediate sync, completion ordering, single-flight, session fence, account identity, credential save failure and HTTP classification (network/storage doubles)");
 }
}
'''.replace("__METHODS__", methods)
with tempfile.TemporaryDirectory(prefix="ykt-session-") as directory:
    path = Path(directory) / "SessionRecoveryTest.java"
    path.write_text(harness)
    compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
    subprocess.run([*compiler, "-encoding", "UTF-8", "-d", directory, str(path)], check=True)
    subprocess.run(["java", "-cp", directory, "SessionRecoveryTest"], check=True, timeout=20)
