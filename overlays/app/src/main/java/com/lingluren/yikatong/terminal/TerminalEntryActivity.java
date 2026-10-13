package com.lingluren.yikatong.terminal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import android.text.method.PasswordTransformationMethod;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.example.authlibrary.BdFaceAuth;
import com.baidu.idl.main.facesdk.utils.PreferencesUtil;
import java.util.concurrent.atomic.AtomicBoolean;
import android.view.TextureView;
import android.app.DatePickerDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

/** Terminal entry over the original Demo authorization module, not a replacement SDK. */
public final class TerminalEntryActivity extends Activity implements TerminalRuntime.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private TerminalRuntime runtime;
    private TerminalCamera camera;
    private TextToSpeech speech;
    private boolean speechReady, accountPage;
    private boolean startAfterPermission;
    private Button startButton;
    private TextView resultView,diagnosticView;
    private TextureView cameraPreview;
    private int pageVersion;
    private String lastState="正在恢复设备状态";
    private String fingerprint() {
        String id=Settings.Secure.getString(getContentResolver(),Settings.Secure.ANDROID_ID);
        if(id==null || id.length()<8) throw new IllegalStateException("设备指纹读取失败，请联系平台人员");
        return getPackageName()+":"+id;
    }
    private boolean destroyed;
    private boolean baiduSettingsOpen;
    private TextView overviewView;
    private void openBaiduSettings(){
        if(busy.get()){Toast.makeText(this,"设备授权处理中，请稍候",Toast.LENGTH_SHORT).show();return;}
        if(baiduSettingsOpen)return;
        if(camera!=null)camera.stop();
        baiduSettingsOpen=true;
        status.setText("正在暂停核验并打开百度内置设置");
        runtime.beginSettings(error->{
            if(destroyed){runtime.endSettings();return;}
            if(error!=null){baiduSettingsOpen=false;status.setText(error);return;}
            try{
                Toast.makeText(this,"核验端使用RGB活体；修改后返回将重新同步，核验需手动开始",Toast.LENGTH_LONG).show();
                startActivityForResult(new Intent().setClassName(this,"com.example.settinglibrary.SettingActivity"),201);
            }catch(RuntimeException e){baiduSettingsOpen=false;runtime.endSettings();status.setText("百度设置无法打开，请检查完整Demo安装包");}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==201){baiduSettingsOpen=false;runtime.endSettings();if(runtime.hasSession())showAccount();}
    }
    private TextView status,communication;
    private Button activate;
    private BdFaceAuth auth;
    private volatile boolean authorized;
    private final Runnable timeout = () -> {
        if (busy.get()) {
            status.setText("验证时间较长，请检查网络；完成前请勿重复激活");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runtime=TerminalRuntime.get(this);runtime.attach(this);
        speech=new TextToSpeech(this,code->{if(code==TextToSpeech.SUCCESS){int result=speech.setLanguage(Locale.SIMPLIFIED_CHINESE);speechReady=result>=0;}});
        PreferencesUtil.initPrefs(getApplicationContext());
        auth = new BdFaceAuth();
        try { runtime.configureConnection(savedOrigin(),fingerprint()); }
        catch(IllegalArgumentException|IllegalStateException error) { showPlatformSettings();status.setText(error.getMessage());return; }
        showActivation();
        // Keep Demo cache keys so its model/recognition modules can reuse the same authorization.
        String saved = PreferencesUtil.getString("activate_online_key", "");
        if (!saved.isEmpty()) verify(saved);
        if(!getSharedPreferences("terminal_permissions",MODE_PRIVATE).getBoolean("explained",false))main.post(this::explainPermissions);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(12));
        if(border != 0) shape.setStroke(dp(1),border); return shape;
    }
    private LinearLayout page(String title) {
        if(camera!=null){camera.stop();camera=null;}else if(runtime!=null)runtime.pauseCamera();
        accountPage=false;
        pageVersion++;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(36),dp(24),dp(24));
        root.setBackgroundColor(Color.rgb(244,246,240));scroll.addView(root);setContentView(scroll);
        TextView brand=new TextView(this);brand.setText("一卡通景区刷脸核验端");brand.setTextSize(13);brand.setTextColor(Color.rgb(100,117,108));LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=new Button(this);settings.setText("设置");settings.setAllCaps(false);
        settings.setTextColor(Color.rgb(22,96,76));settings.setContentDescription("打开百度内置设置");
        top.addView(settings,new LinearLayout.LayoutParams(dp(80),dp(48)));root.addView(top);
        settings.setOnClickListener(v->openBaiduSettings());
        TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(26);heading.setTextColor(Color.rgb(22,96,76));heading.setPadding(0,dp(14),0,dp(12));root.addView(heading);
        communication=new TextView(this);communication.setText(runtime.communicationStatus());communication.setTextSize(14);communication.setTextColor(Color.rgb(70,90,79));communication.setPadding(0,0,0,dp(12));root.addView(communication);
        status=new TextView(this);status.setTextSize(14);status.setTextColor(Color.rgb(70,90,79));status.setPadding(0,0,0,dp(16));root.addView(status);
        Button permissions=new Button(this);permissions.setText("权限管理 / 相机授权");permissions.setAllCaps(false);
        root.addView(permissions);permissions.setOnClickListener(v->explainPermissions());
        return root;
    }
    private boolean cameraAllowed(){return Build.VERSION.SDK_INT<23||checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}
    private void explainPermissions(){
        if(destroyed||isFinishing())return;
        getSharedPreferences("terminal_permissions",MODE_PRIVATE).edit().putBoolean("explained",true).apply();
        new android.app.AlertDialog.Builder(this).setTitle("核验端权限说明")
            .setMessage("相机：用于现场人脸检测与活体核验，当前"+(cameraAllowed()?"已授权":"未授权")+"。\n\n人员照片由平台下发，在内存处理；特征与回执存于应用私有目录，不读取手机相册，也不需要相册或全部文件权限。\n\n拒绝相机权限仍可同步、查看统计和退出账号；授权不会自动开始核验。")
            .setPositiveButton(cameraAllowed()?"已了解":"授权相机",(d,w)->{if(!cameraAllowed())requestCamera(false);})
            .setNeutralButton("系统权限设置",(d,w)->openAppPermissions()).setNegativeButton("稍后",null).show();
    }
    private void openAppPermissions(){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())));}
        catch(RuntimeException e){Toast.makeText(this,"请在手机设置 → 应用管理 → 一卡通核验端中开启相机权限",Toast.LENGTH_LONG).show();}
    }
    private void requestCamera(boolean start){
        startAfterPermission=start;
        if(cameraAllowed()){if(start)startScanning();return;}
        boolean asked=getSharedPreferences("terminal_permissions",MODE_PRIVATE).getBoolean("cameraAsked",false);
        if(asked&&!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)){
            new android.app.AlertDialog.Builder(this).setTitle("相机权限尚未开启").setMessage("系统可能已停止弹出授权窗口，请到系统权限设置中开启相机。同步和统计仍可使用。")
                .setPositiveButton("打开设置",(d,w)->openAppPermissions()).setNegativeButton("取消",null).show();return;
        }
        getSharedPreferences("terminal_permissions",MODE_PRIVATE).edit().putBoolean("cameraAsked",true).apply();
        requestPermissions(new String[]{Manifest.permission.CAMERA},200);
    }
    private void styleInput(EditText field, String hint) {
        field.setHint(hint);field.setTextSize(16);field.setSingleLine();field.setPadding(dp(14),dp(12),dp(14),dp(12));
        field.setTextColor(Color.rgb(30,45,38));field.setHintTextColor(Color.rgb(115,128,120));
        field.setBackground(background(Color.WHITE,Color.rgb(209,219,211)));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.bottomMargin=dp(14);field.setLayoutParams(params);
    }
    private void styleButton(Button button) {
        button.setTextColor(Color.WHITE);button.setTextSize(17);button.setAllCaps(false);
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},background(Color.rgb(160,173,165),0));
        states.addState(new int[]{},background(Color.rgb(22,96,76),0));
        button.setBackground(states);button.setMinHeight(dp(52));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.topMargin=dp(10);button.setLayoutParams(params);
    }
    private String savedOrigin() {
        return getSharedPreferences("terminal_connection",MODE_PRIVATE).getString("platformOrigin","https://yktapi.qiliai.com");
    }
    private void connectionButton(LinearLayout root) {
        Button settings=new Button(this);settings.setText("平台连接设置");styleButton(settings);root.addView(settings);
        settings.setOnClickListener(v->{if(busy.get()){status.setText("设备授权处理中，请完成后再修改平台地址");return;}showPlatformSettings();});
    }
    private void showPlatformSettings() {
        LinearLayout root=page("平台连接设置");
        EditText address=new EditText(this);styleInput(address,"例如：https://您的接口域名");
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);address.setText(savedOrigin());root.addView(address);
        Button save=new Button(this);save.setText("保存平台地址");styleButton(save);root.addView(save);
        status.setText(runtime.hasSession()?"当前账号尚未退出。请返回设备状态，完成同步与回执后再修改平台地址。":"填写平台 API 的 HTTPS 地址，设备授权保持不变。");
        save.setEnabled(!runtime.hasSession());address.setEnabled(!runtime.hasSession());
        save.setOnClickListener(v->{
            if(runtime.hasSession()){status.setText("请先完成原账号同步退出");return;}
            try {
                String origin=TerminalEndpointPolicy.normalizeOrigin(address.getText().toString());
                if(!getSharedPreferences("terminal_connection",MODE_PRIVATE).edit().putString("platformOrigin",origin).commit())throw new IllegalStateException("平台地址保存失败，请重试");
                runtime.configureConnection(origin,fingerprint());
                if(authorized)showLogin();else showActivation();
            }catch(IllegalArgumentException|IllegalStateException error){status.setText(error.getMessage());}
        });
        Button back=new Button(this);back.setText("返回");styleButton(back);root.addView(back);back.setOnClickListener(v->{if(authorized){if(runtime.hasSession())showAccount();else showLogin();}else showActivation();});
    }
    private void showActivation() {
        LinearLayout root = page("设备激活");
        EditText code = new EditText(this);
        styleInput(code,"请输入授权码");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        root.addView(code);
        activate = new Button(this); activate.setText("激活"); styleButton(activate); root.addView(activate);
        activate.setOnClickListener(v -> {
            String value = code.getText().toString().trim();
            if (value.isEmpty()) { status.setText("请输入平台提供的授权码"); return; }
            verify(value);
        });
        status.setText("请向平台工作人员获取本机授权码");
        connectionButton(root);
    }
    private void verify(String code) {
        if (!busy.compareAndSet(false,true)) return;
        runtime.sdkAuthorization("CHECKING",code);
        activate.setEnabled(false); status.setText("正在验证设备授权，请稍候");
        main.postDelayed(timeout,30000);
        try {
            auth.initLicenseOnLine(getApplicationContext(),code,(error,message) -> main.post(() -> {
                if (destroyed) return;
                main.removeCallbacks(timeout); busy.set(false);
                if (error == 0) {
                    PreferencesUtil.putString("activate_online_key",code);
                    authorized = true;runtime.sdkAuthorization("AUTHORIZED",code);runtime.authorize();
                    if(runtime.hasSession())showAccount();else showLogin();
                } else {
                    runtime.sdkAuthorization("ERROR",code);activate.setEnabled(true); status.setText(explain(error));
                }
            }));
        } catch (RuntimeException | LinkageError failure) {
            main.removeCallbacks(timeout); busy.set(false); activate.setEnabled(true);
            runtime.sdkAuthorization("ERROR",code);status.setText("授权组件异常，请联系平台人员");
        }
    }
    private String explain(int code) {
        switch (code) {
            case 290004: case 290009: return "授权码已被绑定，请联系平台人员（"+code+"）";
            case 11: case 290006: return "授权已到期，请联系平台人员（"+code+"）";
            case 13: case 1009: case 1011: return "网络连接异常，请重试（"+code+"）";
            default: return "激活失败，请核对授权码（"+code+"）";
        }
    }
    private void showLogin() {
        if (!authorized) { showActivation(); return; }
        LinearLayout root=page("景区账号登录");int version=pageVersion;
        EditText user=new EditText(this);styleInput(user,"请输入后台开通的景区账号");user.setInputType(InputType.TYPE_CLASS_TEXT);root.addView(user);
        EditText password=new EditText(this);styleInput(password,"请输入密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());root.addView(password);
        CheckBox visible=new CheckBox(this);visible.setText("显示密码");visible.setTextColor(Color.rgb(70,90,79));root.addView(visible);
        visible.setOnCheckedChangeListener((button,checked)->{password.setTransformationMethod(checked?null:PasswordTransformationMethod.getInstance());password.setSelection(password.length());});
        Button login=new Button(this);login.setText("登录");styleButton(login);root.addView(login);
        status.setText("设备已激活，请登录景区账号");
        connectionButton(root);
        login.setOnClickListener(view->{
            final String name=user.getText().toString().trim(),secret=password.getText().toString();
            if(name.isEmpty()||secret.isEmpty()){status.setText("请输入账号和密码");return;}
            login.setEnabled(false);status.setText("正在验证账号");
            runtime.login(savedOrigin(),fingerprint(),name,secret,error->{
                if(destroyed||version!=pageVersion)return;
                if(error!=null){login.setEnabled(true);status.setText(error);return;}
                password.setText("");showAccount();
            });
        });
        if(runtime.hasSession()){
            Button back=new Button(this);back.setText("返回原账号同步");styleButton(back);root.addView(back);back.setOnClickListener(v->showAccount());
            status.setText("原会话尚未退出，只能重新登录原账号；切换账号请返回完成同步退出。");
        }
    }
    private void showAccount() {
        LinearLayout root=page("设备状态");accountPage=true;status.setText(lastState);
        overviewView=new TextView(this);overviewView.setText(runtime.overview());overviewView.setTextSize(15);overviewView.setTextColor(Color.rgb(50,65,55));overviewView.setPadding(dp(12),dp(12),dp(12),dp(12));overviewView.setBackground(background(Color.WHITE,Color.rgb(209,219,211)));root.addView(overviewView);
        TextureView preview=new TextureView(this);preview.setVisibility(android.view.View.GONE);root.addView(preview,new LinearLayout.LayoutParams(-1,dp(180)));
        cameraPreview=preview;
        resultView=new TextView(this);resultView.setText(runtime.ready()?"同步完成，请开始核验":"等待平台信息与人员同步");resultView.setTextSize(23);resultView.setPadding(0,dp(16),0,dp(16));root.addView(resultView);
        camera=new TerminalCamera(this,runtime,preview,text->{status.setText(text);startButton.setText("开始核验");});
        startButton=new Button(this);startButton.setText(runtime.ready()?"开始核验":"等待同步完成");styleButton(startButton);root.addView(startButton);startButton.setEnabled(runtime.ready());
        startButton.setOnClickListener(v->{
            if("暂停核验".contentEquals(startButton.getText())){camera.stop();cameraPreview.setVisibility(android.view.View.GONE);startButton.setText(runtime.ready()?"开始核验":"等待同步完成");resultView.setText("已暂停核验");return;}
            if(!cameraAllowed()){requestCamera(true);return;}
            startScanning();
        });
        Button next=new Button(this);next.setText("下一位 / 重新核验");styleButton(next);root.addView(next);
        next.setOnClickListener(v->{if(!"暂停核验".contentEquals(startButton.getText())){status.setText("请先开始核验");return;}runtime.nextAttempt();});
        diagnosticView=new TextView(this);diagnosticView.setText("识别诊断：等待相机画面");diagnosticView.setTextSize(13);diagnosticView.setTextColor(Color.rgb(70,90,79));diagnosticView.setPadding(0,dp(12),0,dp(12));root.addView(diagnosticView);
        Button records=new Button(this);records.setText("本机今日记录 / 维护状态");styleButton(records);root.addView(records);records.setOnClickListener(v->showLocalRecords());
        Button stats=new Button(this);stats.setText("景区入园统计");styleButton(stats);root.addView(stats);stats.setOnClickListener(v->showStatistics());
        Button sync=new Button(this);sync.setText("立即同步 / 重试回执");styleButton(sync);root.addView(sync);sync.setOnClickListener(v->{status.setText("同步重试已安排，请查看处理进度");runtime.retry();});
        Button loginAgain=new Button(this);loginAgain.setText("重新验证原账号");styleButton(loginAgain);root.addView(loginAgain);loginAgain.setOnClickListener(v->showLogin());
        Button logout=new Button(this);logout.setText("同步完成后退出账号");styleButton(logout);root.addView(logout);
        logout.setOnClickListener(v->{camera.stop();logout.setEnabled(false);status.setText("已暂停核验，正在排空记录、人员任务和回执");
            runtime.exit(error->{if(destroyed)return;if(error==null){if(!runtime.hasSession())showLogin();}else {if(accountPage){status.setText(error);logout.setEnabled(true);}}});
        });
        connectionButton(root);runtime.retry();
    }
    private void startScanning(){if(camera==null||!runtime.startCamera()){status.setText("请等待设备就绪后开始核验");return;}cameraPreview.setVisibility(android.view.View.VISIBLE);if(!camera.start())return;startButton.setText("暂停核验");resultView.setText("请正对摄像头，每次核验后移开");}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request!=200)return;
        boolean resume=startAfterPermission;startAfterPermission=false;
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"相机已授权",Toast.LENGTH_SHORT).show();if(resume&&accountPage)startScanning();
        }else{status.setText("相机权限未授权；可继续同步和统计，需要核验时请打开权限管理");}
    }

    private void showLocalRecords(){
        LinearLayout root=page("本机记录与维护");int version=pageVersion;
        TextView summary=new TextView(this);summary.setTextSize(16);summary.setTextColor(Color.rgb(45,65,55));root.addView(summary);
        Button refresh=new Button(this);refresh.setText("刷新记录");styleButton(refresh);root.addView(refresh);
        Button retry=new Button(this);retry.setText("重试同步与回执");styleButton(retry);root.addView(retry);retry.setOnClickListener(v->runtime.retry());
        TextView rows=new TextView(this);rows.setTextSize(15);rows.setTextColor(Color.rgb(45,65,55));rows.setPadding(0,dp(16),0,dp(16));root.addView(rows);
        refresh.setOnClickListener(v->{refresh.setEnabled(false);runtime.localRecords((data,error)->{
            if(destroyed||pageVersion!=version)return;refresh.setEnabled(true);
            if(error!=null){status.setText(error);return;}
            status.setText("今日按北京时间统计，仅当前登录会话、本机记录；景区全量统计请返回查看景区入园统计。通过数为平台记录结果，不代表重复回执再次放行。");
            summary.setText("今日记录："+data.optLong("today")+" · 通过："+data.optLong("passed")+" · 拒绝："+data.optLong("denied")+" · 待处理："+data.optLong("awaiting")
                +"\n未确认回执："+data.optLong("pending")+" · 本地特征："+data.optLong("localFaces")
                +"\n人员库："+("SHARED".equals(data.optString("libraryMode"))?"同平台共享，换号保留":"旧版会话库")
                +"\nSDK："+(data.optBoolean("sdkReady")?"就绪":"未就绪")+" · APP："+data.optString("appVersion"));
            StringBuilder text=new StringBuilder("最近20条当前会话记录\n\n");JSONArray items=data.optJSONArray("items");
            if(items!=null)for(int i=0;i<items.length();i++){JSONObject row=items.optJSONObject(i);if(row!=null)text.append(row.optString("time")).append(" · ").append(row.optBoolean("confirmed")?"已确认":"待确认").append("\n").append(row.optString("text")).append("\n\n");}
            rows.setText(text);
        });});
        Button back=new Button(this);back.setText("返回设备状态");styleButton(back);root.addView(back);back.setOnClickListener(v->showAccount());refresh.performClick();
    }
    private void showStatistics(){
        LinearLayout root=page("景区入园统计");int version=pageVersion;
        Calendar today=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);fmt.setTimeZone(today.getTimeZone());
        Button from=new Button(this),to=new Button(this);from.setText(fmt.format(today.getTime()));to.setText(fmt.format(today.getTime()));styleButton(from);styleButton(to);root.addView(from);root.addView(to);
        android.view.View.OnClickListener date=v->{Button button=(Button)v;String[] parts=button.getText().toString().split("-");DatePickerDialog dialog=new DatePickerDialog(this,(picker,y,m,d)->button.setText(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)),Integer.parseInt(parts[0]),Integer.parseInt(parts[1])-1,Integer.parseInt(parts[2]));dialog.getDatePicker().setMaxDate(System.currentTimeMillis());dialog.show();};
        from.setOnClickListener(date);to.setOnClickListener(date);
        Button query=new Button(this);query.setText("查询人数");styleButton(query);root.addView(query);TextView results=new TextView(this);results.setTextSize(17);results.setPadding(0,dp(20),0,dp(20));root.addView(results);
        query.setOnClickListener(v->{query.setEnabled(false);runtime.statistics(from.getText().toString(),to.getText().toString(),(data,error)->{if(destroyed||version!=pageVersion)return;query.setEnabled(true);if(error!=null){status.setText(error);return;}status.setText(data.optString("countingDescription"));StringBuilder text=new StringBuilder("每日去重人数合计："+data.optLong("totalDailyPeople")+" 人\n\n");JSONArray items=data.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++){JSONObject row=items.optJSONObject(i);if(row!=null)text.append(row.optString("date")).append(" · ").append(row.optString("scenicName")).append("：").append(row.optLong("people")).append(" 人\n");}results.setText(text);});});
        Button back=new Button(this);back.setText("返回设备状态");styleButton(back);root.addView(back);back.setOnClickListener(v->showAccount());query.performClick();
    }
    @Override public void status(String text,boolean ready,boolean hasSession){
        if(destroyed)return;lastState=text;if(!accountPage)return;status.setText(text);if(overviewView!=null)overviewView.setText(runtime.overview());
        if(startButton!=null){
            boolean scanning="暂停核验".contentEquals(startButton.getText());
            if(!ready&&scanning){if(camera!=null)camera.stop();if(cameraPreview!=null)cameraPreview.setVisibility(android.view.View.GONE);scanning=false;}
            if(!scanning){startButton.setText(ready?"开始核验":"等待同步完成");if(resultView!=null)resultView.setText(ready?"同步完成，请开始核验":"等待平台信息与人员同步");}
            startButton.setEnabled(ready);
        }if(!hasSession&&authorized)showLogin();
    }
    @Override public void diagnostics(String text){if(!destroyed&&accountPage&&diagnosticView!=null)diagnosticView.setText(text);}
    @Override public void communication(String text){if(!destroyed&&communication!=null)communication.setText(text);}
    @Override public void result(String text,String voice,boolean open){
        if(destroyed||!accountPage||resultView==null)return;resultView.setText(text);resultView.setTextColor(open?Color.rgb(22,120,60):Color.rgb(180,45,30));
        if(speechReady&&!voice.isEmpty())speech.speak(voice,TextToSpeech.QUEUE_FLUSH,null,"terminal-result");
    }
    @Override protected void onPause() {
        if(camera!=null)camera.stop();runtime.pauseCamera();if(startButton!=null)startButton.setText("开始核验");super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed=true;pageVersion++;main.removeCallbacks(timeout);runtime.detach(this);if(camera!=null)camera.stop();if(speech!=null){speech.stop();speech.shutdown();}super.onDestroy();
    }
}

