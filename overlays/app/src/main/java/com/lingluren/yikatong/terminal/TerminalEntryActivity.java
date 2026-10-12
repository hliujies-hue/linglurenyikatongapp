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
import com.example.authlibrary.BdFaceAuth;
import com.baidu.idl.main.facesdk.utils.PreferencesUtil;
import java.util.concurrent.atomic.AtomicBoolean;

/** Terminal entry over the original Demo authorization module, not a replacement SDK. */
public final class TerminalEntryActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
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
    private LinearLayout page(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 64, 36, 32);
        root.setBackgroundColor(Color.rgb(244, 246, 240));
        TextView heading = new TextView(this);
        heading.setText(title); heading.setTextSize(26); heading.setTextColor(Color.rgb(22,96,76));
        root.addView(heading);
        status = new TextView(this); status.setTextSize(16); status.setPadding(0,24,0,24);
        root.addView(status); setContentView(root); return root;
    }
    private void showActivation() {
        LinearLayout root = page("设备激活");
        EditText code = new EditText(this);
        code.setHint("请输入授权码"); code.setSingleLine();
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setTextColor(Color.rgb(30,45,38));
        root.addView(code);
        activate = new Button(this); activate.setText("激活"); root.addView(activate);
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
        LinearLayout root = page("景区账号登录");
        EditText user = new EditText(this); user.setHint("请输入景区账号"); user.setSingleLine(); root.addView(user);
        EditText password = new EditText(this); password.setHint("请输入密码"); password.setSingleLine();
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(password);
        Button login = new Button(this); login.setText("登录"); root.addView(login);
        login.setOnClickListener(v -> status.setText("平台终端接口尚未接通，当前版本不能登录或通行"));
        status.setText("设备已激活。开发验证版：平台登录与扫脸联动正在开发。");
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacks(timeout); super.onDestroy();
    }
}
