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

/** Terminal entry over the original Demo authorization module, not a replacement SDK. */
public final class TerminalEntryActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private volatile PlatformClient platform;
    private int pageVersion;
    private long bindingGeneration;
    private boolean communicating;
    private final Runnable heartbeatTick = () -> sendHeartbeat();
    private String fingerprint() {
        String id=Settings.Secure.getString(getContentResolver(),Settings.Secure.ANDROID_ID);
        if(id==null || id.length()<8) throw new IllegalStateException("设备指纹读取失败，请联系平台人员");
        return getPackageName()+":"+id;
    }
    private boolean settingsHeld, settingsOpened;
    private float settingsDownX, settingsDownY;
    private final Runnable openBaiduSettings = () -> {
        if (!settingsHeld || destroyed || isFinishing()) return;
        settingsOpened = true; settingsHeld = false;
        try {
            startActivity(new Intent().setClassName(this, "com.example.settinglibrary.SettingActivity"));
        } catch (RuntimeException error) {
            Toast.makeText(this, "百度配置页无法打开，请检查完整 Demo 模块", Toast.LENGTH_LONG).show();
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
    private TextView status;
    private Button activate;
    private BdFaceAuth auth;
    private volatile boolean authorized;
    private boolean destroyed;
    private final Runnable timeout = () -> {
        if (busy.get()) {
            status.setText("验证时间较长，请检查网络；完成前请勿重复激活");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PreferencesUtil.initPrefs(getApplicationContext());
        auth = new BdFaceAuth();
        try { platform=new PlatformClient(savedOrigin()); }
        catch(IllegalArgumentException error) { showPlatformSettings();return; }
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
        pageVersion++;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(36),dp(24),dp(24));
        root.setBackgroundColor(Color.rgb(244,246,240));scroll.addView(root);setContentView(scroll);
        TextView brand=new TextView(this);brand.setText("一卡通景区刷脸核验端");brand.setTextSize(13);brand.setTextColor(Color.rgb(100,117,108));LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=new Button(this);settings.setText("设置");settings.setAllCaps(false);
        settings.setTextColor(Color.rgb(22,96,76));settings.setContentDescription("设置，长按十秒打开百度配置");
        top.addView(settings,new LinearLayout.LayoutParams(dp(80),dp(48)));root.addView(top);
        settings.setOnClickListener(v->{if(busy.get()){Toast.makeText(this,"设备授权处理中，请稍候",Toast.LENGTH_SHORT).show();return;}showPlatformSettings();});
        settingsTouch(settings);
        TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(26);heading.setTextColor(Color.rgb(22,96,76));heading.setPadding(0,dp(14),0,dp(12));root.addView(heading);
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
        communicating=false;main.removeCallbacks(heartbeatTick);
        LinearLayout root=page("平台连接设置");
        EditText address=new EditText(this);styleInput(address,"例如：https://您的接口域名");
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);address.setText(savedOrigin());root.addView(address);
        Button save=new Button(this);save.setText("保存平台地址");styleButton(save);root.addView(save);
        status.setText("填写平台 API 的 HTTPS 地址，修改后需要重新登录；设备授权保留。");
        save.setOnClickListener(v->{
            final String origin;
            try { origin=TerminalEndpointPolicy.normalizeOrigin(address.getText().toString()); }
            catch(IllegalArgumentException error) {status.setText(error.getMessage());return;}
            final PlatformClient next=new PlatformClient(origin),previous=platform;
            final int version=pageVersion;save.setEnabled(false);status.setText("正在保存并退出旧平台会话");
            network.execute(()->{if(previous!=null)previous.logout();main.post(()->{
                if(destroyed||version!=pageVersion)return;
                if(!getSharedPreferences("terminal_connection",MODE_PRIVATE).edit().putString("platformOrigin",origin).commit()) {
                    save.setEnabled(true);status.setText("平台地址保存失败，请重试");return;
                }
                platform=next;
                if(authorized)showLogin();else{showActivation();String license=PreferencesUtil.getString("activate_online_key","");if(!license.isEmpty())verify(license);}
            });});
        });
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
        activate.setEnabled(false); status.setText("正在验证设备授权，请稍候");
        main.postDelayed(timeout,30000);
        try {
            auth.initLicenseOnLine(getApplicationContext(),code,(error,message) -> main.post(() -> {
                if (destroyed) return;
                main.removeCallbacks(timeout); busy.set(false);
                if (error == 0) {
                    PreferencesUtil.putString("activate_online_key",code);
                    authorized = true;
                    showLogin();
                } else {
                    activate.setEnabled(true); status.setText(explain(error));
                }
            }));
        } catch (RuntimeException | LinkageError failure) {
            main.removeCallbacks(timeout); busy.set(false); activate.setEnabled(true);
            status.setText("授权组件异常，请联系平台人员");
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
            network.execute(()->{try{
                JSONObject result=platform.request("/api/terminal/login",new JSONObject().put("username",name).put("password",secret));
                platform.token(result.getString("accessToken"));
                JSONObject binding=platform.request("/api/terminal/bind",new JSONObject()
                    .put("fingerprint",fingerprint())
                    .put("license",PreferencesUtil.getString("activate_online_key",""))
                    .put("appVersion","0.2.0-binding"));
                main.post(()->{if(destroyed||version!=pageVersion)return;password.setText("");showAccount(result,binding);});
            }catch(Exception error){platform.logout();main.post(()->{if(destroyed||version!=pageVersion)return;login.setEnabled(true);status.setText(error.getMessage());});}});
        });
    }
    private void showAccount(JSONObject info,JSONObject binding) {
        LinearLayout root=page("设备状态");
        bindingGeneration=binding.optLong("generation");
        communicating=true;
        status.setText("设备："+binding.optString("deviceName")+"\n账号："+info.optString("username")+
            "\n设备已激活并绑定，正在连接平台\n人员同步与正式刷脸核验待开通");
        Button logout=new Button(this);logout.setText("退出账号");styleButton(logout);root.addView(logout);
        logout.setOnClickListener(v->{communicating=false;main.removeCallbacks(heartbeatTick);logout.setEnabled(false);
            network.execute(()->{platform.logout();main.post(()->{if(!destroyed)showLogin();});});});
        connectionButton(root);
        main.removeCallbacks(heartbeatTick);main.post(heartbeatTick);
    }
    private void sendHeartbeat() {
        if(destroyed || !communicating) return;
        final int version=pageVersion;
        final long generation=bindingGeneration;
        network.execute(()->{
            String text;
            try {
                JSONObject reply=platform.request("/api/terminal/heartbeat",new JSONObject()
                    .put("fingerprint",fingerprint()).put("generation",generation)
                    .put("appVersion","0.2.0-binding").put("sdkStatus",authorized?"AUTHORIZED":"UNAUTHORIZED"));
                text="设备已绑定 · 平台通信正常\n算法授权："+(authorized?"已授权":"未授权")+
                    "\n平台时间："+reply.optString("serverTime")+"\n人员同步与正式刷脸核验待开通";
            } catch(Exception error) {
                text="平台通信异常："+error.getMessage()+"\n将在30秒后重试；暂不可扫脸通行";
            }
            final String value=text;
            main.post(()->{if(destroyed || !communicating || version!=pageVersion)return;
                status.setText(value);main.postDelayed(heartbeatTick,30000);});
        });
    }
    @Override protected void onPause() {
        cancelSettingsHold(); super.onPause();
    }
    @Override protected void onDestroy() {
        cancelSettingsHold();
        destroyed = true; communicating=false; pageVersion++; main.removeCallbacks(heartbeatTick); main.removeCallbacks(timeout); network.shutdownNow(); super.onDestroy();
    }
}
