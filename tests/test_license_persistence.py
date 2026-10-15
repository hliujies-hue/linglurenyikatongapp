"""Runs the real license manager with fake Android storage/vendor callbacks.
This checks persistence and callback fencing, not a real hardware activation.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

SOURCES = {
"android/content/Context.java": '''package android.content;
import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0;public boolean failCommit;
 private final Map<String,SharedPreferences> stores=new HashMap<>();
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String n,int mode){return stores.computeIfAbsent(n,k->new SharedPreferences(this));}
}''',
"android/content/SharedPreferences.java": '''package android.content;
import java.util.*;
public class SharedPreferences {
 final Context context;final Map<String,String> values=new HashMap<>();
 SharedPreferences(Context c){context=c;} public synchronized String getString(String k,String d){return values.getOrDefault(k,d);}
 public Editor edit(){return new Editor();} public class Editor {
  final Map<String,String> pending=new HashMap<>();public Editor putString(String k,String v){pending.put(k,v);return this;}
  public boolean commit(){if(context.failCommit)return false;synchronized(SharedPreferences.this){values.putAll(pending);}return true;}
 }
}''',
"android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }",
"android/os/Handler.java": "package android.os; public class Handler { public Handler(Looper l){} public void post(Runnable r){r.run();} }",
"com/baidu/idl/main/facesdk/utils/PreferencesUtil.java": '''package com.baidu.idl.main.facesdk.utils;
public class PreferencesUtil {public static String value="";public static String getString(String k,String d){return value;}public static void putString(String k,String v){value=v;}}''',
"com/example/authlibrary/BdFaceAuth.java": '''package com.example.authlibrary;
import android.content.Context;
public class BdFaceAuth {public interface Callback {void call(int error,String message);}public static volatile Callback callback;public static volatile String submitted;
public String getDeviceId(Context c){return "real-vendor-interface-test-double";}
public void initLicenseOnLine(Context c,String code,Callback cb){submitted=code;callback=cb;}}
''',
"com/lingluren/yikatong/terminal/TerminalRuntime.java": '''package com.lingluren.yikatong.terminal;
import android.content.Context;
public class TerminalRuntime {public static final TerminalRuntime instance=new TerminalRuntime();public volatile int authorized,failed;public volatile String backedUp="";
public static TerminalRuntime get(Context c){return instance;}public void sdkAuthorization(String status,String code){if(code!=null&&!code.isEmpty()){if(!status.equals("AUTHORIZED"))throw new AssertionError("unverified code uploaded");backedUp=code;}}
public void authorize(){authorized++;}public void activationFailed(String error){failed++;}}
''',
"com/lingluren/yikatong/terminal/LicensePersistenceTest.java": '''package com.lingluren.yikatong.terminal;
import android.content.Context;import com.example.authlibrary.BdFaceAuth;import com.baidu.idl.main.facesdk.utils.PreferencesUtil;
import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import java.util.function.BooleanSupplier;
public class LicensePersistenceTest {
 static final List<TerminalLicenseManager> managers=new ArrayList<>();
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void waitFor(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("callback timed out");Thread.sleep(2);}}
 static TerminalLicenseManager fresh(Context c)throws Exception{Constructor<TerminalLicenseManager> k=TerminalLicenseManager.class.getDeclaredConstructor(Context.class);k.setAccessible(true);TerminalLicenseManager m=k.newInstance(c);managers.add(m);return m;}
 static BdFaceAuth.Callback begin(TerminalLicenseManager m,String code)throws Exception{BdFaceAuth.callback=null;check(m.start(code),"activation started");waitFor(()->BdFaceAuth.callback!=null);return BdFaceAuth.callback;}
 public static void main(String[] args)throws Exception{try{
  String good="ABCD-EF12-GH34-JK56",bad="WRNG-WRNG-WRNG-WRNG";
  Context c=new Context();PreferencesUtil.value=good;TerminalLicenseManager m=fresh(c);
  check(m.savedCode().equals(good),"upgrade reads legacy saved key");
  BdFaceAuth.Callback failed=begin(m,bad);failed.call(290004,"");waitFor(()->!m.state().busy());
  check(m.savedCode().equals(good)&&TerminalRuntime.instance.backedUp.isEmpty(),"failed candidate cannot replace or upload saved code");
  BdFaceAuth.Callback accepted=begin(m,good);failed.call(0,"");Thread.sleep(20);check(!m.state().authorized(),"old callback cannot authorize new request");
  accepted.call(0,"");waitFor(()->TerminalRuntime.instance.authorized==1);
  check(m.savedCode().equals(good)&&TerminalRuntime.instance.backedUp.equals(good),"successful code committed before backup and authorize");
  accepted.call(290002,"");Thread.sleep(20);check(m.state().authorized(),"duplicate failure cannot revoke success");
  PreferencesUtil.value="";TerminalLicenseManager restarted=fresh(c);check(restarted.savedCode().equals(good),"process restart retains committed key even if vendor preferences are absent");
  BdFaceAuth.Callback retry=begin(restarted,restarted.savedCode());retry.call(1009,"");waitFor(()->!restarted.state().busy());
  check(restarted.savedCode().equals(good),"network failure preserves saved key");
  c.failCommit=true;BdFaceAuth.Callback disk=begin(restarted,good);disk.call(0,"");waitFor(()->!restarted.state().busy());
  check(restarted.state().error==-2003&&TerminalRuntime.instance.authorized==1,"failed durable save never claims completed activation");
  Context clean=new Context();TerminalLicenseManager reinstall=fresh(clean);check(reinstall.savedCode().isEmpty(),"clean install does not invent a local license");
  BdFaceAuth.Callback restored=begin(reinstall,good);restored.call(0,"");waitFor(()->TerminalRuntime.instance.authorized==2);
  check(reinstall.savedCode().equals(good),"restored code still requires actual algorithm callback");
  for(int error:new int[]{1007,1006,1009,1011,1010,290004,290009,290002,290003,290008,11,290006,7,8,9,14,12,13,-2001,-2002,-2003,999}){
   String text=TerminalActivationGate.describe(error);check(!text.contains("百度")&&!text.contains("SDK"),"product terms in errors");}
  System.out.println("PASS: upgrade cache, restart persistence, failed-code retention, stale callbacks, save failure, restored-code verification and product wording (fake Android/vendor)");
 }finally{for(TerminalLicenseManager m:managers){Field f=TerminalLicenseManager.class.getDeclaredField("worker");f.setAccessible(true);((ScheduledExecutorService)f.get(m)).shutdownNow();}}}
}''',
}

with tempfile.TemporaryDirectory(prefix="ykt-license-") as directory:
    root = Path(directory)
    files = []
    for name, source in SOURCES.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
        files.append(str(path))
    production = Path(__file__).resolve().parents[1] / "overlays/app/src/main/java/com/lingluren/yikatong/terminal"
    compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
    subprocess.run([*compiler, "-encoding", "UTF-8", "-d", str(root), *files,
                    str(production / "TerminalActivationGate.java"), str(production / "TerminalLicenseManager.java")], check=True)
    subprocess.run(["java", "-cp", str(root), "com.lingluren.yikatong.terminal.LicensePersistenceTest"], check=True, timeout=20)
