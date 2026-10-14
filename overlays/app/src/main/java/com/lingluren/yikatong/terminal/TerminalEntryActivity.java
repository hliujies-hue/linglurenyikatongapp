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
    private final java.util.Map<String,android.graphics.Bitmap> artworkCache=new java.util.HashMap<>();
    private boolean localVoice(){return getSharedPreferences("terminal_audio",MODE_PRIVATE).getBoolean("enabled",true);}
    private TextView loadText, syncDetail, homeAccount,resultReceipt;
    private TextView[] homeChips;
    private Runnable refreshHomeStatistics;
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
        if((request==301||request==302)&&result==RESULT_OK&&data!=null&&data.getData()!=null){backupPassword(data.getData(),request==302);return;}
        if(request==201){TerminalApplication.lock();baiduSettingsOpen=false;runtime.endSettings();if(runtime.hasSession())showAccount();}
    }
    private TextView status,communication,maintenanceConnection;
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
    private android.graphics.drawable.Drawable artwork(){return artwork("terminal-header-blue.png");}
    private android.graphics.drawable.Drawable artwork(String name){
        try{android.graphics.Bitmap bmp=artworkCache.get(name);if(bmp==null)try(java.io.InputStream in=getAssets().open(name)){android.graphics.BitmapFactory.Options opt=new android.graphics.BitmapFactory.Options();opt.inSampleSize=2;bmp=android.graphics.BitmapFactory.decodeStream(in,null,opt);artworkCache.put(name,bmp);}return new TerminalLandscapeDrawable(bmp);}catch(Exception e){return new TerminalHeaderDrawable();}
    }
    private void round(android.view.View v,int radius){v.setClipToOutline(true);v.setOutlineProvider(new android.view.ViewOutlineProvider(){public void getOutline(android.view.View view,android.graphics.Outline out){out.setRoundRect(0,0,view.getWidth(),view.getHeight(),dp(radius));}});}
    private LinearLayout iconLabel(String icon,String title,int color,int size){LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER);row.addView(new TerminalIconView(this,icon,color),new LinearLayout.LayoutParams(dp(size),dp(size)));TextView label=text(title,13,INK);label.setPadding(dp(5),0,0,0);row.addView(label);return row;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
    private LinearLayout card(LinearLayout parent){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(16),dp(16),dp(16));box.setBackground(background(Color.WHITE,0));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(box,lp);return box;}
    private void label(LinearLayout box,String value){TextView t=text(value,17,INK);t.setTypeface(null,android.graphics.Typeface.BOLD);t.setPadding(0,0,0,dp(12));box.addView(t);}
    private Button action(LinearLayout box,String value,Runnable click,boolean primary){Button button=new Button(this);button.setText(value);styleButton(button);if(!primary){button.setTextColor(BLUE);button.setBackground(background(Color.WHITE,0xffc6dcff));}box.addView(button);button.setOnClickListener(v->click.run());return button;}
    private void menu(LinearLayout box,String title,Runnable click){TextView link=text(title+"  ›",16,INK);link.setPadding(0,dp(17),0,dp(17));box.addView(link,new LinearLayout.LayoutParams(-1,-2));link.setOnClickListener(v->click.run());android.view.View line=new android.view.View(this);line.setBackgroundColor(0xffedf2f8);box.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private void pair(LinearLayout root,String first,Runnable a,String second,Runnable b){LinearLayout bar=new LinearLayout(this);Button left=new Button(this),right=new Button(this);left.setText(first);right.setText(second);styleButton(left);styleButton(right);left.setTextColor(BLUE);left.setBackground(background(Color.WHITE,BLUE));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(54),1);lp.setMargins(0,dp(10),dp(5),0);bar.addView(left,lp);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(54),1);rp.setMargins(dp(5),dp(10),0,0);bar.addView(right,rp);root.addView(bar);left.setOnClickListener(v->a.run());right.setOnClickListener(v->b.run());}
    private TextView row(LinearLayout box,String key,String value){LinearLayout line=new LinearLayout(this);line.setGravity(android.view.Gravity.CENTER_VERTICAL);line.setPadding(0,dp(9),0,dp(9));line.addView(text(key,14,MUTED),new LinearLayout.LayoutParams(dp(94),-2));TextView right=text(TerminalMessages.friendly(value),15,INK);right.setLineSpacing(dp(3),1);right.setGravity(android.view.Gravity.RIGHT);line.addView(right,new LinearLayout.LayoutParams(0,-2,1));box.addView(line);return right;}
    private String connectionLabel(){return runtime.communicationStatus().contains("：在线")?"平台在线":"连接待确认";}
    private void homeStatus(String value){status.setText(value.contains("失败")||value.contains("异常")?"同步异常 · 点击查看原因与重试":runtime.ready()?"":"正在准备人员库 · 点击查看进度");status.setVisibility(status.length()==0?android.view.View.GONE:android.view.View.VISIBLE);status.setTextColor(value.contains("失败")?0xffba522d:MUTED);status.setOnClickListener(v->showSyncPage());}
    private LinearLayout page(String title){return page(title,true);}
    private LinearLayout page(String title,boolean navigation){
        if(camera!=null){camera.stop();camera=null;}else if(runtime!=null)runtime.pauseCamera();
        if(refreshHomeStatistics!=null){main.removeCallbacks(refreshHomeStatistics);refreshHomeStatistics=null;}
        maintenanceConnection=null;accountPage=false;scanPage=false;resultPage=false;syncPage=false;maintenancePage=false;homeAccount=null;homeChips=null;resultReceipt=null;loadRing=null;loadText=null;syncDetail=null;pageVersion++;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);getWindow().setStatusBarColor(0xff2f8fff);getWindow().setNavigationBarColor(Color.WHITE);
        boolean login="景区工作人员登录".equals(title),home="一卡通核验端".equals(title);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(0xfff0f7ff);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);scroll.addView(outer);
        LinearLayout banner=new LinearLayout(this);banner.setOrientation(LinearLayout.VERTICAL);banner.setGravity(login?android.view.Gravity.CENTER:android.view.Gravity.CENTER_VERTICAL);banner.setPadding(dp(18),dp(12),dp(18),dp(16));banner.setBackground(artwork());banner.setMinimumHeight(dp(login?200:home?78:72));outer.addView(banner,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout top=new LinearLayout(this);top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if(!home&&!login){TerminalIconView back=new TerminalIconView(this,"back",Color.WHITE);top.addView(back,new LinearLayout.LayoutParams(dp(30),dp(36)));back.setOnClickListener(v->{if(runtime.hasSession())showAccount();else showLogin();});}
        TextView heading=text(login?"一卡通核验端":title,login?28:home?23:21,Color.WHITE);heading.setTypeface(null,android.graphics.Typeface.BOLD);if(!home)heading.setGravity(android.view.Gravity.CENTER);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        if(!home&&!login){top.addView(new android.view.View(this),new LinearLayout.LayoutParams(dp(30),1));}banner.addView(top,new LinearLayout.LayoutParams(-1,-2));
        if(home||login){TextView subtitle=text(login?"景区工作人员登录":"景区年卡核验",15,Color.WHITE);subtitle.setGravity(login?android.view.Gravity.CENTER:android.view.Gravity.LEFT);subtitle.setPadding(0,dp(8),0,0);banner.addView(subtitle);}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(8),dp(12),dp(8));root.setBackground(background(home?Color.WHITE:0xfff0f7ff,0));LinearLayout.LayoutParams content=new LinearLayout.LayoutParams(-1,-2);content.topMargin=-dp(8);outer.addView(root,content);
        communication=text(runtime.communicationStatus(),12,MUTED);communication.setPadding(dp(4),0,0,dp(6));root.addView(communication);communication.setVisibility(android.view.View.GONE);
        status=text("",13,MUTED);status.setPadding(dp(4),0,dp(4),dp(8));root.addView(status);
        if(runtime.hasSession()&&navigation){LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(Color.WHITE);String[] names={"核验","记录","维护"},icons={"home","record","settings"};int selected=home?0:title.contains("记录")?1:2;for(int i=0;i<3;i++){final int index=i;LinearLayout tab=new LinearLayout(this);tab.setOrientation(LinearLayout.VERTICAL);tab.setGravity(android.view.Gravity.CENTER);int tint=i==selected?BLUE:MUTED;tab.addView(new TerminalIconView(this,icons[i],tint),new LinearLayout.LayoutParams(dp(23),dp(23)));TextView caption=text(names[i],11,tint);caption.setPadding(0,dp(5),0,0);caption.setGravity(android.view.Gravity.CENTER);tab.addView(caption,new LinearLayout.LayoutParams(-1,-2));nav.addView(tab,new LinearLayout.LayoutParams(0,dp(59),1));tab.setOnClickListener(v->{if(index==0)showAccount();else if(index==1)showLocalRecords();else showMaintenance();});}shell.addView(nav,new LinearLayout.LayoutParams(-1,-2));}
        addSupport(shell);
        setContentView(shell);return root;
    }
    private void addSupport(LinearLayout shell){
        TextView support=text("河北领路人科技有限公司 提供技术支持",11,MUTED);support.setGravity(android.view.Gravity.CENTER);support.setPadding(dp(8),dp(9),dp(8),dp(9));support.setMinHeight(dp(40));support.setBackgroundColor(Color.WHITE);support.setContentDescription("河北领路人科技有限公司 提供技术支持，打开官方网站");support.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://linglu.ren")));}catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"请在浏览器访问 linglu.ren",Toast.LENGTH_LONG).show();}});shell.addView(support,new LinearLayout.LayoutParams(-1,-2));
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
        if(cameraAllowed()){if(start)showScan();return;}
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
        button.setTextColor(Color.WHITE);button.setTextSize(17);button.setAllCaps(false);button.setPadding(dp(8),0,dp(8),0);button.setMinWidth(0);button.setMinimumWidth(0);button.setIncludeFontPadding(false);
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
        status.setText("设备已授权 · "+connectionLabel());status.setGravity(android.view.Gravity.CENTER);LinearLayout form=card(root);label(form,"账号登录");
        form.addView(text("子账号",15,INK));EditText user=new EditText(this);styleInput(user,"请输入工作人员账号");form.addView(user);
        form.addView(text("密码",15,INK));EditText password=new EditText(this);styleInput(password,"请输入密码");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setPadding(dp(14),dp(12),dp(56),dp(12));FrameLayout passwordBox=new FrameLayout(this);LinearLayout.LayoutParams passwordLp=new LinearLayout.LayoutParams(-1,dp(52));passwordLp.bottomMargin=dp(14);form.addView(passwordBox,passwordLp);passwordBox.addView(password,new FrameLayout.LayoutParams(-1,-1));TextView visible=text("显示",12,MUTED);visible.setGravity(android.view.Gravity.CENTER);visible.setContentDescription("显示或隐藏密码");passwordBox.addView(visible,new FrameLayout.LayoutParams(dp(52),-1,android.view.Gravity.RIGHT));visible.setOnClickListener(v->{boolean hidden=password.getTransformationMethod()!=null;password.setTransformationMethod(hidden?null:PasswordTransformationMethod.getInstance());visible.setText(hidden?"隐藏":"显示");password.setSelection(password.length());});
        Button login=new Button(this);login.setText("登录");styleButton(login);form.addView(login);TextView hint=text("登录后自动获取所属景区与核验规则",13,MUTED);hint.setPadding(0,dp(14),0,0);form.addView(hint);
        login.setOnClickListener(v->{String name=user.getText().toString().trim(),secret=password.getText().toString();if(name.isEmpty()||secret.isEmpty()){status.setText("请输入账号和密码");return;}login.setEnabled(false);status.setText("正在验证账号…");runtime.login(savedOrigin(),fingerprint(),name,secret,error->{if(destroyed||version!=pageVersion)return;if(error!=null){login.setEnabled(true);status.setText(error);return;}password.setText("");showSyncPage();});});
        LinearLayout connection=card(root);label(connection,"平台连接");connection.addView(text(runtime.communicationStatus(),14,MUTED));action(connection,"修改连接地址  ›",this::showPlatformSettings,false);
        LinearLayout note=card(root);note.setBackground(background(0xffe0f2ff,0));label(note,"同平台换账号，保留本地人员库");note.addView(text("更换账号后仍可使用本地已下载的人员库，无需重复同步。冷启动需加载本地检索库。",13,MUTED));
        if(runtime.hasSession()){status.setText("原会话尚未退出，只能重新验证原账号");action(root,"返回原账号同步",this::showAccount,false);}
    }
    private void showAccount(){
        LinearLayout root=page("一卡通核验端");accountPage=true;homeStatus(lastState);JSONObject profile=runtime.uiProfile();
        LinearLayout state=new LinearLayout(this);state.setOrientation(LinearLayout.VERTICAL);state.setPadding(dp(2),dp(6),dp(2),dp(10));root.addView(state);
        LinearLayout chips=new LinearLayout(this);String[] vals={authorized?"设备已授权":"设备未授权",connectionLabel(),profile.optBoolean("sdkReady")?"SDK就绪":"SDK准备中"},icons={"check","wifi","sdk"};homeChips=new TextView[3];
        for(int i=0;i<3;i++){LinearLayout box=new LinearLayout(this);box.setGravity(android.view.Gravity.CENTER);box.addView(new TerminalIconView(this,icons[i],i==0?0xff00be79:BLUE),new LinearLayout.LayoutParams(dp(20),dp(20)));TextView t=text(vals[i],12,INK);t.setPadding(dp(5),0,0,0);homeChips[i]=t;box.addView(t);box.setMinimumHeight(dp(32));chips.addView(box,new LinearLayout.LayoutParams(0,-2,1));}state.addView(chips);
        homeAccount=text(profile.optString("scenicName","等待景区同步")+" · "+profile.optString("accountName","等待账号同步"),14,INK);homeAccount.setPadding(dp(4),dp(13),0,dp(8));state.addView(homeAccount);TextView sync=text("人员库"+(runtime.ready()?"已就绪":"同步中")+"  ›",12,BLUE);sync.setPadding(dp(4),0,0,0);state.addView(sync);sync.setOnClickListener(v->showSyncPage());
        FrameLayout hero=new FrameLayout(this);hero.setBackground(artwork("terminal-landscape.png"));round(hero,12);int width=getResources().getDisplayMetrics().widthPixels;int previewHeight=Math.max(180,Math.min(238,Math.round(width/getResources().getDisplayMetrics().density*.50f)));root.addView(hero,new LinearLayout.LayoutParams(-1,dp(previewHeight)));
        TextureView preview=new TextureView(this);hero.addView(preview,new FrameLayout.LayoutParams(-1,-1));hero.addView(new TerminalFaceGuide(this,false),new FrameLayout.LayoutParams(-1,-1));TextView guide=text(cameraAllowed()?"请正对镜头，保持面部无遮挡":"点击开始核验后授权相机",13,Color.WHITE);guide.setGravity(android.view.Gravity.CENTER);guide.setBackground(background(0xbb152734,0));FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,dp(32),android.view.Gravity.BOTTOM);gp.setMargins(dp(30),0,dp(30),dp(8));hero.addView(guide,gp);
        camera=new TerminalCamera(this,runtime,preview,error->{guide.setText(error);});if(cameraAllowed())camera.preview();
        startButton=action(root,runtime.ready()?"开始核验":"查看同步进度",()->{if(!runtime.ready()){showSyncPage();return;}if(cameraAllowed())showScan();else requestCamera(true);},true);
        LinearLayout metrics=new LinearLayout(this);metrics.setPadding(0,dp(10),0,dp(6));root.addView(metrics);String[] keys={"passedPeople","passed","denied","notEffective"},labels={"通过人数","通过人次","今日拒绝","今日待生效"},metricIcons={"person","check","alert","clock"};int[] colors={0xff00b96b,BLUE,0xffee8240,0xffe9a100},fills={0xffedfaf5,0xffedf5ff,0xfffff2ec,0xfffff8e8};TextView[] values=new TextView[4];
        for(int i=0;i<4;i++){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(android.view.Gravity.CENTER);box.setPadding(dp(2),dp(7),dp(2),dp(7));box.setBackground(background(fills[i],0));box.addView(new TerminalIconView(this,metricIcons[i],colors[i]),new LinearLayout.LayoutParams(dp(20),dp(20)));TextView n=text("—",23,INK);n.setTypeface(null,android.graphics.Typeface.BOLD);n.setGravity(android.view.Gravity.CENTER);n.setPadding(0,dp(5),0,dp(5));values[i]=n;n.setMaxLines(1);if(Build.VERSION.SDK_INT>=26)n.setAutoSizeTextTypeUniformWithConfiguration(14,23,1,android.util.TypedValue.COMPLEX_UNIT_SP);box.addView(n,new LinearLayout.LayoutParams(-1,-2));TextView caption=text(labels[i],11,MUTED);caption.setGravity(android.view.Gravity.CENTER);box.addView(caption,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(i==0?0:dp(4),0,i==3?0:dp(4),0);metrics.addView(box,lp);}
        TextView scope=text("正在读取景区共享统计…",12,MUTED);scope.setPadding(dp(3),dp(8),dp(3),dp(12));scope.setLineSpacing(dp(3),1);root.addView(scope,new LinearLayout.LayoutParams(-1,-2));final int version=pageVersion;
        refreshHomeStatistics=new Runnable(){public void run(){
            if(destroyed||pageVersion!=version||!foreground)return;
            runtime.today((data,error)->{
                if(destroyed||pageVersion!=version||!foreground)return;
                JSONObject counts=data==null?null:data.optJSONObject("counts");
                if(error!=null||counts==null){for(TextView value:values)value.setText("—");scope.setText(error!=null?"统计暂不可用 · 将自动重试":"统计格式不匹配，请更新平台");}
                else{for(int i=0;i<4;i++)values[i].setText(String.valueOf(counts.optLong(keys[i])));scope.setText(data.optString("date")+" · "+data.optString("scopeText","统计范围待确认")+"\n通过人数、待生效按人去重；通过人次不去重");}
                main.removeCallbacks(this);main.postDelayed(this,16000);
            });
        }};if(foreground)refreshHomeStatistics.run();
        LinearLayout summary=new LinearLayout(this);summary.setOrientation(LinearLayout.VERTICAL);summary.setPadding(dp(3),dp(8),dp(3),0);root.addView(summary);label(summary,"本机最近核验");runtime.localRecords((data,error)->{if(destroyed||pageVersion!=version)return;if(error!=null){summary.addView(text(error,13,MUTED));return;}JSONArray rows=data.optJSONArray("items");if(rows==null||rows.length()==0)summary.addView(text("本机当前会话暂无记录",13,MUTED));else for(int i=0;i<Math.min(3,rows.length());i++){JSONObject r=rows.optJSONObject(i);if(r==null)continue;LinearLayout line=new LinearLayout(this);line.setGravity(android.view.Gravity.CENTER_VERTICAL);boolean pass=r.optBoolean("pass");line.addView(new TerminalIconView(this,pass?"check":"alert",pass?0xff00bd79:0xffff6b43),new LinearLayout.LayoutParams(dp(18),dp(18)));TextView time=text(r.optString("time"),12,MUTED);time.setPadding(dp(10),0,dp(6),0);line.addView(time,new LinearLayout.LayoutParams(0,dp(30),1));time.setGravity(android.view.Gravity.CENTER_VERTICAL);TextView outcome=text(r.optString("title",r.optString("text")),12,pass?0xff00ac6d:0xffd56640);line.addView(outcome);summary.addView(line);}TextView more=text("本机记录 · 查看全部  ›",12,BLUE);more.setPadding(0,dp(8),0,0);summary.addView(more);more.setOnClickListener(v->showLocalRecords());});
    }
    private void showScan(){
        if(!runtime.ready()){showSyncPage();return;}page("人脸核验",false);scanPage=true;
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(0xfff7fbff);LinearLayout heading=new LinearLayout(this);heading.setGravity(android.view.Gravity.CENTER_VERTICAL);heading.setPadding(dp(12),dp(8),dp(12),dp(8));heading.setBackgroundColor(0xff2f8fff);TerminalIconView back=new TerminalIconView(this,"back",Color.WHITE);heading.addView(back,new LinearLayout.LayoutParams(dp(32),dp(40)));back.setOnClickListener(v->showAccount());LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setGravity(android.view.Gravity.CENTER);TextView title=text("人脸核验",22,Color.WHITE);title.setTypeface(null,android.graphics.Typeface.BOLD);titles.addView(title);titles.addView(text(runtime.uiProfile().optString("scenicName"),13,Color.WHITE));heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));TextView mute=text(voiceLabel(),12,Color.WHITE);heading.addView(mute,new LinearLayout.LayoutParams(dp(56),dp(40)));mute.setGravity(android.view.Gravity.CENTER);mute.setOnClickListener(v->{boolean enabled=!localVoice();getSharedPreferences("terminal_audio",MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();if(!enabled&&speech!=null)speech.stop();mute.setText(voiceLabel());});shell.addView(heading);
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(0xff24384b);round(frame,16);shell.addView(frame,new LinearLayout.LayoutParams(-1,0,1));cameraPreview=new TextureView(this);frame.addView(cameraPreview,new FrameLayout.LayoutParams(-1,-1));frame.addView(new TerminalFaceGuide(this,true),new FrameLayout.LayoutParams(-1,-1));
        resultView=text("正在核验，请保持稳定",18,Color.WHITE);resultView.setGravity(android.view.Gravity.CENTER);resultView.setBackground(background(0xbb172737,0));FrameLayout.LayoutParams hint=new FrameLayout.LayoutParams(-1,dp(54),android.view.Gravity.BOTTOM);hint.setMargins(dp(30),0,dp(30),dp(20));frame.addView(resultView,hint);
        diagnosticView=text("识别完成后由平台确认入园权限",13,MUTED);diagnosticView.setGravity(android.view.Gravity.CENTER);diagnosticView.setPadding(dp(12),dp(12),dp(12),dp(12));shell.addView(diagnosticView);
        LinearLayout controls=new LinearLayout(this);controls.setPadding(dp(12),0,dp(12),dp(16));shell.addView(controls);String[] names={"暂停核验","下一位","重新核验"};for(int i=0;i<3;i++){final int action=i;Button button=new Button(this);styleButton(button);button.setTextSize(14);button.setText(names[i]);button.setTextColor(i==0?0xfff05d49:BLUE);button.setBackground(background(Color.WHITE,i==0?0xffff8e80:0xffc3daff));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(4),0,dp(4),0);controls.addView(button,lp);if(i==0)startButton=button;button.setOnClickListener(v->{if(action==0){if("暂停核验".contentEquals(startButton.getText())){camera.stop();startButton.setText("开始核验");resultView.setText("已暂停核验");}else startScanning();}else{if(!"暂停核验".contentEquals(startButton.getText()))startScanning();if("暂停核验".contentEquals(startButton.getText()))runtime.nextAttempt();}});}
        camera=new TerminalCamera(this,runtime,cameraPreview,error->{diagnosticView.setText(error);startButton.setText("开始核验");});addSupport(shell);setContentView(shell);startScanning();
    }
    private boolean syncPage,maintenancePage;
    private void updateSync(){if(loadRing==null)return;long total=runtime.loadTotal(),loaded=runtime.loadedFaces();loadRing.setProgress(loaded,total);loadText.setText(loaded+" / "+total+"\n已用时 "+runtime.loadElapsed()/1000+" 秒");JSONObject p=runtime.uiProfile();syncDetail.setText("平台待同步："+p.optLong("pendingPeople")+"    待回执："+p.optLong("pendingReceipts")+"\n\n平台配置："+(p.optBoolean("platformSynced")?"已同步":"等待同步")+"\n检索库："+(p.optBoolean("sdkReady")?"已加载":"准备中")+"\n\n"+lastState);}
    private void showSyncPage(){
        LinearLayout root=page("人员同步");syncPage=true;status.setText("优先处理删除与失效数据");LinearLayout loading=card(root);label(loading,"本地检索库加载");LinearLayout progress=new LinearLayout(this);progress.setGravity(android.view.Gravity.CENTER_VERTICAL);loadRing=new TerminalProgressView(this);progress.addView(loadRing,new LinearLayout.LayoutParams(dp(120),dp(120)));loadText=text("",19,INK);progress.addView(loadText,new LinearLayout.LayoutParams(0,-2,1));loading.addView(progress);
        LinearLayout changes=card(root);label(changes,"平台增量同步");syncDetail=text("",15,MUTED);changes.addView(syncDetail);updateSync();
        LinearLayout note=card(root);note.setBackground(background(0xffe4f3ff,0));note.addView(text("加载期间可查看记录与维护，完成后可开始核验。同平台换账号保留本地人员库。",14,MUTED));
        action(root,"立即同步 / 重试",()->runtime.retry(),false);action(root,"返回首页",this::showAccount,false);
    }
    private void showMaintenance(){
        LinearLayout root=page("设备维护");maintenancePage=true;status.setText(TerminalMessages.friendly(lastState));JSONObject p=runtime.uiProfile();LinearLayout device=card(root);LinearLayout licensed=iconLabel("check",authorized?"设备已授权":"设备未授权",0xff00b779,32);licensed.setPadding(0,dp(8),0,dp(8));licensed.setBackground(artwork("terminal-result-mint.png"));round(licensed,8);device.addView(licensed);maintenanceConnection=row(device,"平台通信",TerminalMessages.friendly(runtime.communicationStatus()).replace("平台通信：",""));row(device,"当前账号",p.optString("accountName","待同步"));row(device,"所属景区",p.optString("scenicName","待同步"));row(device,"识别模式","RGB活体");
        LinearLayout sync=card(root);label(sync,"数据与同步");TextView faceCount=row(sync,"本地特征","读取中…");TextView pendingCount=row(sync,"待确认记录","读取中…");final int maintenanceVersion=pageVersion;runtime.localRecords((data,error)->{if(destroyed||pageVersion!=maintenanceVersion)return;faceCount.setText(error==null?String.valueOf(data.optLong("localFaces")):"暂不可用");pendingCount.setText(error==null?String.valueOf(data.optLong("pending")):"暂不可用");});row(sync,"同步状态",runtime.ready()?"已完成":"准备中");action(sync,"立即同步 / 查看进度",this::showSyncPage,false);
        LinearLayout audioBox=card(root);Switch voiceSwitch=new Switch(this);voiceSwitch.setText("核验语音播报");voiceSwitch.setTextSize(16);voiceSwitch.setChecked(localVoice());voiceSwitch.setOnCheckedChangeListener((button,enabled)->{getSharedPreferences("terminal_audio",MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();if(!enabled&&speech!=null)speech.stop();});voiceSwitch.setTextColor(INK);voiceSwitch.setGravity(android.view.Gravity.CENTER_VERTICAL);voiceSwitch.setMinHeight(dp(48));audioBox.addView(voiceSwitch,new LinearLayout.LayoutParams(-1,-2));audioBox.addView(text(!speechReady?"中文语音未就绪，可在手机系统设置中检查语音引擎":runtime.voiceAllowed()?"本机可随时静音；不影响核验与记录":"后台已关闭语音，本机开启仍保持静音",12,MUTED));
        LinearLayout menus=card(root);menu(menus,"管理员设置",this::openBaiduSettings);menu(menus,"核验记录与统计",this::showStatistics);menu(menus,"本机核验记录",this::showLocalRecords);menu(menus,"相机权限管理",this::explainPermissions);menu(menus,"记录备份与恢复",this::showBackup);
        action(root,"平台连接设置",this::showPlatformSettings,false);action(root,"重新验证原账号",this::showLogin,false);
        Button logout=action(root,"同步后退出账号",()->{},false);logout.setOnClickListener(v->{logout.setEnabled(false);status.setText("正在确认原账号记录、人员任务和回执…");runtime.exit(error->{if(destroyed)return;logout.setEnabled(true);if(error!=null)status.setText(error);else if(!runtime.hasSession())showLogin();});});TextView note=text("ⓘ 原账号记录确认完成后才能切换",12,MUTED);note.setGravity(android.view.Gravity.CENTER);root.addView(note);
    }
    private void startScanning(){if(camera==null||!runtime.startCamera()){status.setText("请等待设备就绪后开始核验");if(scanPage&&resultView!=null)resultView.setText("设备尚未就绪，请返回查看同步状态");return;}cameraPreview.setVisibility(android.view.View.VISIBLE);if(!camera.start())return;startButton.setText("暂停核验");resultView.setText("请正对摄像头，每次核验后移开");}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request!=200)return;
        boolean resume=startAfterPermission;startAfterPermission=false;
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"相机已授权",Toast.LENGTH_SHORT).show();if(resume&&accountPage)showScan();
        }else{status.setText("相机权限未授权；可继续同步和统计，需要核验时请打开权限管理");}
    }

    private void showBackup(){
        LinearLayout root=page("记录备份与恢复");status.setText("备份由密码加密，请妥善保管文件与密码。");
        LinearLayout info=card(root);label(info,"本机会话记录");TextView help=text("备份当前会话的核验记录及回执。恢复仅支持原设备、原平台、原会话，合并缺失记录，不覆盖已有新记录。\n\n不包含人员特征库、SDK授权、账号密码和算法设置；不用于卸载后恢复或迁移设备。",14,MUTED);help.setLineSpacing(dp(5),1);info.addView(help);
        action(root,"导出加密记录备份",()->chooseBackup(false),true);action(root,"选择备份并恢复记录",()->chooseBackup(true),false);
        TextView note=text("恢复后自动核对平台回执，不重新放行。密码至少12位；忘记密码无法解密。",13,MUTED);note.setPadding(dp(4),dp(12),dp(4),dp(12));root.addView(note);
    }
    private void chooseBackup(boolean restore){
        if(!runtime.hasSession()){status.setText("请先登录原账号后操作");return;}
        if(busy.get()){Toast.makeText(this,"正在处理，请稍候",Toast.LENGTH_SHORT).show();return;}
        Intent picker=new Intent(restore?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType("application/octet-stream");
        if(!restore)picker.putExtra(Intent.EXTRA_TITLE,"yikatong-records-"+System.currentTimeMillis()+".yktbackup");
        try{startActivityForResult(picker,restore?302:301);}catch(android.content.ActivityNotFoundException e){status.setText("系统文件选择器不可用，请检查系统文件应用");}
    }
    private void backupPassword(android.net.Uri uri,boolean restore){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(8),dp(20),0);
        EditText secret=new EditText(this);secret.setHint("请输入至少12位备份密码");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);form.addView(secret);
        EditText confirm=new EditText(this);confirm.setHint("再次输入备份密码");confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);if(!restore)form.addView(confirm);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle(restore?"恢复核验记录":"加密备份记录").setView(form).setPositiveButton(restore?"校验并恢复":"开始备份",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String value=secret.getText().toString();if(value.length()<12){secret.setError("密码至少12位");return;}if(!restore&&!value.equals(confirm.getText().toString())){confirm.setError("两次密码不一致");return;}if(!busy.compareAndSet(false,true))return;
            char[] password=value.toCharArray();secret.setText("");confirm.setText("");dialog.dismiss();status.setText(restore?"正在校验并合并记录，请稍候…":"正在加密备份，请稍候…");runtime.recordBackup(uri,password,restore,savedOrigin(),fingerprint(),error->{busy.set(false);if(destroyed)return;String result=error==null?(restore?"恢复完成，记录将自动核对平台回执":"加密备份已保存，请妥善保管密码"):"操作未完成："+TerminalMessages.friendly(error);new android.app.AlertDialog.Builder(this).setTitle(error==null?"操作完成":"操作未完成").setMessage(result).setPositiveButton("知道了",null).show();status.setText(result);});
        }));dialog.show();
    }
    private void showLocalRecords(){
        LinearLayout root=page("本机记录与维护");int version=pageVersion;
        LinearLayout summaryCard=card(root);label(summaryCard,"本机记录概览");TextView summary=text("正在加载…",15,INK);summary.setLineSpacing(dp(5),1);summaryCard.addView(summary);
        Button refresh=new Button(this);refresh.setText("刷新记录");styleButton(refresh);root.addView(refresh);
        Button retry=new Button(this);retry.setText("重试同步与回执");styleButton(retry);root.addView(retry);retry.setOnClickListener(v->runtime.retry());
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(0,dp(12),0,dp(8));root.addView(rows);
        refresh.setOnClickListener(v->{refresh.setEnabled(false);runtime.localRecords((data,error)->{
            if(destroyed||pageVersion!=version)return;refresh.setEnabled(true);
            if(error!=null){status.setText(error);return;}
            status.setText("本页为本机当前会话记录；首页为景区共享统计。待生效同人同日合并展示，原始回执保留。");
            summary.setText("本机原始记录："+data.optLong("today")+" 条\n通过："+data.optLong("passed")+" 次    拒绝："+data.optLong("denied")+" 次\n待生效："+data.optLong("notEffectivePeople")+" 人    待处理："+data.optLong("awaiting")+" 条"
                +"\n未确认回执："+data.optLong("pending")+" · 本地特征："+data.optLong("localFaces")
                +"\n人员库："+("SHARED".equals(data.optString("libraryMode"))?"同平台共享，换号保留":"旧版会话库")
                +"\nSDK："+(data.optBoolean("sdkReady")?"就绪":"未就绪")+" · APP："+data.optString("appVersion"));
            rows.removeAllViews();label(rows,"最近核验记录");JSONArray items=data.optJSONArray("items");
            if(items==null||items.length()==0)rows.addView(text("暂无本机会话记录",14,MUTED));
            if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null)continue;
                LinearLayout record=card(rows);TextView time=text(item.optString("time")+" · "+(item.optBoolean("confirmed")?"已确认":"待确认"),12,MUTED);record.addView(time);
                TextView detail=text(item.optString("text"),15,item.optBoolean("pass")?0xff009c69:INK);detail.setPadding(0,dp(8),0,0);detail.setLineSpacing(dp(3),1);record.addView(detail);
            }
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
        if(destroyed)return;lastState=value;if(syncPage){status.setText(TerminalMessages.friendly(value));updateSync();}
        if(!hasSession&&authorized&&!baiduSettingsOpen&&(accountPage||scanPage||resultPage||syncPage||maintenancePage)){showLogin();return;}
        if(accountPage){homeStatus(value);JSONObject p=runtime.uiProfile();if(homeChips!=null){homeChips[1].setText(connectionLabel());homeChips[2].setText(p.optBoolean("sdkReady")?"SDK就绪":"SDK准备中");}if(homeAccount!=null)homeAccount.setText(p.optString("scenicName","等待景区同步")+" · "+p.optString("accountName","等待账号同步"));if(startButton!=null){startButton.setText(ready?"开始核验":"查看同步进度");startButton.setEnabled(true);}}
        if(resultPage&&resultReceipt!=null)resultReceipt.setText(receiptText());
        if(scanPage){status.setText(value);if(!ready){if(diagnosticView!=null)diagnosticView.setText(value);if(camera!=null)camera.stop();startButton.setText("开始核验");}startButton.setEnabled(ready);}
    }
    @Override public void diagnostics(String value){if(!destroyed&&scanPage&&diagnosticView!=null)diagnosticView.setText(value);}
    @Override public void communication(String value){if(!destroyed&&communication!=null){communication.setText(TerminalMessages.friendly(value));if(maintenanceConnection!=null)maintenanceConnection.setText(TerminalMessages.friendly(value).replace("平台通信：",""));if(accountPage&&homeChips!=null)homeChips[1].setText(connectionLabel());}}
    private String voiceLabel(){return !runtime.voiceAllowed()?"平台静音":localVoice()?"声音开":"已静音";}
    private String receiptText(){JSONObject out=runtime.outcome();return out.optBoolean("confirmed")?"记录状态：平台已确认":out.optBoolean("persisted")?"记录状态：平台已保存，回执确认中":"记录状态：等待补传或处理";}
    @Override public void result(String value,String voice,boolean open,String type){
        if(destroyed||!scanPage)return;if(value.startsWith("已开始新一轮")){resultView.setText(value);return;}
        if(speechReady&&localVoice()&&runtime.voiceAllowed()&&!voice.isEmpty())speech.speak(voice,TextToSpeech.QUEUE_FLUSH,null,"terminal-result");
        LinearLayout root=page("核验结果",false);resultPage=true;status.setText("");LinearLayout outcome=card(root);outcome.setGravity(android.view.Gravity.CENTER);TerminalResultBadge badge=new TerminalResultBadge(this,open);outcome.addView(badge,new LinearLayout.LayoutParams(dp(150),dp(150)));TextView title=text(TerminalResultType.title(type),28,open?0xff004b31:0xffb64520);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setPadding(0,dp(18),0,dp(8));outcome.addView(title);TextView detail=text(value,17,MUTED);detail.setGravity(android.view.Gravity.CENTER);outcome.addView(detail);
        LinearLayout facts=card(root);SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA);fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));String occurred=runtime.outcome().optString("occurredAt");String when="本机显示 "+fmt.format(new java.util.Date());try{SimpleDateFormat utc=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);utc.setTimeZone(TimeZone.getTimeZone("UTC"));if(!occurred.isEmpty())when=fmt.format(utc.parse(occurred));}catch(Exception ignored){}row(facts,"核验时间",when);row(facts,"所属景区",runtime.uiProfile().optString("scenicName","待同步"));row(facts,"核验方式","人脸核验");resultReceipt=text(receiptText(),14,runtime.outcome().optBoolean("confirmed")?0xff00ae71:MUTED);resultReceipt.setPadding(0,dp(8),0,0);facts.addView(resultReceipt);
        LinearLayout art=card(root);art.setBackground(artwork("terminal-result-mint.png"));round(art,12);art.setGravity(android.view.Gravity.CENTER);art.setMinimumHeight(dp(130));TextView message=text(open?"祝您游览愉快":"请核对提示后重试",23,0xff064e55);message.setTypeface(null,android.graphics.Typeface.BOLD);art.addView(message);TextView wish=text(open?"一山一水皆美景，期待下次再见":"本次未放行，可联系现场工作人员",13,0xff397878);wish.setPadding(0,dp(10),0,0);art.addView(wish);
        pair(root,"返回首页",this::showAccount,"继续核验",()->{if(runtime.ready())showScan();else showSyncPage();});
    }
    @Override public void onBackPressed(){if(scanPage||resultPage||syncPage||maintenancePage){showAccount();return;}if(runtime.hasSession()){showMaintenance();return;}super.onBackPressed();}
    @Override protected void onResume() { super.onResume();foreground=true;if(refreshHomeStatistics!=null){main.removeCallbacks(refreshHomeStatistics);refreshHomeStatistics.run();}if(accountPage&&camera!=null&&cameraAllowed())camera.preview(); }
    @Override protected void onPause() {
        foreground=false;if(refreshHomeStatistics!=null)main.removeCallbacks(refreshHomeStatistics);
        if(camera!=null)camera.stop();runtime.pauseCamera();if(startButton!=null)startButton.setText("开始核验");super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed=true;if(refreshHomeStatistics!=null)main.removeCallbacks(refreshHomeStatistics);adminWorker.shutdownNow();pageVersion++;main.removeCallbacks(timeout);runtime.detach(this);if(camera!=null)camera.stop();if(speech!=null){speech.stop();speech.shutdown();}super.onDestroy();
    }
}

