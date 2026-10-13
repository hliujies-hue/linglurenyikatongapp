package com.lingluren.yikatong.terminal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
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
    private Button startButton;
    private TextView resultView;
    private int pageVersion;
    private String lastState="正在恢复设备状态";
    private String fingerprint() {
        String id=Settings.Secure.getString(getContentResolver(),Settings.Secure.ANDROID_ID);
        if(id==null || id.length()<8) throw new IllegalStateException("设备指纹读取失败，请联系平台人员");
        return getPackageName()+":"+id;
    }
    private boolean destroyed;
    private boolean settingsHeld, settingsOpened;
    private float settingsDownX, settingsDownY;
    private final Runnable openBaiduSettings = () -> {
        if (!settingsHeld || destroyed || isFinishing()) return;
        settingsOpened = true; settingsHeld = false;
        try {
            if(camera!=null)camera.stop();runtime.pauseCamera();
            startActivity(new Intent().setClassName(this, "com.example.settinglibrary.SettingActivity"));
        } catch (RuntimeException error) {
            Toast.makeText(this, "高级算法配置无法打开，请联系平台人员", Toast.LENGTH_LONG).show();
        }
    };
    private void cancelSettingsHold() {
        settingsHeld = false; main.removeCallbacks(openBaiduSettings);
    }
    private void settingsTouch(Button button) {
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelSettingsHold(); settingsOpened = false; settingsHeld = true;
                    settingsDownX = event.getX(); settingsDownY = event.getY();
                    view.setPressed(true); view.getParent().requestDisallowInterceptTouchEvent(true);
                    main.postDelayed(openBaiduSettings, 10000L); return true;
                case MotionEvent.ACTION_MOVE:
                    int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                    if (Math.abs(event.getX()-settingsDownX)>slop || Math.abs(event.getY()-settingsDownY)>slop) {
                        cancelSettingsHold(); view.setPressed(false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean click = settingsHeld && !settingsOpened;
                    cancelSettingsHold(); view.setPressed(false);
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    if (click) view.performClick(); return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelSettingsHold(); view.setPressed(false);
                    view.getParent().requestDisallowInterceptTouchEvent(false); return true;
                default: return true;
            }
        });
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
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(12));
        if(border != 0) shape.setStroke(dp(1),border); return shape;
    }
    private LinearLayout page(String title) {
        cancelSettingsHold();
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
        settings.setTextColor(Color.rgb(22,96,76));settings.setContentDescription("设置，长按十秒打开高级算法配置");
        top.addView(settings,new LinearLayout.LayoutParams(dp(80),dp(48)));root.addView(top);
        settings.setOnClickListener(v->{if(busy.get()){Toast.makeText(this,"设备授权处理中，请稍候",Toast.LENGTH_SHORT).show();return;}showPlatformSettings();});
        settingsTouch(settings);
        TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(26);heading.setTextColor(Color.rgb(22,96,76));heading.setPadding(0,dp(14),0,dp(12));root.addView(heading);
        communication=new TextView(this);communication.setText(runtime.communicationStatus());communication.setTextSize(14);communication.setPadding(0,0,0,dp(12));root.addView(communication);
        status=new TextView(this);status.setTextSize(14);status.setTextColor(Color.rgb(70,90,79));status.setPadding(0,0,0,dp(16));root.addView(status);
        return root;
    }
    private void styleInput(EditText field, String hint) {
        field.setHint(hint);field.setTextSize(16);field.setSingleLine();field.setPadding(dp(14),dp(12),dp(14),dp(12));
        field.setTextColor(Color.rgb(30,45,38));field.setHintTextColor(Color.rgb(115,128,120));
        field.setBackground(background(Color.WHITE,Color.rgb(209,219,211)));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.bottomMargin=dp(14);field.setLayoutParams(params);
    }
    private void styleButton(Button button) {
        button.setTextColor(Color.WHITE);button.setTextSize(17);button.setAllCaps(false);
        button.setBackground(background(Color.rgb(22,96,76),0));button.setMinHeight(dp(52));
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
        TextureView preview=new TextureView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,dp(280)));
        resultView=new TextView(this);resultView.setText("请先开始核验");resultView.setTextSize(23);resultView.setPadding(0,dp(16),0,dp(16));root.addView(resultView);
        camera=new TerminalCamera(this,runtime,preview,text->{status.setText(text);startButton.setText("开始核验");});
        startButton=new Button(this);startButton.setText("开始核验");styleButton(startButton);root.addView(startButton);startButton.setEnabled(runtime.ready());
        startButton.setOnClickListener(v->{
            if("暂停核验".contentEquals(startButton.getText())){camera.stop();startButton.setText("开始核验");resultView.setText("已暂停核验");return;}
            if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},200);return;}
            startScanning();
        });
        Button stats=new Button(this);stats.setText("景区入园统计");styleButton(stats);root.addView(stats);stats.setOnClickListener(v->showStatistics());
        Button sync=new Button(this);sync.setText("立即同步 / 重试回执");styleButton(sync);root.addView(sync);sync.setOnClickListener(v->runtime.retry());
        Button loginAgain=new Button(this);loginAgain.setText("重新验证原账号");styleButton(loginAgain);root.addView(loginAgain);loginAgain.setOnClickListener(v->showLogin());
        Button logout=new Button(this);logout.setText("同步完成后退出账号");styleButton(logout);root.addView(logout);
        logout.setOnClickListener(v->{camera.stop();logout.setEnabled(false);status.setText("已暂停核验，正在排空记录、人员任务和回执");
            runtime.exit(error->{if(destroyed)return;if(error==null){if(!runtime.hasSession())showLogin();}else {if(accountPage){status.setText(error);logout.setEnabled(true);}}});
        });
        connectionButton(root);runtime.retry();
    }
    private void startScanning(){if(camera==null||!runtime.startCamera()){status.setText("请等待设备就绪后开始核验");return;}if(!camera.start())return;startButton.setText("暂停核验");resultView.setText("请正对摄像头，每次核验后移开");}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==200&&accountPage){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startScanning();else status.setText("未授予相机权限，请在手机设置中允许后重试");}}
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
        if(destroyed)return;lastState=text;if(!accountPage)return;status.setText(text);if(startButton!=null)startButton.setEnabled(ready||"暂停核验".contentEquals(startButton.getText()));if(!hasSession&&authorized)showLogin();
    }
    @Override public void communication(String text){if(!destroyed&&communication!=null)communication.setText(text);}
    @Override public void result(String text,String voice,boolean open){
        if(destroyed||!accountPage||resultView==null)return;resultView.setText(text);resultView.setTextColor(open?Color.rgb(22,120,60):Color.rgb(180,45,30));
        if(speechReady&&!voice.isEmpty())speech.speak(voice,TextToSpeech.QUEUE_FLUSH,null,"terminal-result");
    }
    @Override protected void onPause() {
        cancelSettingsHold();if(camera!=null)camera.stop();runtime.pauseCamera();if(startButton!=null)startButton.setText("开始核验");super.onPause();
    }
    @Override protected void onDestroy() {
        cancelSettingsHold();
        destroyed=true;pageVersion++;main.removeCallbacks(timeout);runtime.detach(this);if(camera!=null)camera.stop();if(speech!=null){speech.stop();speech.shutdown();}super.onDestroy();
    }
}

