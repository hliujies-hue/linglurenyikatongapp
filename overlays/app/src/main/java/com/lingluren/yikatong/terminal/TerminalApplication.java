package com.lingluren.yikatong.terminal;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Set;
/** Short-lived, process-only permission for private Demo settings screens. */
public final class TerminalApplication extends Application implements Application.ActivityLifecycleCallbacks {
 private static long until;
 private final Set<Activity> settings=new HashSet<>();
 private final Set<Activity> explained=new HashSet<>();
 private final Handler main=new Handler(Looper.getMainLooper());
 private int started;
 public static void unlock(){until=SystemClock.elapsedRealtime()+10*60*1000;}
 public static void lock(){until=0;}
 private static boolean allowed(){return until>SystemClock.elapsedRealtime();}
 private boolean protectedScreen(Activity a){return a.getClass().getName().startsWith("com.example.settinglibrary.");}
 @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
 public void onActivityCreated(Activity a,Bundle b){if(protectedScreen(a)){settings.add(a);if(!allowed())a.finish();}}
 public void onActivityStarted(Activity a){started++;}
 public void onActivityResumed(Activity a){if(protectedScreen(a)){if(!allowed()){a.finish();return;}explainSettings(a);main.postDelayed(()->{if(!allowed())for(Activity screen:new HashSet<>(settings))screen.finish();},Math.max(1,until-SystemClock.elapsedRealtime()));}}
 public void onActivityPaused(Activity a){}
 public void onActivityStopped(Activity a){started=Math.max(0,started-1);if(!a.isChangingConfigurations())main.postDelayed(()->{if(started==0){lock();for(Activity screen:new HashSet<>(settings))screen.finish();}},200);}
 public void onActivitySaveInstanceState(Activity a,Bundle b){}
 public void onActivityDestroyed(Activity a){settings.remove(a);explained.remove(a);}
 private void explainSettings(Activity a){
  if(!explained.add(a))return;
  String screen=a.getClass().getSimpleName(),note="";
  if("SettingActivity".equals(screen)){
   for(String name:new String[]{"gate_lens_settings","gate_picture_optimization","gate_log_settings"}){
    int id=a.getResources().getIdentifier(name,"id",a.getPackageName());android.view.View view=a.findViewById(id);if(view!=null)view.setVisibility(android.view.View.GONE);
   }
   note="本核验端使用普通手机RGB相机。摄像头默认后置，在核验页面手动切换；方向由系统计算。镜头方向、镜像、图像优化和原生日志设置未接入，入口已隐藏。";
  }else if("FaceLivinessTypeActivity".equals(screen))note="此页只有RGB活体分数在核验端生效。RGB活体强制开启；关闭活体、红外、深度、多目模式、相机类型和分辨率选项不会改变本机核验方式。";
  else if("GateFaceDetectActivity".equals(screen))note="此页只有生活照识别阈值（LiveThreshold）用于人员库检索。证件照、RGB+红外阈值、识别模型切换和补光设置未接入本核验端。";
  else if("GateConfigQualtifyActivity".equals(screen))note="姿态、模糊、光照及遮挡阈值参与核验。质量检查强制开启，此页关闭质量控制的开关不生效。";
  if(!note.isEmpty())new android.app.AlertDialog.Builder(a).setTitle("本机实际生效范围").setMessage(note).setPositiveButton("已了解",null).show();
 }
}
