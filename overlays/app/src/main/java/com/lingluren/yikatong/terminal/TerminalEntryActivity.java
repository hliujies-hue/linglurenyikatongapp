package com.lingluren.yikatong.terminal;

import android.app.Activity;
import android.content.Context;
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
    private final PlatformClient platform = new PlatformClient("https://ykt.qiliai.com");
    private int pageVersion;
    private int captchaVersion;
    private String challenge = "", proof = "";
    private long challengeExpires, sliderStarted;
    private final ArrayList<Integer> trail = new ArrayList<>();
    private SeekBar slider;
    private Button loginButton;
    private boolean verifyingSlider;
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
        pageVersion++;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(36),dp(24),dp(24));
        root.setBackgroundColor(Color.rgb(244,246,240));scroll.addView(root);setContentView(scroll);
        TextView brand=new TextView(this);brand.setText("一卡通景区刷脸核验端");brand.setTextSize(13);brand.setTextColor(Color.rgb(100,117,108));root.addView(brand);
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
        proof="";challenge="";verifyingSlider=false;
        EditText user=new EditText(this);styleInput(user,"请输入后台开通的景区账号");user.setInputType(InputType.TYPE_CLASS_TEXT);root.addView(user);
        EditText password=new EditText(this);styleInput(password,"请输入密码");
        // Ordinary keyboard with masked rendering avoids invoking vendor secure-keyboard input mode.
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());root.addView(password);
        CheckBox visible=new CheckBox(this);visible.setText("显示密码");root.addView(visible);
        visible.setOnCheckedChangeListener((button,checked)->{password.setTransformationMethod(checked?null:PasswordTransformationMethod.getInstance());password.setSelection(password.length());});
        TextView hint=new TextView(this);hint.setText("请拖动滑块至最右侧完成验证");hint.setTextColor(Color.rgb(70,90,79));hint.setPadding(0,dp(12),0,dp(8));root.addView(hint);
        slider=new SeekBar(this);slider.setMax(300);slider.setEnabled(false);root.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { sliderStarted=SystemClock.elapsedRealtime();trail.clear();trail.add(bar.getProgress()); }
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) { if(fromUser && trail.size()<160)trail.add(progress); }
            public void onStopTrackingTouch(SeekBar bar) {
                if(bar.getProgress()<290 || verifyingSlider) { bar.setProgress(0);return; }
                if(System.currentTimeMillis()>=challengeExpires){refreshCaptcha(version);return;}
                verifyingSlider=true;bar.setEnabled(false);
                final long duration=SystemClock.elapsedRealtime()-sliderStarted;
                final JSONArray points=new JSONArray(trail);final String id=challenge;final int generation=captchaVersion;
                network.execute(()->{try{
                    JSONObject response=platform.request("/api/auth/captcha/verify",new JSONObject().put("challengeId",id).put("durationMs",duration).put("trail",points));
                    main.post(()->{if(destroyed||version!=pageVersion||generation!=captchaVersion)return;proof=response.optString("captchaProof");verifyingSlider=false;
                        if(proof.isEmpty()){refreshCaptcha(version);return;}hint.setText("验证通过");loginButton.setEnabled(true);status.setText("设备已激活，请登录景区账号");});
                }catch(Exception error){main.post(()->{if(destroyed||version!=pageVersion)return;status.setText(error.getMessage());refreshCaptcha(version);});}});
            }
        });
        loginButton=new Button(this);loginButton.setText("登录");styleButton(loginButton);loginButton.setEnabled(false);root.addView(loginButton);
        loginButton.setOnClickListener(view->{
            final String name=user.getText().toString().trim(),secret=password.getText().toString(),captcha=proof;
            if(name.isEmpty()||secret.isEmpty()){status.setText("请输入账号和密码");return;}
            if(captcha.isEmpty()||System.currentTimeMillis()>=challengeExpires){refreshCaptcha(version);return;}
            proof="";loginButton.setEnabled(false);status.setText("正在验证账号");
            network.execute(()->{try{
                JSONObject result=platform.request("/api/auth/login",new JSONObject().put("username",name).put("password",secret).put("captchaProof",captcha));
                String token=result.getString("accessToken");platform.token(token);
                JSONObject info=platform.request("/api/user/info",null);
                // Permissions are checked by the platform; no arbitrary scenic selection or local quota decisions.
                platform.request("/api/admin/scenic-console/options",null);
                main.post(()->{if(destroyed||version!=pageVersion)return;password.setText("");showAccount(info);});
            }catch(Exception error){platform.logout();main.post(()->{if(destroyed||version!=pageVersion)return;status.setText(error.getMessage());refreshCaptcha(version);});}});
        });
        Button refresh=new Button(this);refresh.setText("刷新验证");root.addView(refresh);refresh.setOnClickListener(v->refreshCaptcha(version));
        refreshCaptcha(version);
    }
    private void refreshCaptcha(int version) {
        if(destroyed||version!=pageVersion)return;
        final int generation=++captchaVersion;
        proof="";challenge="";verifyingSlider=false;slider.setProgress(0);slider.setEnabled(false);loginButton.setEnabled(false);
        network.execute(()->{try{
            JSONObject response=platform.request("/api/auth/captcha",null);
            String id=response.getString("challengeId");
            main.post(()->{if(destroyed||version!=pageVersion||generation!=captchaVersion)return;challenge=id;challengeExpires=System.currentTimeMillis()+100000;slider.setEnabled(true);});
        }catch(Exception error){main.post(()->{if(!destroyed&&version==pageVersion)status.setText(error.getMessage());});}});
    }
    private void showAccount(JSONObject info) {
        LinearLayout root=page("账号验证成功");status.setText("当前账号："+info.optString("username")+"\n设备接入与人员同步尚未开通，暂不可扫脸通行。");
        Button logout=new Button(this);logout.setText("退出账号");styleButton(logout);root.addView(logout);
        logout.setOnClickListener(v->{logout.setEnabled(false);network.execute(()->{platform.logout();main.post(()->{if(!destroyed)showLogin();});});});
    }
    @Override protected void onDestroy() {
        destroyed = true; pageVersion++; main.removeCallbacks(timeout); network.shutdownNow(); super.onDestroy();
    }
}
