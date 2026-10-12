package com.lingluren.yikatong.terminal;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.lingluren.yikatong.core.EventPolicy;
import org.json.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native UI. No sample user, simulated PASS or offline admission path. */
@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
 private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
 private final Handler ui=new Handler(Looper.getMainLooper());
 private final AtomicBoolean frameBusy=new AtomicBoolean();
 private FaceEngine engine;private Vault vault;private LocalStore store;private TerminalApi api;private SyncWorker sync;
 private volatile boolean active,loggedIn,rosterReady,diagnostic;
 private volatile int screenVersion;
 private volatile String currentEvent="";
 private volatile JSONObject session, messages=new JSONObject();
 private LinearLayout layout;private TextView status;private Camera camera;private TextToSpeech tts;
 private long lastFrame,absentSince=-1;private boolean armed=true;
 private int rotation;private boolean mirror;private boolean cameraPage;
 private String deviceId;private EditText codeInput;
 private final int green=Color.rgb(22,96,76),red=Color.rgb(174,49,49);
 @Override public void onCreate(Bundle state){
  super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_SECURE);
  try{
   vault=new Vault(this);store=new LocalStore(this,vault);engine=new FaceEngine();
   deviceId=getPreferences(0).getString("deviceId","");
   if(deviceId.isEmpty()){deviceId="YKT-"+UUID.randomUUID();getPreferences(0).edit().putString("deviceId",deviceId).commit();}
   tts=new TextToSpeech(this,rc->{if(rc==TextToSpeech.SUCCESS)tts.setLanguage(Locale.SIMPLIFIED_CHINESE);});
   showActivation();
   worker.execute(()->{try{String cached=vault.get("license");if(!cached.isEmpty())activate(cached);}catch(Exception e){showError("授权缓存读取失败，请联系平台人员");}});
   worker.scheduleWithFixedDelay(this::backgroundSync,5,15,TimeUnit.SECONDS);
  }catch(Exception|LinkageError e){new AlertDialog.Builder(this).setMessage("设备组件加载失败，请检查 SDK 安装及手机架构").setPositiveButton("关闭",(d,w)->finish()).show();}
 }
 private void page(String title){
  screenVersion++;currentEvent="";stopCamera();cameraPage=false;diagnostic=false;if(tts!=null)tts.stop();
  ScrollView scroll=new ScrollView(this);layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(28,36,28,28);layout.setBackgroundColor(Color.rgb(243,245,238));scroll.addView(layout);setContentView(scroll);
  TextView heading=text(title,25);heading.setTextColor(green);
  text("一卡通景区刷脸核验端",14);
  status=text("",17);
 }
 private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(0,12,0,12);layout.addView(t);return t;}
 private EditText input(String hint,int type){EditText e=new EditText(this);e.setHint(hint);e.setInputType(type);e.setSingleLine(true);layout.addView(e);return e;}
 private void button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());layout.addView(b);}
 private void showError(String message){ui.post(()->{if(!isFinishing()&&status!=null){status.setText(message);status.setTextColor(red);}});}
 private void showActivation(){page("设备激活");codeInput=input("请输入授权码",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);text("请向平台工作人员获取本机授权码",14);
  button("激活",()->{String code=codeInput.getText().toString().trim();if(code.isEmpty()){showError("请输入授权码");return;}status.setText("正在联网验证，请稍候");worker.execute(()->activate(code));});
 }
 private final AtomicBoolean activating=new AtomicBoolean();
 private void activate(String code){
  if(!activating.compareAndSet(false,true))return;
  try{engine.activate(getApplicationContext(),code,(rc,msg)->{
   activating.set(false);
   if(rc!=0){showError(activationMessage(rc));return;}
   worker.execute(()->{try{vault.save("license",code);engine.initialize(getApplicationContext());ui.post(this::showLogin);}catch(Exception e){showError("设备已激活，"+e.getMessage());}});
  });}catch(Exception|LinkageError e){activating.set(false);showError("激活组件异常，请联系平台人员");}
 }
 private String activationMessage(int rc){return switch(rc){case 290009,290004->"授权码已绑定其他设备（"+rc+"）";case 11,290006->"授权已到期（"+rc+"）";case 1011,1009,13->"网络连接失败，请重试（"+rc+"）";default->"激活失败，请核对授权码并联系平台（"+rc+"）";};}
 private void showLogin(){
  loggedIn=false;rosterReady=false;session=null;page("景区账号登录");
  EditText host=input("平台 HTTPS 地址",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
  host.setText(getPreferences(0).getString("platform","https://ykt.qiliai.com"));
  EditText user=input("后台开通的账号",InputType.TYPE_CLASS_TEXT);
  EditText password=input("密码",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
  button("登录",()->{
   String origin=host.getText().toString().trim(),name=user.getText().toString().trim(),pass=password.getText().toString();
   if(name.isEmpty()||pass.isEmpty()){showError("请输入账号和密码");return;}
   status.setText("正在连接平台");int version=screenVersion;
   worker.execute(()->{try{
    TerminalApi next=new TerminalApi(origin);
    JSONObject caps=next.post("capabilities",new JSONObject().put("protocolVersion",1));
    if(caps.getInt("protocolVersion")!=1||!caps.getBoolean("productionReady"))throw new IllegalStateException("平台核验端接口尚未就绪");
    JSONObject result=next.post("login",new JSONObject().put("username",name).put("password",pass).put("deviceId",deviceId).put("sdkFingerprint",engine.fingerprint(this)).put("appVersion","0.1.0-dev"));
    if(version!=screenVersion)return;
    String scope=result.getString("bindingId");
    // Never silently rebind a device or clear an existing scenic's pending events.
    String existing=store.meta("bindingId","");
    if(!existing.isEmpty()&&!existing.equals(scope))throw new IllegalStateException("设备归属发生变化，请联系平台执行调拨，不能直接登录其他景区");
    next.token(result.getString("token"));api=next;session=result;sync=new SyncWorker(api,store,engine);
    store.metaPut("bindingId",scope);getPreferences(0).edit().putString("platform",origin).apply();
    engine.clear();store.load(engine);loggedIn=true;
    ui.post(()->{if(version==screenVersion){password.setText("");showHome();}});
   }catch(Exception e){if(version==screenVersion)showError(e.getMessage());}});
  });
  text("开发版：平台接口未部署时会阻止登录及通行，不会模拟成功。",13);
  button("相机与算法检查（不核验通行）",()->showCamera(true));
 }
 private void showHome(){
  page("刷脸核验");text("设备编号："+deviceId,13);
  text("当前时间："+java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime().withNano(0),14);
  text("设备已激活 · 账号已登录",16);
  text("本机已存特征："+store.faceCount()+"　待回传："+store.pendingCount(),14);
  status.setText(rosterReady?"准备就绪":"数据准备中，请稍候");
  button("开始刷脸",()->{if(!rosterReady){showError("数据准备中，请稍候");return;}showCamera(false);});
  button("按日期查询景区通过人数",this::showCounts);
  button("刷新状态",this::showHome);
  button("退出账号",()->new AlertDialog.Builder(this).setMessage("退出后将停止扫脸，保留设备授权和待上传记录。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->{
   loggedIn=false;rosterReady=false;currentEvent="";showLogin();
  }).show());
 }
 private void showCounts(){
  page("景区通过人数");EditText from=input("开始日期 YYYY-MM-DD",InputType.TYPE_CLASS_DATETIME);EditText to=input("结束日期 YYYY-MM-DD",InputType.TYPE_CLASS_DATETIME);
  String today=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();from.setText(today);to.setText(today);
  TextView answer=text("同景区所有设备共享平台统计结果",17);
  button("查询",()->{String start=from.getText().toString(),end=to.getText().toString();int version=screenVersion;
   worker.execute(()->{try{LocalDate a=LocalDate.parse(start),b=LocalDate.parse(end);if(a.isAfter(b))throw new IllegalArgumentException("开始日期不能晚于结束日期");
    JSONObject r=api.post("counts",new JSONObject().put("from",start).put("to",end));
    ui.post(()->{if(version==screenVersion)answer.setText("通过人数："+r.optLong("people")+"\n统计口径："+r.optString("countingLabel")+"\n更新时间："+r.optString("updatedAt"));});
   }catch(Exception e){if(version==screenVersion)showError(e.getMessage());}});
  });button("返回",this::showHome);
 }
 private void showCamera(boolean test){
  if(!test&&!BuildConfig.ADMISSION_ENABLED){showError("开发验证版尚未完成平台联调，暂不开放正式通行");return;}
  page(test?"相机与算法检查":"请刷脸核验");diagnostic=test;armed=true;absentSince=-1;
  if(test)text("仅检查取景、检测及活体。不会生成通行结果。",14);
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},7);status.setText("请允许相机权限后重新进入");button("返回",()->{if(loggedIn)showHome();else showLogin();});return;}
  cameraPage=true; SurfaceView preview=new SurfaceView(this);layout.addView(preview,new LinearLayout.LayoutParams(-1,700));
  preview.getHolder().addCallback(new SurfaceHolder.Callback(){
   public void surfaceCreated(SurfaceHolder h){startCamera(h);}
   public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}
   public void surfaceDestroyed(SurfaceHolder h){stopCamera();}
  });
  button("切换前后摄像头",()->{getPreferences(0).edit().putBoolean("rear",!getPreferences(0).getBoolean("rear",false)).apply();showCamera(test);});
  button("返回",()->{if(loggedIn)showHome();else showLogin();});
 }
 private void startCamera(SurfaceHolder holder){
  try{
   boolean rear=getPreferences(0).getBoolean("rear",false);int selected=-1;Camera.CameraInfo info=new Camera.CameraInfo();
   for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.getCameraInfo(i,info);if(info.facing==(rear?Camera.CameraInfo.CAMERA_FACING_BACK:Camera.CameraInfo.CAMERA_FACING_FRONT)){selected=i;break;}}
   if(selected<0)selected=0;Camera.getCameraInfo(selected,info);mirror=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT;
   rotation=info.orientation;camera=Camera.open(selected);Camera.Parameters p=camera.getParameters();
   Camera.Size size=p.getSupportedPreviewSizes().stream().min(Comparator.comparingLong(s->Math.abs((long)s.width*s.height-640L*480))).get();
   p.setPreviewSize(size.width,size.height);p.setPreviewFormat(android.graphics.ImageFormat.NV21);camera.setParameters(p);
   camera.setDisplayOrientation(mirror?(360-info.orientation)%360:info.orientation);camera.setPreviewDisplay(holder);
   camera.setPreviewCallback((data,cam)->frame(data,size.width,size.height));camera.startPreview();
  }catch(Exception e){stopCamera();showError("相机不可用，请检查权限或重启设备");}
 }
 private void stopCamera(){if(camera!=null){camera.setPreviewCallback(null);camera.stopPreview();camera.release();camera=null;}}
 private void frame(byte[] data,int width,int height){
  long now=SystemClock.elapsedRealtime();if(!active||now-lastFrame<250||!frameBusy.compareAndSet(false,true))return;
  lastFrame=now;byte[] copy=data.clone();int version=screenVersion;boolean test=diagnostic;
  worker.execute(()->{try{
   if(version!=screenVersion)return;
   FaceEngine.Match match=engine.recognize(copy,width,height,rotation,mirror,0.8f,80f);
   if("NO_FACE".equals(match.status())){if(absentSince<0)absentSince=SystemClock.elapsedRealtime();if(SystemClock.elapsedRealtime()-absentSince>=800)armed=true;return;}
   absentSince=-1;
   if(test){ui.post(()->{if(version==screenVersion)status.setText("算法返回："+match.status()+"（不作为通行结果）");});return;}
   if(!loggedIn||!rosterReady||!armed)return;armed=false;
   JSONObject event=new JSONObject().put("eventId",UUID.randomUUID().toString()).put("deviceId",deviceId)
    .put("bindingId",session.getString("bindingId")).put("capturedAt",System.currentTimeMillis())
    .put("localStatus",match.status()).put("subjectId",match.localId()>0?store.subject(match.localId()):"");
   store.queue(event);currentEvent=event.getString("eventId");String id=currentEvent;long began=SystemClock.elapsedRealtime();
   String statusCode=match.status();boolean allow=false;
   if("MATCH".equals(statusCode)){
    try{JSONObject result=api.post("verify",event);
     if(!id.equals(result.getString("eventId")))throw new IllegalStateException("核验事件不匹配");
     statusCode=result.getString("status");allow=result.getBoolean("allowed") && result.getBoolean("recordStored");
     // Explicit durable acknowledgement only; HTTP 200 itself is insufficient.
     if(result.getBoolean("recordStored"))store.acknowledge(new JSONArray().put(id));
    }catch(Exception e){statusCode="NETWORK_ERROR";allow=false;}
   }
   boolean pass=EventPolicy.mayPresentPass(currentEvent,id,SystemClock.elapsedRealtime()-began,active&&version==screenVersion,allow&&"PASS".equals(statusCode));
   if("PASS".equals(statusCode)&&!pass)statusCode="NETWORK_ERROR";
   String resultText=messages.optString(statusCode,EventPolicy.defaultText(statusCode));boolean approved=pass;
   ui.post(()->{if(version==screenVersion&&active&&id.equals(currentEvent)){status.setText(resultText);status.setTextColor(approved?green:red);if(tts!=null)tts.speak(resultText,TextToSpeech.QUEUE_FLUSH,null,id);
    ui.postDelayed(()->{if(version==screenVersion&&id.equals(currentEvent)){status.setText("请刷脸核验");status.setTextColor(green);}},2500);
   }});
  }catch(Exception|LinkageError e){rosterReady=false;showError("设备异常，暂不可核验，请联系平台人员");}finally{frameBusy.set(false);}});
 }
 private void backgroundSync(){
  if(!loggedIn||sync==null)return;
  try{
   JSONObject config=api.post("config",new JSONObject());messages=config.getJSONObject("messages");
   sync.upload();rosterReady=false;boolean caughtUp=sync.syncPage();rosterReady=caughtUp;
   // Remaining pages are drained in subsequent cycles, with bounded work per iteration.
  }catch(Exception e){rosterReady=false;if(loggedIn)showError("同步未完成："+e.getMessage());}
 }
 @Override protected void onResume(){super.onResume();active=true; if(cameraPage && camera==null){boolean test=diagnostic;ui.post(()->showCamera(test));}}
 @Override protected void onPause(){active=false;screenVersion++;currentEvent="";if(tts!=null)tts.stop();stopCamera();super.onPause();}
 @Override protected void onDestroy(){loggedIn=false;worker.shutdownNow();if(tts!=null)tts.shutdown();super.onDestroy();}
 @Override public void onBackPressed(){if(loggedIn)showHome();else if(engine!=null&&engine.authorized())showLogin();else super.onBackPressed();}
}
