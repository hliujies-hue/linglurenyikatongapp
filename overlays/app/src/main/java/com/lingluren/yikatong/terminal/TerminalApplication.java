package com.lingluren.yikatong.terminal;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Set;
/** Short-lived, process-only permission for the terminal's private algorithm settings. */
public final class TerminalApplication extends Application implements Application.ActivityLifecycleCallbacks {
 private static long until;
 private final Set<Activity> settings=new HashSet<>();
 private final Handler main=new Handler(Looper.getMainLooper());
 private int started;
 public static void unlock(){until=SystemClock.elapsedRealtime()+10*60*1000;}
 public static void lock(){until=0;}
 private static boolean allowed(){return until>SystemClock.elapsedRealtime();}
 private boolean protectedScreen(Activity a){return a.getClass().getName().startsWith("com.example.settinglibrary.");}
 private boolean terminalSettings(Activity a){return "com.example.settinglibrary.SettingActivity".equals(a.getClass().getName());}
 @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
 public void onActivityCreated(Activity a,Bundle b){if(protectedScreen(a)){settings.add(a);if(!allowed()||!terminalSettings(a))a.finish();}}
 public void onActivityStarted(Activity a){started++;}
 public void onActivityResumed(Activity a){if(protectedScreen(a)){if(!allowed()||!terminalSettings(a)){a.finish();return;}main.postDelayed(()->{if(!allowed())for(Activity screen:new HashSet<>(settings))screen.finish();},Math.max(1,until-SystemClock.elapsedRealtime()));}}
 public void onActivityPaused(Activity a){}
 public void onActivityStopped(Activity a){started=Math.max(0,started-1);if(!a.isChangingConfigurations())main.postDelayed(()->{if(started==0){lock();for(Activity screen:new HashSet<>(settings))screen.finish();}},200);}
 public void onActivitySaveInstanceState(Activity a,Bundle b){}
 public void onActivityDestroyed(Activity a){settings.remove(a);}
}
