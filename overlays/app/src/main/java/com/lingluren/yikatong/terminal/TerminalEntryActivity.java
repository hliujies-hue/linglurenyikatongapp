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
    private final ExecutorService adminWorker=Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private TerminalRuntime runtime;
    private TerminalCamera camera;
    private TextToSpeech speech;
    private boolean speechReady, accountPage, scanPage, resultPage;

    private final int BLUE=0xff187cff, INK=0xff102139, MUTED=0xff788aa7;
    private android.graphics.Bitmap landscape;
    private TextView loadText, syncDetail, homeAccount;
    private TextView[] homeChips;
    private TerminalProgressView loadRing;
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
    private boolean destroyed, foreground;
    private boolean baiduSettingsOpen;

    private void openBaiduSettings(){
        if(baiduSettingsOpen||busy.get()){Toast.makeText(this,"正在处理，请稍候",Toast.LENGTH_SHORT).show();return;}
        if(camera!=null)camera.stop();runtime.pauseCamera();
        final EditText password=new EditText(this);password.setSingleLine();password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setHint("请输入管理员设置密码");
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(12),dp(24),0);box.addView(password);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("管理员设置").setMessage("由后台独立配置密码，验证后进入百度原生参数设置。需要联网，每次进入重新验证。").setView(box).setPositiveButton("验证并进入",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            final String secret=password.getText().toString();if(secret.isEmpty()){password.setError("请输入密码");return;}
            dialog.getButton(-1).setEnabled(false);dialog.getButton(-1).setText("正在验证…");
            adminWorker.execute(()->{String error=null;try{
                JSONObject reply=new PlatformClient(savedOrigin()).request("/api/terminal/admin-settings/unlock",new JSONObject().put("password",secret));
                if(!reply.optBoolean("verified"))throw new IllegalStateException("管理员设置验证未通过");
            }catch(Exception e){error=e.getMessage()==null?"管理员设置验证失败":e.getMessage();}
            final String problem=error;main.post(()->{if(destroyed||!dialog.isShowing())return;if(!foreground){dialog.dismiss();return;}dialog.getButton(-1).setEnabled(true);dialog.getButton(-1).setText("验证并进入");password.setText("");if(problem!=null){password.setError(problem);return;}dialog.dismiss();enterBaiduSettings();});
            });
        }));dialog.show();
    }
    private void enterBaiduSettings(){
        if(busy.get()){Toast.makeText(this,"设备授权处理中，请稍候",Toast.LENGTH_SHORT).show();return;}
        if(baiduSettingsOpen)return;
        if(camera!=null)camera.stop();
        baiduSettingsOpen=true;
        status.setText("密码验证成功，正在等待算法释放后打开设置…");Toast.makeText(this,"密码验证成功，正在打开管理员设置；本地库加载中可能需要等待",Toast.LENGTH_LONG).show();
        runtime.beginSettings(error->{
            if(destroyed||!foreground){baiduSettingsOpen=false;runtime.endSettings();return;}
            if(error!=null){baiduSettingsOpen=false;status.setText(error);new android.app.AlertDialog.Builder(this).setTitle("管理员设置未打开").setMessage(error).setPositiveButton("知道了",null).show();return;}
            try{
                TerminalApplication.unlock();
                Toast.makeText(this,"原生参数设置：当前业务使用RGB活体，红外/深度需专用硬件与接入；返回后手动开始核验",Toast.LENGTH_LONG).show();
                startActivityForResult(new Intent().setClassName(this,"com.example.settinglibrary.SettingActivity"),201);
            }catch(RuntimeException e){TerminalApplication.lock();baiduSettingsOpen=false;runtime.endSettings();status.setText("百度设置无法打开，请检查完整Demo安装包");}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==201){TerminalApplication.lock();baiduSettingsOpen=false;runtime.endSettings();if(runtime.hasSession())showAccount();}
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
    private android.graphics.drawable.Drawable artwork(){
        try{if(landscape==null)try(java.io.InputStream in=getAssets().open("terminal-landscape.png")){android.graphics.BitmapFactory.Options opt=new android.graphics.BitmapFactory.Options();opt.inSampleSize=2;landscape=android.graphics.BitmapFactory.decodeStream(in,null,opt);}return new android.graphics.drawable.BitmapDrawable(getResources(),landscape);}catch(Exception e){return new TerminalHeaderDrawable();}
    }
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private LinearLayout card(LinearLayout parent){LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(16),dp(16),dp(16),dp(16));box.setBackground(background(Color.WHITE,0));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(box,lp);return box;}
    private void label(LinearLayout box,String value){TextView t=text(value,17,INK);t.setTypeface(null,1);t.setPadding(0,0,0,dp(12));box.addView(t);}
    private Button action(LinearLayout box,String value,Runnable click,boolean primary){Button button=new Button(this);button.setText(value);styleButton(button);if(!primary){button.setTextColor(BLUE);button.setBackground(background(Color.WHITE,0xffc6dcff));}box.addView(button);button.setOnClickListener(v->click.run());return button;}
    private void menu(LinearLayout box,String title,Runnable click){TextView link=text(title+"  ›",16,INK);link.setPadding(0,dp(17),0,dp(17));box.addView(link,new LinearLayout.LayoutParams(-1,-2));link.setOnClickListener(v->click.run());android.view.View line=new android.view.View(this);line.setBackgroundColor(0xffedf2f8);box.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private void pair(LinearLayout root,String first,Runnable a,String second,Runnable b){LinearLayout bar=new LinearLayout(this);Button left=new Button(this),right=new Button(this);left.setText(first);right.setText(second);styleButton(left);styleButton(right);left.setTextColor(BLUE);left.setBackground(background(Color.WHITE,BLUE));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(54),1);lp.setMargins(0,dp(10),dp(5),0);bar.addView(left,lp);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(54),1);rp.setMargins(dp(5),dp(10),0,0);bar.addView(right,rp);root.addView(bar);left.setOnClickListener(v->a.run());right.setOnClickListener(v->b.run());}
    private void row(LinearLayout box,String key,String value){LinearLayout line=new LinearLayout(this);line.setGravity(android.view.Gravity.CENTER_VERTICAL);line.setPadding(0,dp(9),0,dp(9));line.addView(text(key,14,MUTED),new LinearLayout.LayoutParams(0,-2,1));TextView right=text(value,15,INK);right.setGravity(android.view.Gravity.RIGHT);line.addView(right,new LinearLayout.LayoutParams(0,-2,1));box.addView(line);}
    private LinearLayout page(String title){return page(title,true);}
    private LinearLayout page(String title,boolean navigation){
        if(camera!=null){camera.stop();camera=null;}else if(runtime!=null)runtime.pauseCamera();
        accountPage=false;scanPage=false;resultPage=false;syncPage=false;maintenancePage=false;homeAccount=null;homeChips=null;loadRing=null;loadText=null;syncDetail=null;pageVersion++;
        getWindow().setStatusBarColor(BLUE);getWindow().setNavigationBarColor(Color.WHITE);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(1);shell.setBackgroundColor(0xfff0f7ff);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(1);scroll.addView(outer);
        LinearLayout banner=new LinearLayout(this);banner.setOrientation(1);banner.setGravity(android.view.Gravity.CENTER_VERTICAL);banner.setPadding(dp(18),dp(12),dp(18),dp(14));banner.setBackground(artwork());outer.addView(banner,new LinearLayout.LayoutParams(-1,dp(title.equals("景区工作人员登录")?190:100)));
        LinearLayout top=new LinearLayout(this);top.setGravity(android.view.Gravity.CENTER_VERTICAL);TextView heading=text(title,23,Color.WHITE);heading.setTypeface(null,1);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        TextView settings=text("⚙ 管理员设置",12,Color.WHITE);settings.setPadding(dp(8),dp(12),dp(8),dp(12));settings.setBackground(background(0x302075ff,0x88ffffff));top.addView(settings);settings.setOnClickListener(v->openBaiduSettings());banner.addView(top);
        TextView subtitle=text(title.equals("景区工作人员登录")?"一卡通核验端":"景区年卡核验",14,Color.WHITE);subtitle.setPadding(0,dp(6),0,0);banner.addView(subtitle);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(12),dp(12),dp(12),dp(16));outer.addView(root);
        communication=text(runtime.communicationStatus(),12,MUTED);communication.setPadding(dp(6),0,0,dp(6));root.addView(communication);
        status=text("",13,MUTED);status.setPadding(dp(6),0,dp(6),dp(8));root.addView(status);
        if(runtime.hasSession()&&navigation){LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(Color.WHITE);String[] names={"⌂\n核验","▤\n记录","⚙\n维护"};for(int i=0;i<3;i++){final int index=i;TextView tab=text(names[i],14,BLUE);tab.setGravity(17);nav.addView(tab,new LinearLayout.LayoutParams(0,dp(60),1));tab.setOnClickListener(v->{if(index==0)showAccount();else if(index==1)showLocalRecords();else showMaintenance();});}shell.addView(nav);}
        setContentView(shell);return root;
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
        field.setTextColor(Color.rgb(28,46,73));field.setHintTextColor(Color.rgb(120,138,160));
        field.setBackground(background(Color.WHITE,Color.rgb(213,228,247)));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.bottomMargin=dp(14);field.setLayoutParams(params);
    }
    private void styleButton(Button button) {
        button.setTextColor(Color.WHITE);button.setTextSize(17);button.setAllCaps(false);
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},background(Color.rgb(166,187,215),0));
        states.addState(new int[]{},background(Color.rgb(25,117,255),0));
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
    private void showLogin(){
        if(!authorized){showActivation();return;}LinearLayout root=page("景区工作人员登录");int version=pageVersion;
        status.setText("✓ 设备已授权");LinearLayout form=card(root);label(form,"账号登录");
        form.addView(text("子账号",15,INK));EditText user=new EditText(this);styleInput(user,"请输入工作人员账号");form.addView(user);
        form.addView(text("密码",15,INK));EditText password=new EditText(this);styleInput(password,"请输入密码");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);form.addView(password);
        CheckBox visible=new CheckBox(this);visible.setText("显示密码");form.addView(visible);visible.setOnCheckedChangeListener((v,checked)->{password.setTransformationMethod(checked?null:PasswordTransformationMethod.getInstance());password.setSelection(password.length());});
        Button login=new Button(this);login.setText("登录");styleButton(login);form.addView(login);TextView hint=text("ⓘ 登录后自动获取所属景区与核验规则",13,MUTED);hint.setPadding(0,dp(14),0,0);form.addView(hint);
        login.setOnClickListener(v->{String name=user.getText().toString().trim(),secret=password.getText().toString();if(name.isEmpty()||secret.isEmpty()){status.setText("请输入账号和密码");return;}login.setEnabled(false);status.setText("正在验证账号…");runtime.login(savedOrigin(),fingerprint(),name,secret,error->{if(destroyed||version!=pageVersion)return;if(error!=null){login.setEnabled(true);status.setText(error);return;}password.setText("");showSyncPage();});});
        LinearLayout connection=card(root);label(connection,"▣ 平台连接");connection.addView(text(runtime.communicationStatus(),14,MUTED));action(connection,"修改连接地址  ›",this::showPlatformSettings,false);
        LinearLayout note=card(root);note.setBackground(background(0xffe0f2ff,0));label(note,"同平台换账号，保留本地人员库");note.addView(text("更换账号后仍可使用本地已下载的人员库，无需重复同步。冷启动需加载本地检索库。",13,MUTED));
        if(runtime.hasSession()){status.setText("原会话尚未退出，只能重新验证原账号");action(root,"返回原账号同步",this::showAccount,false);}
    }
    private void showAccount(){
        LinearLayout root=page("一卡通核验端");accountPage=true;status.setText(lastState);JSONObject profile=runtime.uiProfile();
        LinearLayout state=card(root);LinearLayout chips=new LinearLayout(this);String[] vals={authorized?"✓ 设备已授权":"设备未授权",runtime.communicationStatus(),profile.optBoolean("sdkReady")?"◇ SDK就绪":"◇ SDK加载中"};homeChips=new TextView[3];int chipIndex=0;for(String value:vals){TextView t=text(value,12,BLUE);homeChips[chipIndex++]=t;t.setGravity(17);chips.addView(t,new LinearLayout.LayoutParams(0,-2,1));}state.addView(chips);homeAccount=text(profile.optString("scenicName","等待景区同步")+" · "+profile.optString("accountName","等待账号同步"),15,INK);homeAccount.setPadding(0,dp(16),0,dp(8));state.addView(homeAccount);TextView sync=text("人员库与同步状态  ›",14,BLUE);sync.setPadding(0,dp(8),0,0);state.addView(sync);sync.setOnClickListener(v->showSyncPage());
        FrameLayout hero=new FrameLayout(this);hero.setBackground(artwork());root.addView(hero,new LinearLayout.LayoutParams(-1,dp(205)));hero.addView(new TerminalFaceGuide(this,false),new FrameLayout.LayoutParams(-1,-1));TextView guide=text("请正对镜头，保持面部无遮挡",14,Color.WHITE);guide.setGravity(17);guide.setBackground(background(0xaa152734,0));FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,dp(36),android.view.Gravity.BOTTOM);hero.addView(guide,gp);
        startButton=action(root,runtime.ready()?"▣  开始核验":"等待同步完成",()->{if(cameraAllowed())showScan();else requestCamera(true);},true);startButton.setEnabled(runtime.ready());
        LinearLayout summary=card(root);final int version=pageVersion;summary.addView(text("正在读取今日记录…",14,MUTED));runtime.localRecords((data,error)->{if(destroyed||pageVersion!=version)return;summary.removeAllViews();if(error!=null){summary.addView(text(error,14,MUTED));return;}LinearLayout numbers=new LinearLayout(this);String[] keys={"passed","denied","pending"},labels={"今日通过","今日拒绝","待确认"};int[] colors={0xff00b96b,0xffff9a00,BLUE};for(int i=0;i<3;i++){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(4),dp(12),dp(4),dp(12));c.setGravity(17);c.setBackground(background(i==0?0xffedfaf5:i==1?0xfffff7ef:0xffedf5ff,0));TextView n=text(String.valueOf(data.optLong(keys[i])),26,colors[i]);n.setTypeface(null,1);c.addView(n);c.addView(text(labels[i],12,MUTED));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),0,dp(3),dp(12));numbers.addView(c,lp);}summary.addView(numbers);label(summary,"最近核验记录");JSONArray rows=data.optJSONArray("items");if(rows==null||rows.length()==0)summary.addView(text("本机当前会话暂无记录",14,MUTED));else for(int i=0;i<Math.min(3,rows.length());i++){JSONObject r=rows.optJSONObject(i);if(r!=null)row(summary,r.optString("time"),r.optString("text"));}TextView more=text("本机当前会话 · 查看全部  ›",12,BLUE);summary.addView(more);more.setOnClickListener(v->showLocalRecords());});
    }
    private void showScan(){
        if(!runtime.ready()){showSyncPage();return;}LinearLayout root=page("人脸核验",false);scanPage=true;status.setText(runtime.uiProfile().optString("scenicName"));
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(0xff24384b);root.addView(frame,new LinearLayout.LayoutParams(-1,dp(420)));cameraPreview=new TextureView(this);frame.addView(cameraPreview,new FrameLayout.LayoutParams(-1,-1));frame.addView(new TerminalFaceGuide(this,true),new FrameLayout.LayoutParams(-1,-1));
        resultView=text("正在核验，请保持稳定",18,Color.WHITE);resultView.setGravity(17);resultView.setBackground(background(0xbb172737,0));FrameLayout.LayoutParams hint=new FrameLayout.LayoutParams(-1,dp(54),android.view.Gravity.BOTTOM);frame.addView(resultView,hint);
        diagnosticView=text("识别完成后由平台确认入园权限",13,MUTED);diagnosticView.setPadding(0,dp(14),0,dp(14));root.addView(diagnosticView);
        camera=new TerminalCamera(this,runtime,cameraPreview,error->{status.setText(error);startButton.setText("开始核验");});
        startButton=action(root,"暂停核验",()->{if("暂停核验".contentEquals(startButton.getText())){camera.stop();startButton.setText("开始核验");resultView.setText("已暂停");}else startScanning();},false);
        action(root,"下一位 / 重新核验",()->{if(!"暂停核验".contentEquals(startButton.getText())){status.setText("请先开始核验");return;}runtime.nextAttempt();},false);action(root,"返回首页",this::showAccount,false);startScanning();
    }
    private boolean syncPage,maintenancePage;
    private void updateSync(){if(loadRing==null)return;long total=runtime.loadTotal(),loaded=runtime.loadedFaces();loadRing.setProgress(loaded,total);loadText.setText(loaded+" / "+total+"\n已用时 "+runtime.loadElapsed()/1000+" 秒");JSONObject p=runtime.uiProfile();syncDetail.setText("平台待同步："+p.optLong("pendingPeople")+"    待回执："+p.optLong("pendingReceipts")+"\n\n平台配置："+(p.optBoolean("platformSynced")?"已同步":"等待同步")+"\n检索库："+(p.optBoolean("sdkReady")?"已加载":"准备中")+"\n\n"+lastState);}
    private void showSyncPage(){
        LinearLayout root=page("人员同步");syncPage=true;status.setText("优先处理删除与失效数据");LinearLayout loading=card(root);label(loading,"本地检索库加载");LinearLayout progress=new LinearLayout(this);progress.setGravity(16);loadRing=new TerminalProgressView(this);progress.addView(loadRing,new LinearLayout.LayoutParams(dp(120),dp(120)));loadText=text("",19,INK);progress.addView(loadText,new LinearLayout.LayoutParams(0,-2,1));loading.addView(progress);
        LinearLayout changes=card(root);label(changes,"平台增量同步");syncDetail=text("",15,MUTED);changes.addView(syncDetail);updateSync();
        LinearLayout note=card(root);note.setBackground(background(0xffe4f3ff,0));note.addView(text("加载期间可查看记录与维护，完成后可开始核验。同平台换账号保留本地人员库。",14,MUTED));
        action(root,"立即同步 / 重试",()->runtime.retry(),false);action(root,"返回首页",this::showAccount,false);
    }
    private void showMaintenance(){
        LinearLayout root=page("设备维护");maintenancePage=true;status.setText(lastState);JSONObject p=runtime.uiProfile();LinearLayout device=card(root);label(device,authorized?"✓ 设备已授权":"设备未授权");row(device,"平台通信",runtime.communicationStatus());row(device,"当前账号",p.optString("accountName","待同步"));row(device,"所属景区",p.optString("scenicName","待同步"));row(device,"识别模式","RGB活体");
        LinearLayout sync=card(root);label(sync,"▣ 数据与同步");row(sync,"本地特征",String.valueOf(p.optLong("localFaces")));row(sync,"待确认记录",String.valueOf(p.optLong("pendingEvents")));row(sync,"同步状态",runtime.ready()?"已完成":"准备中");action(sync,"立即同步 / 查看进度",this::showSyncPage,false);
        LinearLayout menus=card(root);menu(menus,"⚙ 管理员设置",this::openBaiduSettings);menu(menus,"核验记录与统计",this::showStatistics);menu(menus,"本机核验记录",this::showLocalRecords);menu(menus,"相机权限管理",this::explainPermissions);menu(menus,"备份与恢复 · 规划中",()->new android.app.AlertDialog.Builder(this).setTitle("备份与恢复").setMessage("卸载后的加密备份恢复尚未开放。请使用覆盖安装，保留本机未确认记录与人员库。").setPositiveButton("知道了",null).show());
        action(root,"平台连接设置",this::showPlatformSettings,false);action(root,"重新验证原账号",this::showLogin,false);
        Button logout=action(root,"同步后退出账号",()->{},false);logout.setOnClickListener(v->{logout.setEnabled(false);status.setText("正在确认原账号记录、人员任务和回执…");runtime.exit(error->{if(destroyed)return;logout.setEnabled(true);if(error!=null)status.setText(error);else if(!runtime.hasSession())showLogin();});});TextView note=text("ⓘ 原账号记录确认完成后才能切换",12,MUTED);note.setGravity(17);root.addView(note);
    }
    private void startScanning(){if(camera==null||!runtime.startCamera()){status.setText("请等待设备就绪后开始核验");return;}cameraPreview.setVisibility(android.view.View.VISIBLE);if(!camera.start())return;startButton.setText("暂停核验");resultView.setText("请正对摄像头，每次核验后移开");}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request!=200)return;
        boolean resume=startAfterPermission;startAfterPermission=false;
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"相机已授权",Toast.LENGTH_SHORT).show();if(resume&&accountPage)showScan();
        }else{status.setText("相机权限未授权；可继续同步和统计，需要核验时请打开权限管理");}
    }

    private void showLocalRecords(){
        LinearLayout root=page("本机记录与维护");int version=pageVersion;
        TextView summary=new TextView(this);summary.setTextSize(16);summary.setTextColor(Color.rgb(45,65,95));root.addView(summary);
        Button refresh=new Button(this);refresh.setText("刷新记录");styleButton(refresh);root.addView(refresh);
        Button retry=new Button(this);retry.setText("重试同步与回执");styleButton(retry);root.addView(retry);retry.setOnClickListener(v->runtime.retry());
        TextView rows=new TextView(this);rows.setTextSize(15);rows.setTextColor(Color.rgb(45,65,95));rows.setPadding(0,dp(16),0,dp(16));root.addView(rows);
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
    @Override public void status(String value,boolean ready,boolean hasSession){
        if(destroyed)return;lastState=value;if(syncPage){status.setText(value);updateSync();}
        if(!hasSession&&authorized&&!baiduSettingsOpen&&(accountPage||scanPage||resultPage||syncPage||maintenancePage)){showLogin();return;}
        if(accountPage){status.setText(value);JSONObject p=runtime.uiProfile();if(homeChips!=null){homeChips[1].setText(runtime.communicationStatus());homeChips[2].setText(p.optBoolean("sdkReady")?"◇ SDK就绪":"◇ SDK加载中");}if(homeAccount!=null)homeAccount.setText(p.optString("scenicName","等待景区同步")+" · "+p.optString("accountName","等待账号同步"));if(startButton!=null){startButton.setText(ready?"▣  开始核验":"等待同步完成");startButton.setEnabled(ready);}}
        if(scanPage){status.setText(value);if(!ready){if(camera!=null)camera.stop();startButton.setText("开始核验");}startButton.setEnabled(ready);}
    }
    @Override public void diagnostics(String value){if(!destroyed&&scanPage&&diagnosticView!=null)diagnosticView.setText(value);}
    @Override public void communication(String value){if(!destroyed&&communication!=null){communication.setText(value);if(accountPage&&homeChips!=null)homeChips[1].setText(value);}}
    @Override public void result(String value,String voice,boolean open){
        if(destroyed||!scanPage)return;if(value.startsWith("已开始新一轮")){resultView.setText(value);return;}
        if(speechReady&&!voice.isEmpty())speech.speak(voice,TextToSpeech.QUEUE_FLUSH,null,"terminal-result");
        LinearLayout root=page("核验结果",false);resultPage=true;status.setText("");LinearLayout outcome=card(root);outcome.setGravity(17);TextView badge=text(open?"✓":"!",60,open?0xff00bf73:0xffff7a45);badge.setGravity(17);badge.setBackground(background(open?0xffe0f9ee:0xffffede6,0));outcome.addView(badge,new LinearLayout.LayoutParams(dp(110),dp(110)));TextView title=text(open?"核验通过":"本次未放行",30,open?0xff004b31:0xffb64520);title.setTypeface(null,1);title.setPadding(0,dp(18),0,dp(8));outcome.addView(title);TextView detail=text(value,17,MUTED);detail.setGravity(17);outcome.addView(detail);
        LinearLayout facts=card(root);SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA);fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));row(facts,"结果显示时间",fmt.format(new java.util.Date()));row(facts,"所属景区",runtime.uiProfile().optString("scenicName","待同步"));row(facts,"核验方式","人脸核验");facts.addView(text("入园记录回执以记录页状态为准",12,MUTED));
        LinearLayout art=card(root);art.setBackground(artwork());art.setGravity(17);art.setMinimumHeight(dp(130));TextView message=text(open?"祝您游览愉快":"请核对提示后重试",23,0xff064e55);message.setTypeface(null,1);art.addView(message);
        pair(root,"返回首页",this::showAccount,"继续核验",()->{if(runtime.ready())showScan();else showSyncPage();});
    }
    @Override public void onBackPressed(){if(scanPage||resultPage||syncPage||maintenancePage){showAccount();return;}if(runtime.hasSession()){showMaintenance();return;}super.onBackPressed();}
    @Override protected void onResume() { super.onResume();foreground=true; }
    @Override protected void onPause() {
        foreground=false;
        if(camera!=null)camera.stop();runtime.pauseCamera();if(startButton!=null)startButton.setText("开始核验");super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed=true;adminWorker.shutdownNow();pageVersion++;main.removeCallbacks(timeout);runtime.detach(this);if(camera!=null)camera.stop();if(speech!=null){speech.stop();speech.shutdown();}super.onDestroy();
    }
}

