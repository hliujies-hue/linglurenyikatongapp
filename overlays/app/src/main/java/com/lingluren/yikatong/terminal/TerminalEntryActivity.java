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
public final class TerminalEntryActivity extends Activity implements TerminalRuntime.Listener, TerminalLicenseManager.Listener {
    private final ExecutorService adminWorker=Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private TerminalRuntime runtime;
    private TextView brandHeading,brandSubtitle,brandSupport,appNotice,deviceNameView;
    private ImageView brandIcon;
    private LinearLayout brandBanner;
    private TerminalBrandImages brandImages;
    private static final class BrandSlot {
        final boolean icon;
        String requested="",displayed="";
        int attempts,request;
        boolean loading;
        java.util.concurrent.Future<?> pending;
        Runnable retry;
        BrandSlot(boolean icon){this.icon=icon;}
    }
    private final BrandSlot iconSlot=new BrandSlot(true),backgroundSlot=new BrandSlot(false);
    private void applySupportConfig(){
        if(brandSupport==null)return;
        JSONObject config=runtime.appConfig();
        String label=config.optString("supportText","河北领路人科技有限公司 提供技术支持");
        String raw=config.optString("supportUrl","https://linglu.ren");
        android.net.Uri uri=android.net.Uri.parse(raw);
        boolean valid="https".equals(uri.getScheme())&&uri.getHost()!=null&&!uri.getHost().isEmpty()&&uri.getUserInfo()==null&&!raw.matches(".*[\\s\\p{Cntrl}].*");
        brandSupport.setText(label);brandSupport.setVisibility(label.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);
        brandSupport.setContentDescription(label+(valid?"，点击打开网址":""));
        brandSupport.setOnClickListener(valid?v->{try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(android.content.ActivityNotFoundException|SecurityException e){Toast.makeText(this,"无法打开浏览器，请联系管理员检查跳转网址",Toast.LENGTH_LONG).show();}}:null);
        brandSupport.setClickable(valid);
    }
    private void applyAppConfig(){
        JSONObject c=runtime.appConfig();
        if(brandHeading!=null)brandHeading.setText(c.optString("appName","一卡通核验端"));
        if(brandSubtitle!=null)brandSubtitle.setText(c.optString("subtitle","景区年卡核验"));
        applySupportConfig();
        if(deviceNameView!=null)deviceNameView.setText(runtime.deviceName());
        String notice=runtime.appBlockedReason();String announcement=c.optString("announcement");if(!announcement.isEmpty())notice+=(notice.isEmpty()?"":"\n")+announcement;
        try{if(!c.optString("latestVersion").isEmpty()&&TerminalAppPolicy.older(TerminalProtocol.VERSION,c.optString("latestVersion")))notice+=(notice.isEmpty()?"":"\n")+"发现新版本 "+c.optString("latestVersion")+"，点击查看更新";}catch(Exception ignored){}
        if(appNotice!=null){appNotice.setText(notice);appNotice.setVisibility(notice.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);}
        if(brandIcon!=null)requestBrandImage(iconSlot,c.optString("iconUrl"));
        if(brandBanner!=null)requestBrandImage(backgroundSlot,c.optString("backgroundUrl"));
    }
    private void cancelBrandImage(BrandSlot slot){
        slot.request++;
        if(slot.pending!=null){slot.pending.cancel(true);slot.pending=null;}
        if(slot.retry!=null){main.removeCallbacks(slot.retry);slot.retry=null;}
        slot.loading=false;
    }
    private void resetBrandImages(){
        for(BrandSlot slot:new BrandSlot[]{iconSlot,backgroundSlot}){cancelBrandImage(slot);slot.requested="";slot.displayed="";slot.attempts=0;}
        if(brandImages!=null)brandImages.cancelQueued();
    }
    private void retryBrandImages(){
        for(BrandSlot slot:new BrandSlot[]{iconSlot,backgroundSlot}){
            if(slot.loading||slot.requested.isEmpty()||slot.requested.equals(slot.displayed))continue;
            cancelBrandImage(slot);slot.attempts=0;loadBrandImage(slot);
        }
    }
    private void requestBrandImage(BrandSlot slot,String url){
        if(!url.equals(slot.requested)){
            cancelBrandImage(slot);slot.requested=url;slot.attempts=0;
            // Keep the previously displayed valid image until the replacement is decoded.
            if(url.isEmpty()){
                slot.displayed="";
                if(slot.icon&&brandIcon!=null){brandIcon.setImageDrawable(null);brandIcon.setVisibility(android.view.View.GONE);}
                else if(brandBanner!=null)brandBanner.setBackground(artwork());
                return;
            }
        }
        if(!url.isEmpty()&&!url.equals(slot.displayed)&&!slot.loading&&slot.retry==null&&slot.attempts<4)loadBrandImage(slot);
    }
    private void applyBrandImage(BrandSlot slot,android.graphics.Bitmap bitmap){
        slot.displayed=slot.requested;slot.attempts=0;
        if(slot.icon&&brandIcon!=null){brandIcon.setImageBitmap(bitmap);brandIcon.setVisibility(android.view.View.VISIBLE);}
        else if(brandBanner!=null)brandBanner.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(),bitmap));
    }
    private void loadBrandImage(BrandSlot slot){
        if(destroyed||brandImages==null||slot.requested.isEmpty())return;
        android.graphics.Bitmap cached=brandImages.cached(slot.requested,slot.icon);
        if(cached!=null){applyBrandImage(slot,cached);return;}
        final int generation=pageVersion,request=++slot.request;final String url=slot.requested;
        slot.loading=true;slot.attempts++;
        try{
            slot.pending=brandImages.load(url,slot.icon,(bitmap,error)->main.post(()->{
                if(destroyed||generation!=pageVersion||request!=slot.request||!url.equals(slot.requested))return;
                slot.loading=false;slot.pending=null;
                if(error==null){applyBrandImage(slot,bitmap);return;}
                brandImageFailed(slot,error);
            }));
        }catch(java.util.concurrent.RejectedExecutionException error){slot.loading=false;brandImageFailed(slot,error);}
    }
    private void brandImageFailed(BrandSlot slot,Exception error){
        String detail=error instanceof java.util.concurrent.RejectedExecutionException?"图片队列正在释放上个页面的请求":TerminalMessages.friendly(error.getMessage());
        if(detail==null||detail.isEmpty())detail="未收到可用图片，请检查网络和后台图片地址";
        if(slot.attempts<4){
            long delay=slot.attempts==1?5000:slot.attempts==2?15000:45000;
            final int generation=pageVersion,request=slot.request;
            slot.retry=()->{slot.retry=null;if(!destroyed&&generation==pageVersion&&request==slot.request)loadBrandImage(slot);};
            main.postDelayed(slot.retry,delay);
            if(slot.attempts==1)Toast.makeText(this,"品牌"+(slot.icon?"图标":"背景")+"未更新："+detail+"。保留原显示，5秒后重试。",Toast.LENGTH_LONG).show();
        }else Toast.makeText(this,"品牌"+(slot.icon?"图标":"背景")+"连续4次加载失败："+detail+"。网络恢复后点击顶部品牌区域重试。",Toast.LENGTH_LONG).show();
    }
    private TerminalAppUpdater appUpdater;
    private void showAppUpdate(){
        JSONObject c=runtime.appConfig();String latest=c.optString("latestVersion");
        android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(this).setTitle("APP版本与更新")
            .setMessage("当前版本："+TerminalProtocol.VERSION+"\n平台最新版本："+(latest.isEmpty()?"未发布":latest)+"\n"+runtime.appBlockedReason()+"\n"+c.optString("updateNotes")+"\n更新将在应用内下载并校验，随后由系统确认覆盖安装。")
            .setNegativeButton("关闭",null);
        if(!c.optString("updateUrl").isEmpty())dialog.setPositiveButton("下载并更新",(d,w)->{
            if(appUpdater==null)appUpdater=new TerminalAppUpdater(this);
            appUpdater.start(c);
        });
        dialog.show();
    }
    private void checkAppUpdate(){runtime.checkAppConfig(error->{if(destroyed)return;if(error!=null){new android.app.AlertDialog.Builder(this).setTitle("检查更新失败").setMessage(error).setPositiveButton("知道了",null).show();return;}applyAppConfig();showAppUpdate();});}

    private TerminalCamera camera;
    private TextToSpeech speech;
    private boolean speechReady, accountPage, scanPage, resultPage;
    private int speechGeneration,speechSequence;
    private String speechStatus="正在初始化中文语音",speechEngine="系统默认",speechUtterance="";
    private Runnable speechTimeout;
    private final android.media.AudioManager.OnAudioFocusChangeListener speechFocus=change->{if(change==android.media.AudioManager.AUDIOFOCUS_LOSS||change==android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)main.post(this::stopSpeech);};
    private TextView voiceStatusView;
    private String pendingDiagnostic="",lastCameraError="未记录到相机异常";

    private final int BLUE=0xff1677ff, INK=0xff1f2329, MUTED=0xff646a73;
    private final java.util.Map<String,android.graphics.Bitmap> artworkCache=new java.util.HashMap<>();
    private boolean localVoice(){return getSharedPreferences("terminal_audio",MODE_PRIVATE).getBoolean("enabled",true);}
    private TextView loadText, syncDetail, homeAccount,resultReceipt;
    private TextView[] syncValues;
    private TextView[] homeChips;
    private Runnable refreshHomeStatistics,refreshDiagnostics;
    private TerminalProgressView loadRing;
    private boolean startAfterPermission;
    private Button startButton;
    private enum ScanState { PAUSED, STARTING, RUNNING, UNAVAILABLE, ERROR }
    private ScanState scanState=ScanState.PAUSED;
    private Runnable scanStartupTimeout;
    private TextView resultView,diagnosticView;
    private TextureView cameraPreview;
    private int pageVersion;
    private String lastState="正在恢复设备状态";
    private String fingerprint() {
        String id=Settings.Secure.getString(getContentResolver(),Settings.Secure.ANDROID_ID);
        if(id==null || id.length()<8) throw new IllegalStateException("设备指纹读取失败，请联系平台人员");
        return getPackageName()+":"+id;
    }
    // Same UTF-8 SHA256 as the platform fingerprintHash; never read or activate the SDK.
    private String deviceIdentitySummary() {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder();
            for(int i=0;i<8;i++)result.append(String.format(java.util.Locale.ROOT,"%02x",digest[i]&255));
            return result.toString();
        } catch(Exception e) { return "读取失败，请检查系统设备标识"; }
    }
    private boolean destroyed, foreground;
    private boolean baiduSettingsOpen;

    private void openBaiduSettings(){
        if(baiduSettingsOpen||busy.get()){Toast.makeText(this,"正在处理，请稍候",Toast.LENGTH_SHORT).show();return;}
        if(camera!=null)camera.stop();runtime.pauseCamera();
        final EditText password=new EditText(this);password.setSingleLine();password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setHint("请输入32位一次性维护码");
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(12),dp(24),0);box.addView(password);
        android.widget.CheckBox globalPassword=new android.widget.CheckBox(this);globalPassword.setText("使用全平台维护密码（可重复使用）");box.addView(globalPassword);globalPassword.setOnCheckedChangeListener((button,checked)->{password.setText("");password.setHint(checked?"请输入全平台维护密码":"请输入32位一次性维护码");});
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("管理员设置").setMessage("后台设备列表可签发本机一次性维护码，10分钟内有效；每次进入都需联网验证。全平台维护密码保留兼容入口。").setView(box).setPositiveButton("验证并进入",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            final boolean useGlobal=globalPassword.isChecked();final String secret=password.getText().toString();if(secret.isEmpty()){password.setError(useGlobal?"请输入全平台维护密码":"请输入本机一次性维护码");return;}
            dialog.getButton(-1).setEnabled(false);dialog.getButton(-1).setText("正在验证…");
            adminWorker.execute(()->{String error=null;try{
                JSONObject reply=new PlatformClient(savedOrigin()).request("/api/terminal/admin-settings/unlock",new JSONObject().put(useGlobal?"password":"code",secret).put("fingerprint",fingerprint()));
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
                Toast.makeText(this,"算法参数只影响本机，保存并返回后手动开始核验",Toast.LENGTH_LONG).show();
                startActivityForResult(new Intent().setClassName(this,"com.example.settinglibrary.SettingActivity"),201);
            }catch(RuntimeException e){TerminalApplication.lock();baiduSettingsOpen=false;runtime.endSettings();status.setText("算法设置无法打开：当前安装包缺少设置页面，请联系管理员覆盖安装完整版本");}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==303){
            String snapshot=pendingDiagnostic;pendingDiagnostic="";
            if(result!=RESULT_OK)return;
            if(data==null||data.getData()==null||snapshot.isEmpty()){Toast.makeText(this,"诊断快照已失效，请重新导出",Toast.LENGTH_LONG).show();return;}
            android.net.Uri destination=data.getData();
            adminWorker.execute(()->{
                String message;
                try(java.io.OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
                    if(out==null)throw new java.io.IOException("No output stream");
                    out.write(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();message="诊断快照已保存";
                }catch(Exception e){message="诊断导出失败，请检查所选位置的写入权限与剩余空间";}
                final String feedback=message;main.post(()->{if(!destroyed)Toast.makeText(this,feedback,Toast.LENGTH_LONG).show();});
            });return;
        }
        if((request==301||request==302)&&result==RESULT_OK&&data!=null&&data.getData()!=null){backupPassword(data.getData(),request==302);return;}
        if(request==401||request==402){initSpeech();return;}
        if(request==201){TerminalApplication.lock();baiduSettingsOpen=false;runtime.endSettings();if(runtime.hasSession())showAccount();}
    }
    private TextView status,communication,maintenanceConnection;
    private Button activate,loginButton;
    private TerminalLicenseManager license;
    private boolean activationPage,loginPage,loginCompleted;
    private String activationInput="";
    private volatile boolean authorized;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        brandImages=new TerminalBrandImages(getApplicationContext());
        runtime=TerminalRuntime.get(this);runtime.attach(this);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);initSpeech();
        PreferencesUtil.initPrefs(getApplicationContext());
        license=TerminalLicenseManager.get(this);authorized=license.state().authorized();license.attach(this);
        try { runtime.configureConnection(savedOrigin(),fingerprint()); }
        catch(IllegalArgumentException|IllegalStateException error) { showPlatformSettings();status.setText(error.getMessage());return; }
        if(authorized&&runtime.hasSession())showAccount();else showLogin();
        // Keep Demo cache keys so its model/recognition modules can reuse the same authorization.
        String saved = license.savedCode();
        if (!saved.isEmpty()&&!runtime.signingIn()&&license.state().phase.equals("IDLE")) verify(saved);
        if(!getSharedPreferences("terminal_permissions",MODE_PRIVATE).getBoolean("explained",false))main.post(this::explainPermissions);
    }
    private boolean compact(){return getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.3f;}
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(16));
        if(border != 0) shape.setStroke(dp(1),border); return shape;
    }
    private android.graphics.drawable.Drawable artwork(){return new TerminalHeaderDrawable();}
    private android.graphics.drawable.Drawable artwork(String name){
        try{android.graphics.Bitmap bmp=artworkCache.get(name);if(bmp==null)try(java.io.InputStream in=getAssets().open(name)){android.graphics.BitmapFactory.Options opt=new android.graphics.BitmapFactory.Options();opt.inSampleSize=2;bmp=android.graphics.BitmapFactory.decodeStream(in,null,opt);artworkCache.put(name,bmp);}return new TerminalLandscapeDrawable(bmp);}catch(Exception e){return new TerminalHeaderDrawable();}
    }
    private void round(android.view.View v,int radius){v.setClipToOutline(true);v.setOutlineProvider(new android.view.ViewOutlineProvider(){public void getOutline(android.view.View view,android.graphics.Outline out){out.setRoundRect(0,0,view.getWidth(),view.getHeight(),dp(radius));}});}
    private LinearLayout iconLabel(String icon,String title,int color,int size){LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER);row.addView(new TerminalIconView(this,icon,color),new LinearLayout.LayoutParams(dp(size),dp(size)));TextView label=text(title,13,INK);label.setPadding(dp(5),0,0,0);row.addView(label);return row;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size<=12?12:size==13?14:size);t.setTextColor(color);t.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));t.setIncludeFontPadding(false);t.setLineSpacing(dp(4),1);if(Build.VERSION.SDK_INT>=29){t.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);}if(Build.VERSION.SDK_INT>=23){t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);}return t;}
    private LinearLayout card(LinearLayout parent){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(20),dp(20),dp(20));box.setBackground(background(Color.WHITE,0xffedf0f3));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(16);parent.addView(box,lp);return box;}
    private void label(LinearLayout box,String value){TextView t=text(value,18,INK);t.setTypeface(null,android.graphics.Typeface.BOLD);t.setPadding(0,0,0,dp(12));box.addView(t);}
    private Button action(LinearLayout box,String value,Runnable click,boolean primary){Button button=new Button(this);button.setText(value);styleButton(button);if(!primary){button.setTextColor(BLUE);button.setBackground(background(Color.WHITE,0xffc6dcff));}box.addView(button);button.setOnClickListener(v->click.run());return button;}
    private void menu(LinearLayout box,String title,Runnable click){
        String icon=title.contains("语音")?"voice":title.contains("版本")?"retry":title.contains("记录")||title.contains("备份")?"record":title.contains("统计")?"chart":title.contains("连接")?"wifi":"settings";
        LinearLayout link=new LinearLayout(this);link.setGravity(android.view.Gravity.CENTER_VERTICAL);link.setPadding(0,dp(16),0,dp(16));
        link.addView(new TerminalIconView(this,icon,BLUE),new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView caption=text(title,15,INK);caption.setPadding(dp(12),0,dp(6),0);link.addView(caption,new LinearLayout.LayoutParams(0,-2,1));link.addView(text("›",22,MUTED));
        box.addView(link,new LinearLayout.LayoutParams(-1,-2));link.setOnClickListener(v->click.run());link.setContentDescription(title);link.setBackgroundResource(android.R.drawable.list_selector_background);
        android.view.View line=new android.view.View(this);line.setBackgroundColor(0xffedf2f8);box.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void recordRow(LinearLayout parent,JSONObject item){
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(0,dp(12),0,dp(12));
        TerminalIconView avatar=new TerminalIconView(this,"person",0xff94a7be);avatar.setBackground(background(0xffedf3fa,0));row.addView(avatar,new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout detail=new LinearLayout(this);detail.setOrientation(LinearLayout.VERTICAL);detail.setPadding(dp(12),0,dp(8),0);row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=text(item.optString("title","等待平台处理"),15,INK);title.setTypeface(null,android.graphics.Typeface.BOLD);detail.addView(title);TextView time=text(item.optString("time"),12,MUTED);time.setPadding(0,dp(5),0,0);detail.addView(time);
        TextView badge=text(item.optBoolean("pass")?"通过":item.optString("title","待处理"),12,item.optBoolean("pass")?0xff009c69:0xffb27027);badge.setPadding(dp(8),dp(6),dp(8),dp(6));badge.setBackground(background(item.optBoolean("pass")?0xffeaf8f1:0xfffff4e8,0));row.addView(badge);
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));TextView receipt=text(item.optBoolean("confirmed")?"平台已确认  ›":"待确认 · 自动补传  ›",12,MUTED);receipt.setPadding(dp(52),0,0,dp(12));parent.addView(receipt);
        Runnable open=()->new android.app.AlertDialog.Builder(this).setTitle(item.optString("title","等待平台处理")).setMessage(item.optString("time")+"\n"+item.optString("text")+"\n"+(item.optBoolean("confirmed")?"平台已确认":"待确认 · 将自动补传")).setPositiveButton("关闭",null).show();row.setOnClickListener(v->open.run());receipt.setOnClickListener(v->open.run());
        android.view.View line=new android.view.View(this);line.setBackgroundColor(0xffedf2f8);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void pair(LinearLayout root,String first,Runnable a,String second,Runnable b){
        if(compact()){action(root,second,b,true);action(root,first,a,false);return;}
        LinearLayout bar=new LinearLayout(this);Button left=new Button(this),right=new Button(this);left.setText(first);right.setText(second);styleButton(left);styleButton(right);left.setTextColor(BLUE);left.setBackground(background(Color.WHITE,BLUE));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(0,dp(10),dp(5),0);bar.addView(left,lp);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1);rp.setMargins(dp(5),dp(10),0,0);bar.addView(right,rp);root.addView(bar,new LinearLayout.LayoutParams(-1,-2));left.setOnClickListener(v->a.run());right.setOnClickListener(v->b.run());
    }
    private TextView row(LinearLayout box,String key,String value){
        LinearLayout line=new LinearLayout(this);line.setGravity(android.view.Gravity.CENTER_VERTICAL);line.setPadding(0,dp(12),0,dp(12));TextView title=text(key,14,MUTED),right=text(TerminalMessages.friendly(value),15,INK);
        if(compact()){line.setOrientation(LinearLayout.VERTICAL);line.addView(title);right.setPadding(0,dp(5),0,0);line.addView(right,new LinearLayout.LayoutParams(-1,-2));}
        else{line.addView(title,new LinearLayout.LayoutParams(dp(88),-2));right.setGravity(android.view.Gravity.END);line.addView(right,new LinearLayout.LayoutParams(0,-2,1));}
        box.addView(line,new LinearLayout.LayoutParams(-1,-2));return right;
    }
    private String connectionLabel(){return runtime.communicationStatus().contains("：在线")?"平台在线":"连接待确认";}
    private void homeStatus(String value){
        boolean problem=value.contains("失败")||value.contains("异常")||value.contains("无法")||value.contains("超时")||value.contains("未连接");
        status.setText(runtime.ready()?"":problem?TerminalMessages.friendly(value)+"\n点击查看同步进度与重试":"正在准备核验 · 查看同步进度");status.setVisibility(status.length()==0?android.view.View.GONE:android.view.View.VISIBLE);status.setTextColor(problem?0xffba522d:MUTED);status.setOnClickListener(v->showSyncPage());
    }
    private LinearLayout page(String title){return page(title,true);}
    private LinearLayout page(String title,boolean navigation){
        if(camera!=null){camera.stop();camera=null;}else if(runtime!=null)runtime.pauseCamera();
        if(refreshHomeStatistics!=null){main.removeCallbacks(refreshHomeStatistics);refreshHomeStatistics=null;}
        if(refreshDiagnostics!=null){main.removeCallbacks(refreshDiagnostics);refreshDiagnostics=null;}
        resetBrandImages();if(scanStartupTimeout!=null){main.removeCallbacks(scanStartupTimeout);scanStartupTimeout=null;}scanState=ScanState.PAUSED;startButton=null;
        brandHeading=null;brandSubtitle=null;brandSupport=null;appNotice=null;deviceNameView=null;brandIcon=null;brandBanner=null;voiceStatusView=null;maintenanceConnection=null;loginPage=false;loginCompleted=false;loginButton=null;activationPage=false;accountPage=false;scanPage=false;resultPage=false;syncPage=false;maintenancePage=false;homeAccount=null;homeChips=null;resultReceipt=null;loadRing=null;loadText=null;syncDetail=null;pageVersion++;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);getWindow().setStatusBarColor(0xff2f8fff);getWindow().setNavigationBarColor(Color.WHITE);
        boolean login="景区工作人员登录".equals(title),home="一卡通核验端".equals(title);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(0xfff5f6f8);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);scroll.addView(outer);
        LinearLayout banner=new LinearLayout(this);banner.setOrientation(LinearLayout.VERTICAL);banner.setGravity(login?android.view.Gravity.CENTER:android.view.Gravity.CENTER_VERTICAL);banner.setPadding(dp(20),dp(24),dp(20),dp(24));banner.setBackground(artwork());banner.setMinimumHeight(dp(login?184:home?104:88));outer.addView(banner,new LinearLayout.LayoutParams(-1,-2));
        if(home||login){brandBanner=banner;banner.setOnClickListener(v->retryBrandImages());banner.setContentDescription("APP品牌区域，图片未加载时点击重试");}
        LinearLayout top=new LinearLayout(this);top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if(!home&&!login){TerminalIconView back=new TerminalIconView(this,"back",Color.WHITE);top.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));back.setOnClickListener(v->{if(runtime.hasSession())showAccount();else showLogin();});}
        if(home||login){brandIcon=new ImageView(this);brandIcon.setContentDescription("APP图标");brandIcon.setVisibility(android.view.View.GONE);top.addView(brandIcon,new LinearLayout.LayoutParams(dp(42),dp(42)));}
        TextView heading=text(login?"一卡通核验端":title,login?28:home?23:21,Color.WHITE);if(home||login)brandHeading=heading;heading.setTypeface(null,android.graphics.Typeface.BOLD);if(!home)heading.setGravity(android.view.Gravity.CENTER);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        if(!home&&!login){top.addView(new android.view.View(this),new LinearLayout.LayoutParams(dp(30),1));}banner.addView(top,new LinearLayout.LayoutParams(-1,-2));
        if(home||login){TextView subtitle=text(login?"景区工作人员登录":"景区年卡核验",15,Color.WHITE);if(home)brandSubtitle=subtitle;subtitle.setGravity(login?android.view.Gravity.CENTER:android.view.Gravity.LEFT);subtitle.setPadding(0,dp(8),0,0);banner.addView(subtitle);}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(20),dp(16),dp(16));root.setBackgroundColor(0xfff5f6f8);LinearLayout.LayoutParams content=new LinearLayout.LayoutParams(-1,-2);content.topMargin=0;outer.addView(root,content);
        communication=text(runtime.communicationStatus(),12,MUTED);communication.setPadding(dp(4),0,0,dp(6));root.addView(communication);communication.setVisibility(android.view.View.GONE);
        appNotice=text("",14,0xffa33500);appNotice.setPadding(dp(16),dp(14),dp(16),dp(14));appNotice.setBackground(background(0xfffff5e8,0));appNotice.setOnClickListener(v->showAppUpdate());root.addView(appNotice);
        status=text("",13,MUTED);status.setPadding(dp(4),0,dp(4),dp(16));root.addView(status);
        if(runtime.hasSession()&&navigation){LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(Color.WHITE);String[] names={"核验","记录","维护"},icons={"home","record","settings"};int selected=home?0:title.contains("记录")?1:2;for(int i=0;i<3;i++){final int index=i;LinearLayout tab=new LinearLayout(this);tab.setOrientation(LinearLayout.VERTICAL);tab.setGravity(android.view.Gravity.CENTER);int tint=i==selected?BLUE:MUTED;tab.addView(new TerminalIconView(this,icons[i],tint),new LinearLayout.LayoutParams(dp(23),dp(23)));TextView caption=text(names[i],11,tint);caption.setPadding(0,dp(5),0,0);caption.setGravity(android.view.Gravity.CENTER);tab.addView(caption,new LinearLayout.LayoutParams(-1,-2));tab.setMinimumHeight(dp(59));tab.setPadding(0,dp(8),0,dp(8));tab.setContentDescription(names[i]);nav.addView(tab,new LinearLayout.LayoutParams(0,-2,1));tab.setOnClickListener(v->{if(index==0)showAccount();else if(index==1)showLocalRecords();else showMaintenance();});}shell.addView(nav,new LinearLayout.LayoutParams(-1,-2));}
        addSupport(shell);
        setContentView(shell);applyAppConfig();return root;
    }
    private void addSupport(LinearLayout shell){
        TextView support=text(runtime.appConfig().optString("supportText","河北领路人科技有限公司 提供技术支持"),11,MUTED);brandSupport=support;support.setGravity(android.view.Gravity.CENTER);support.setPadding(dp(8),dp(9),dp(8),dp(9));support.setMinHeight(dp(40));support.setBackgroundColor(Color.WHITE);applySupportConfig();shell.addView(support,new LinearLayout.LayoutParams(-1,-2));
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
        field.setBackground(background(0xfff7f8fa,0xffe3e6eb));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);field.setMinHeight(dp(52));params.bottomMargin=dp(14);field.setLayoutParams(params);
    }
    private void styleButton(Button button) {
        button.setTextColor(Color.WHITE);button.setTextSize(16);button.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));button.setAllCaps(false);button.setPadding(dp(12),dp(12),dp(12),dp(12));button.setMinWidth(0);button.setMinimumWidth(0);button.setIncludeFontPadding(false);
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},background(Color.rgb(166,187,215),0));
        states.addState(new int[]{},background(Color.rgb(25,117,255),0));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000),states,null));button.setStateListAnimator(null);button.setMinHeight(dp(52));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(10);button.setLayoutParams(params);
    }
    private String savedOrigin() {
        return getSharedPreferences("terminal_connection",MODE_PRIVATE).getString("platformOrigin","https://yktapi.qiliai.com");
    }
    private void connectionButton(LinearLayout root) {
        Button settings=new Button(this);settings.setText("平台连接设置");styleButton(settings);root.addView(settings);
        settings.setOnClickListener(v->{if(busy.get()||(license!=null&&license.state().busy())){status.setText("设备授权处理中，请完成后再修改平台地址");return;}showPlatformSettings();});
    }
    private void showPlatformSettings() {
        if(runtime.signingIn()||(license!=null&&license.state().busy())){Toast.makeText(this,"正在登录或校验算法授权，请完成后再修改平台地址",Toast.LENGTH_LONG).show();return;}
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
                showLogin();
            }catch(IllegalArgumentException|IllegalStateException error){status.setText(error.getMessage());}
        });
        Button back=new Button(this);back.setText("返回");styleButton(back);root.addView(back);back.setOnClickListener(v->{if(authorized&&runtime.hasSession())showAccount();else showLogin();});
    }
    private void showActivation() {
        if(runtime.signingIn()){Toast.makeText(this,"正在恢复算法授权并登录，请等待当前结果",Toast.LENGTH_LONG).show();return;}
        LinearLayout root=page("设备激活");activationPage=true;deviceNameView=row(root,"设备名称",runtime.deviceName());
        action(root,"已激活设备：登录恢复授权",this::showLogin,false);
        EditText code=new EditText(this);styleInput(code,"首次使用请输入本机算法授权码");
        code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);code.setText(activationInput);root.addView(code);
        activate=new Button(this);activate.setText("激活 / 重试");styleButton(activate);root.addView(activate);
        activate.setOnClickListener(v->{activationInput=code.getText().toString();verify(activationInput.trim().isEmpty()?license.savedCode():activationInput);});
        root.addView(text("首次激活成功后会保存本机算法授权码并尝试备份到平台。覆盖更新自动校验；卸载重装后，登录原景区子账号可自动恢复已备份授权。",13,MUTED));
        if(!license.savedCode().isEmpty())root.addView(text("本机已有保存的算法授权码，可直接点击激活 / 重试，无需再次输入。",13,MUTED));
        action(root,"激活诊断 / 导出信息",()->{activationInput=code.getText().toString();showDiagnostics();},false);
        connectionButton(root);renderActivation(license.state());
    }
    private void verify(String code) {
        try{if(!license.start(code))renderActivation(license.state());}
        catch(IllegalArgumentException e){status.setText(e.getMessage());}
    }
    private void renderActivation(TerminalActivationGate.State state){
        if(!activationPage)return;
        activate.setEnabled(!state.busy());
        String message;
        switch(state.phase){
            case "PREPARING":message="正在初始化算法设备指纹（尝试 "+state.attempts+" / "+TerminalActivationGate.MAX_FINGERPRINT_ATTEMPTS+"），请稍候";break;
            case "VERIFYING":message="已取得本机设备指纹，正在校验算法授权…";break;
            case "SLOW":message="算法授权超过60秒尚未回调，后台仍在等待；为避免重复激活，暂不再次提交。可导出诊断；仍无响应时在系统应用信息中强行停止再重开，不要清除数据。";break;
            case "FAILED":message=TerminalActivationGate.describe(state.error);break;
            case "AUTHORIZED":message="设备已授权，请继续登录";break;
            default:message="首次激活请填写算法授权码；已激活设备请返回登录自动恢复授权";
        }
        status.setText(message);
    }
    @Override public void changed(TerminalActivationGate.State state){
        if(destroyed)return;authorized=state.authorized();renderActivation(state);
        if(loginPage){
            if(loginButton!=null)loginButton.setEnabled(!state.busy()&&!runtime.signingIn());
            if(state.phase.equals("FAILED"))status.setText(TerminalActivationGate.describe(state.error));
            else if(state.busy())status.setText(state.phase.equals("SLOW")?"算法授权服务超过60秒尚未回调，仍在等待；不要重复提交，可在设备诊断中查看进度":runtime.signingIn()?"已取回本机算法授权码，正在自动校验…":"正在自动校验已保存的本机算法授权…");
            else if(authorized)status.setText(runtime.signingIn()?"算法授权已恢复，正在完成账号登录…":"本机算法授权已就绪 · "+connectionLabel());
            if(authorized&&runtime.hasSession()&&!runtime.signingIn()){loginCompleted=true;if(foreground){showAccount();return;}}
        }
        if(authorized&&activationPage&&foreground){if(runtime.hasSession())showAccount();else showLogin();}
    }
    private void showLogin(){
        LinearLayout root=page("景区工作人员登录");loginPage=true;LinearLayout identity=card(root);deviceNameView=row(identity,"本机设备",runtime.deviceName());identity.addView(iconLabel("sdk",(authorized?"已授权":"授权待恢复")+" · "+connectionLabel(),BLUE,20));identity.setOnClickListener(v->showDiagnostics());int version=pageVersion;
        status.setText(runtime.signingIn()?"正在恢复算法授权并登录，请稍候":authorized?"本机算法授权已就绪 · "+connectionLabel():"已激活的设备可登录恢复算法授权，无需重复输入授权码");status.setGravity(android.view.Gravity.CENTER);LinearLayout form=card(root);label(form,"工作人员登录");
        form.addView(text("工作人员账号",15,INK));EditText user=new EditText(this);styleInput(user,"请输入工作人员账号");form.addView(user);
        form.addView(text("密码",15,INK));EditText password=new EditText(this);styleInput(password,"请输入密码");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setPadding(dp(14),dp(12),dp(56),dp(12));FrameLayout passwordBox=new FrameLayout(this);LinearLayout.LayoutParams passwordLp=new LinearLayout.LayoutParams(-1,-2);passwordBox.setMinimumHeight(dp(52));passwordLp.bottomMargin=dp(14);form.addView(passwordBox,passwordLp);passwordBox.addView(password,new FrameLayout.LayoutParams(-1,-2));TextView visible=text("显示",12,MUTED);visible.setGravity(android.view.Gravity.CENTER);visible.setContentDescription("显示或隐藏密码");passwordBox.addView(visible,new FrameLayout.LayoutParams(dp(52),-1,android.view.Gravity.RIGHT));visible.setOnClickListener(v->{boolean hidden=password.getTransformationMethod()!=null;password.setTransformationMethod(hidden?null:PasswordTransformationMethod.getInstance());visible.setText(hidden?"隐藏":"显示");password.setSelection(password.length());});
        Button login=new Button(this);loginButton=login;login.setText("登录");styleButton(login);form.addView(login);login.setEnabled(!runtime.signingIn()&&!license.state().busy());TextView hint=text("登录后自动恢复本机已备份的算法授权，并获取所属景区与核验规则",13,MUTED);hint.setPadding(0,dp(14),0,0);form.addView(hint);
        login.setOnClickListener(v->{String name=user.getText().toString().trim(),secret=password.getText().toString();if(name.isEmpty()||secret.isEmpty()){status.setText("请输入账号和密码");return;}login.setEnabled(false);status.setText("正在验证账号…");runtime.login(savedOrigin(),fingerprint(),name,secret,error->{if(destroyed||version!=pageVersion)return;if(error!=null){login.setEnabled(true);status.setText(error);return;}password.setText("");showSyncPage();});});
        if(!authorized)action(form,"首次激活 / 授权重试",this::showActivation,false);
        LinearLayout connection=card(root);pair(connection,"检查设备连接",this::showDiagnostics,"修改连接地址",this::showPlatformSettings);
        
        if(runtime.hasSession()){status.setText("原会话尚未退出，只能重新验证原账号");action(root,"返回原账号同步",this::showAccount,false);}
    }
    private void showAccount(){
        LinearLayout root=page("一卡通核验端");accountPage=true;homeStatus(lastState);JSONObject profile=runtime.uiProfile();
        LinearLayout state=card(root);deviceNameView=text(runtime.deviceName(),18,INK);deviceNameView.setTypeface(null,android.graphics.Typeface.BOLD);deviceNameView.setPadding(0,0,0,dp(10));state.addView(deviceNameView);
        LinearLayout chips=new LinearLayout(this);String[] vals={authorized?"已授权":"未授权",connectionLabel(),profile.optBoolean("sdkReady")?"算法就绪":"算法准备中"},icons={"check","wifi","sdk"};homeChips=new TextView[3];
        for(int i=0;i<3;i++){LinearLayout box=new LinearLayout(this);box.setGravity(android.view.Gravity.CENTER);box.addView(new TerminalIconView(this,icons[i],i==0?0xff00be79:BLUE),new LinearLayout.LayoutParams(dp(20),dp(20)));TextView t=text(vals[i],12,INK);t.setGravity(android.view.Gravity.CENTER);t.setPadding(0,dp(5),0,0);box.setOrientation(LinearLayout.VERTICAL);homeChips[i]=t;box.addView(t,new LinearLayout.LayoutParams(-1,-2));box.setMinimumHeight(dp(56));chips.addView(box,new LinearLayout.LayoutParams(0,-2,1));}state.addView(chips);
        homeAccount=text(profile.optString("scenicName","等待景区同步")+" · "+profile.optString("accountName","等待账号同步"),14,INK);homeAccount.setPadding(dp(4),dp(13),0,dp(8));state.addView(homeAccount);TextView sync=text("人员库"+(runtime.ready()?"已就绪":"同步中")+"  ›",12,BLUE);sync.setPadding(dp(4),0,0,0);state.addView(sync);sync.setOnClickListener(v->showSyncPage());
        label(root,"人脸核验");FrameLayout hero=new FrameLayout(this);hero.setBackgroundColor(0xff172b46);round(hero,12);int width=getResources().getDisplayMetrics().widthPixels;int previewHeight=Math.max(200,Math.min(280,Math.round(width/getResources().getDisplayMetrics().density*.60f)));root.addView(hero,new LinearLayout.LayoutParams(-1,dp(previewHeight)));
        TextureView preview=new TextureView(this);hero.addView(preview,new FrameLayout.LayoutParams(-1,-1));hero.addView(new TerminalFaceGuide(this,false),new FrameLayout.LayoutParams(-1,-1));TextView guide=text(cameraAllowed()?"请正对镜头，保持面部无遮挡":"点击开始核验后授权相机",13,Color.WHITE);guide.setGravity(android.view.Gravity.CENTER);guide.setPadding(dp(8),dp(7),dp(8),dp(7));guide.setBackground(background(0xbb152734,0));FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM);gp.setMargins(dp(30),0,dp(30),dp(8));hero.addView(guide,gp);
        camera=new TerminalCamera(this,runtime,preview,error->{lastCameraError=error;guide.setText(error);});if(cameraAllowed())camera.preview();
        startButton=action(root,runtime.ready()?"开始核验":"查看同步进度",()->{if(!runtime.ready()){showSyncPage();return;}if(cameraAllowed())showScan();else requestCamera(true);},true);
        TextView voiceLink=text("语音："+voiceLabel()+"  ·  设置与试听 ›",13,BLUE);voiceLink.setGravity(android.view.Gravity.CENTER);voiceLink.setPadding(0,dp(12),0,dp(12));root.addView(voiceLink,new LinearLayout.LayoutParams(-1,-2));voiceLink.setOnClickListener(v->showVoiceSettings());
        LinearLayout metrics=card(root);label(metrics,"今日入园概览");String[] keys={"passedPeople","passed","denied","notEffective"},labels={"通过人数","通过人次","今日拒绝","今日待生效"},metricIcons={"person","check","alert","clock"};int[] colors={0xff00b96b,BLUE,0xffee8240,0xffe9a100},fills={0xffedfaf5,0xffedf5ff,0xfffff2ec,0xfffff8e8};TextView[] values=new TextView[4];
        LinearLayout metricRow=null;int metricColumns=getResources().getConfiguration().screenWidthDp>=360&&getResources().getConfiguration().fontScale<=1.15f?4:2;
        for(int i=0;i<4;i++){if(i%metricColumns==0){metricRow=new LinearLayout(this);metrics.addView(metricRow,new LinearLayout.LayoutParams(-1,-2));}LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(android.view.Gravity.CENTER);box.setPadding(dp(3),dp(10),dp(3),dp(10));box.setBackground(background(fills[i],0));box.addView(new TerminalIconView(this,metricIcons[i],colors[i]),new LinearLayout.LayoutParams(dp(22),dp(22)));TextView n=text("—",26,colors[i]);n.setTypeface(null,android.graphics.Typeface.BOLD);n.setGravity(android.view.Gravity.CENTER);n.setPadding(0,dp(5),0,dp(5));values[i]=n;n.setMaxLines(1);if(Build.VERSION.SDK_INT>=26)n.setAutoSizeTextTypeUniformWithConfiguration(18,30,1,android.util.TypedValue.COMPLEX_UNIT_SP);box.addView(n,new LinearLayout.LayoutParams(-1,-2));TextView caption=text(labels[i],13,MUTED);caption.setGravity(android.view.Gravity.CENTER);box.addView(caption,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(i%metricColumns==0?0:dp(4),0,i%metricColumns==metricColumns-1?0:dp(4),dp(8));metricRow.addView(box,lp);}
        TextView scope=text("正在读取景区共享统计…",12,MUTED);scope.setPadding(dp(3),dp(8),dp(3),dp(12));scope.setLineSpacing(dp(3),1);root.addView(scope,new LinearLayout.LayoutParams(-1,-2));final int version=pageVersion;
        refreshHomeStatistics=new Runnable(){private boolean loading;public void run(){
            if(destroyed||pageVersion!=version||!foreground)return;
            if(loading)return;loading=true;
            runtime.today((data,error)->{
                loading=false;
                if(destroyed||pageVersion!=version||!foreground)return;
                JSONObject counts=data==null?null:data.optJSONObject("counts");
                if(error!=null||counts==null){for(TextView value:values)value.setText("—");scope.setText(error!=null?"统计暂不可用 · 将自动重试":"统计格式不匹配，请更新平台");}
                else{for(int i=0;i<4;i++)values[i].setText(String.valueOf(counts.optLong(keys[i])));scope.setText(data.optString("date")+" · "+data.optString("scopeText","统计范围待确认")+"\n通过人数、待生效按人去重；通过人次不去重");}
                main.removeCallbacks(this);main.postDelayed(this,16000);
            });
        }};if(foreground)refreshHomeStatistics.run();
        LinearLayout summary=card(root);label(summary,"本机最近核验");runtime.localRecords((data,error)->{if(destroyed||pageVersion!=version)return;if(error!=null){summary.addView(text(error,13,MUTED));return;}JSONArray rows=data.optJSONArray("items");if(rows==null||rows.length()==0)summary.addView(text("本机当前会话暂无记录",13,MUTED));else for(int i=0;i<Math.min(3,rows.length());i++){JSONObject r=rows.optJSONObject(i);if(r==null)continue;recordRow(summary,r);}TextView more=text("本机记录 · 查看全部  ›",12,BLUE);more.setPadding(0,dp(8),0,0);summary.addView(more);more.setOnClickListener(v->showLocalRecords());});
    }
    private void setScanState(ScanState state){
        if(scanStartupTimeout!=null){main.removeCallbacks(scanStartupTimeout);scanStartupTimeout=null;}
        scanState=state;
        if(!scanPage||startButton==null)return;
        startButton.setText(state==ScanState.RUNNING?"暂停核验":state==ScanState.STARTING?"正在启动相机…":"开始核验");
        startButton.setEnabled(state!=ScanState.STARTING&&runtime.ready());
    }
    private void showScan(){
        if(!runtime.ready()){showSyncPage();return;}
        page("人脸核验",false);scanPage=true;final int scanVersion=pageVersion;
        int width=getResources().getConfiguration().screenWidthDp,height=getResources().getConfiguration().screenHeightDp;
        boolean twoColumns=width>=680&&width>height;
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(0xfff5f6f8);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);shell.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(12),dp(16),dp(16));scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        LinearLayout heading=new LinearLayout(this);heading.setOrientation(LinearLayout.VERTICAL);heading.setPadding(dp(16),dp(16),dp(16),dp(16));heading.setBackground(artwork());round(heading,16);content.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);heading.addView(titleRow,new LinearLayout.LayoutParams(-1,-2));
        TerminalIconView back=new TerminalIconView(this,"back",Color.WHITE);back.setContentDescription("返回核验首页");titleRow.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->showAccount());
        TextView title=text("人脸核验",22,Color.WHITE);title.setTypeface(null,android.graphics.Typeface.BOLD);titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView scenic=text(runtime.uiProfile().optString("scenicName","等待景区信息"),14,Color.WHITE);scenic.setPadding(dp(4),dp(8),dp(4),0);heading.addView(scenic,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout body=new LinearLayout(this);body.setOrientation(twoColumns?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);LinearLayout.LayoutParams bodyLp=new LinearLayout.LayoutParams(-1,-2);bodyLp.topMargin=dp(16);content.addView(body,bodyLp);
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(0xff24384b);frame.setMinimumHeight(dp(240));round(frame,16);
        int previewHeight=twoColumns?Math.max(240,Math.min(420,height-148)):Math.max(260,Math.min(420,Math.round(width*.9f)));
        LinearLayout.LayoutParams previewLp=twoColumns?new LinearLayout.LayoutParams(0,dp(previewHeight),1):new LinearLayout.LayoutParams(-1,dp(previewHeight));
        if(twoColumns)previewLp.rightMargin=dp(16);body.addView(frame,previewLp);
        cameraPreview=new TextureView(this);frame.addView(cameraPreview,new FrameLayout.LayoutParams(-1,-1));frame.addView(new TerminalFaceGuide(this,true),new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams panelLp=twoColumns?new LinearLayout.LayoutParams(dp(280),-2):new LinearLayout.LayoutParams(-1,-2);if(!twoColumns)panelLp.topMargin=dp(16);body.addView(panel,panelLp);
        LinearLayout information=card(panel);label(information,"核验状态");
        resultView=text("正在启动相机，请保持面部无遮挡",18,INK);resultView.setTypeface(null,android.graphics.Typeface.BOLD);resultView.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);information.addView(resultView,new LinearLayout.LayoutParams(-1,-2));
        diagnosticView=text("识别人脸后，将由平台确认年卡状态与入园次数。",14,MUTED);diagnosticView.setPadding(0,dp(12),0,0);information.addView(diagnosticView,new LinearLayout.LayoutParams(-1,-2));
        startButton=action(panel,"开始核验",()->{
            if(scanState==ScanState.RUNNING){if(camera!=null)camera.stop();runtime.pauseCamera();setScanState(ScanState.PAUSED);resultView.setText("核验已暂停");diagnosticView.setText("相机已停止。点击开始核验后继续识别人脸。");}
            else startScanning();
        },true);
        action(panel,"重新识别当前人员",()->{if(scanState!=ScanState.RUNNING)startScanning();if(scanState==ScanState.RUNNING)runtime.nextAttempt();},false);
        Button switchLens=action(panel,"切换摄像头",()->{},false);
        switchLens.setOnClickListener(v->{
            if(camera==null)return;boolean resume=scanState==ScanState.RUNNING;
            if(camera.selectOtherLens()){
                setScanState(ScanState.PAUSED);resultView.setText("已切换至"+camera.lensName()+"摄像头");
                diagnosticView.setText(resume?"正在恢复核验，请正对镜头":"点击开始核验后使用当前镜头");
                if(resume)startScanning();switchLens.setText("当前"+camera.lensName()+" · 切换摄像头");
            }
        });
        Button mute=action(panel,voiceLabel(),()->{},false);mute.setOnClickListener(v->{boolean enabled=!localVoice();getSharedPreferences("terminal_audio",MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();if(!enabled)stopSpeech();mute.setText(voiceLabel());});
        camera=new TerminalCamera(this,runtime,cameraPreview,error->{
            if(destroyed||scanVersion!=pageVersion||!scanPage)return;
            lastCameraError=error;diagnosticView.setText(error);resultView.setText("相机未能启动，核验已暂停");setScanState(ScanState.ERROR);
        });
        camera.setStateListener(running->{
            if(destroyed||scanVersion!=pageVersion||!scanPage)return;
            if(running){
                setScanState(ScanState.RUNNING);resultView.setText("请正对镜头，保持面部无遮挡");diagnosticView.setText("正在识别人脸。核验完成后请让下一位进入取景区。");switchLens.setText("当前"+camera.lensName()+" · 切换摄像头");
            }else if(scanState==ScanState.RUNNING||scanState==ScanState.STARTING){
                setScanState(ScanState.PAUSED);resultView.setText("相机画面已停止，核验已暂停");diagnosticView.setText("点击开始核验重新打开相机；若仍无画面，请返回设备诊断检查权限和相机占用。");
            }
        });
        switchLens.setText("当前"+camera.lensName()+" · 切换摄像头");
        addSupport(content);setContentView(shell);startScanning();
    }
    private boolean syncPage,maintenancePage;
    private String count(JSONObject data,String key){return data!=null&&data.has(key)&&!data.isNull(key)?String.valueOf(data.optLong(key)):"等待平台返回";}
    private void updateSync(){
        if(loadRing==null)return;
        long total=runtime.loadTotal(),loaded=runtime.loadedFaces();JSONObject p=runtime.uiProfile();long local=p.optLong("localFaces");
        boolean loadedReady=runtime.libraryReady(),failed=runtime.libraryFailed();loadRing.setState(loadedReady&&!runtime.loginExpired(),failed||runtime.loginExpired());loadRing.setProgress(loadedReady?local:loaded,loadedReady?local:total);
        loadText.setText(runtime.loginExpired()?"登录已失效\n请先重新验证当前账号":failed?"本地特征加载失败\n请打开设备诊断查看原因":loadedReady?(p.has("localFaces")?"当前本地特征："+local+" 人\n"+(local==0?"暂无可检索人员特征":"本地检索库已就绪"):"算法已就绪\n本地人数等待平台状态刷新"):(loaded+" / "+total+" 人\n加载用时 "+runtime.loadElapsed()/1000+" 秒"));
        JSONObject ops=p.optJSONObject("syncOperations");
        if(syncValues!=null){syncValues[0].setText(count(ops,"upsert"));syncValues[1].setText(count(ops,"delete"));syncValues[2].setText(count(p,"pendingReceipts"));syncValues[3].setText(count(p,"pendingEvents"));syncValues[4].setText(count(p,"photoRejected"));}
        syncDetail.setText("待同步人员任务："+count(p,"pendingPeople")+"\n平台配置："+(p.optBoolean("platformSynced")?"已同步":"等待同步")+"\n领路人脸算法："+(runtime.loginExpired()?"等待重新验证账号":p.optBoolean("sdkReady")?"已就绪":"准备中")+"\n数据更新时间："+(p.optString("serverTime").isEmpty()?"尚未获取":p.optString("serverTime"))+"\n\n新增和更新合并为待写入；待回执可能与人员任务重叠。网络异常时保留最近数据，请重试同步。照片待处理表示对应照片未通过算法处理；请管理员在设备人员同步页面查看原因并更换照片。");
    }
    private void showReauthentication(){
        if(!runtime.hasSession()){showLogin();return;}
        final String expectedSessionId=runtime.currentSessionId();
        final EditText password=new EditText(this);password.setSingleLine();password.setHint("请输入当前账号密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this)
            .setTitle("重新验证当前账号")
            .setMessage("账号："+runtime.currentUsername()+"\n验证后继续同步，保留本地人员、设备授权和待确认记录。")
            .setView(password).setPositiveButton("验证并恢复",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String value=password.getText().toString();
            if(value.isEmpty()){password.setError("请输入密码");return;}
            dialog.getButton(-1).setEnabled(false);password.setText("");
            runtime.reauthenticate(expectedSessionId,value,error->{
                if(destroyed||!dialog.isShowing())return;
                if(error==null){dialog.dismiss();showSyncPage();}
                else {dialog.getButton(-1).setEnabled(true);password.setError(error);}
            });
        }));dialog.show();
    }
    private void showSyncPage(){
        LinearLayout root=page("人员同步");syncPage=true;status.setText(TerminalMessages.friendly(lastState));
        LinearLayout loading=card(root);label(loading,"本地检索库加载");LinearLayout progress=new LinearLayout(this);progress.setGravity(android.view.Gravity.CENTER_VERTICAL);loadRing=new TerminalProgressView(this);int ring=compact()?96:120;progress.addView(loadRing,new LinearLayout.LayoutParams(dp(ring),dp(ring)));loadText=text("",compact()?16:19,INK);loadText.setPadding(dp(16),0,0,0);progress.addView(loadText,new LinearLayout.LayoutParams(0,-2,1));loading.addView(progress);
        LinearLayout changes=card(root);label(changes,"平台增量同步");syncValues=new TextView[5];String[] labels={"待写入人员","待删除人员","人员任务回执","核验记录待确认","照片待处理（无法刷脸）"};for(int i=0;i<labels.length;i++)syncValues[i]=row(changes,labels[i],"读取中…");
        syncDetail=text("",13,MUTED);syncDetail.setPadding(0,dp(12),0,0);changes.addView(syncDetail);updateSync();
        LinearLayout note=card(root);note.setBackground(background(0xffe4f3ff,0));note.addView(text("本地特征已保存在设备中。重启后分批加载到算法内存，不重复下载照片；全部加载并同步完成后可开始核验。",14,MUTED));
        action(root,"重新验证当前账号",this::showReauthentication,false);action(root,"立即同步",runtime::retry,false);action(root,"设备诊断",this::showDiagnostics,false);
    }
    private String deviceTime(long time){if(time<=0)return "尚无记录";SimpleDateFormat fmt=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA);fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return fmt.format(new java.util.Date(time));}
    private void exportDiagnostic(String snapshot){
        if(snapshot.isEmpty())return;
        if(!pendingDiagnostic.isEmpty()){Toast.makeText(this,"请先完成当前文件选择",Toast.LENGTH_SHORT).show();return;}
        pendingDiagnostic=snapshot;
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
        picker.putExtra(Intent.EXTRA_TITLE,"yikatong-diagnostic-"+System.currentTimeMillis()+".txt");
        try{startActivityForResult(picker,303);}catch(android.content.ActivityNotFoundException e){pendingDiagnostic="";Toast.makeText(this,"系统文件选择器不可用，请使用复制诊断信息",Toast.LENGTH_LONG).show();}
    }
    private String deviceResources(){
        android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager manager=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
        if(manager!=null)manager.getMemoryInfo(memory);
        android.os.StatFs disk=new android.os.StatFs(getFilesDir().getAbsolutePath());
        long mib=1024L*1024;
        return "\n设备型号："+Build.MANUFACTURER+" / "+Build.MODEL+"\nCPU架构："+android.text.TextUtils.join(", ",Build.SUPPORTED_ABIS)
            +"\n应用Java堆已用/上限："+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/mib+" / "+Runtime.getRuntime().maxMemory()/mib+" MiB"
            +"\n应用原生堆已分配："+android.os.Debug.getNativeHeapAllocatedSize()/mib+" MiB"
            +"\n系统可用内存："+(manager==null?"不可用":memory.availMem/mib+" MiB")+"\n应用分区可用空间："+disk.getAvailableBytes()/mib+" MiB";
    }
    private void showDiagnostics(){
        LinearLayout root=page("设备诊断");final int version=pageVersion;status.setText("仅显示连接、算法与数量信息，可复制给技术支持。时间按北京时间显示。");
        LinearLayout box=card(root);label(box,"设备运行信息");TextView report=text("正在读取…",14,INK);report.setTextIsSelectable(true);report.setLineSpacing(dp(5),1);box.addView(report);
        final String[] copy={""};Button check=action(root,"立即检查连接与同步",()->{runtime.retryConnection();status.setText("已发起检查，结果将自动刷新");},true);
        Button copyButton=action(root,"复制诊断信息",()->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null){clipboard.setPrimaryClip(android.content.ClipData.newPlainText("一卡通设备诊断",copy[0]));Toast.makeText(this,"诊断信息已复制",Toast.LENGTH_SHORT).show();}},false);copyButton.setEnabled(false);
        Button exportButton=action(root,"导出诊断快照（TXT）",()->exportDiagnostic(copy[0]),false);exportButton.setEnabled(false);
        root.addView(text("快照不含人员、证件、照片、特征、密码或访问凭据。内存为当前采样，不代表峰值或容量验收。",12,MUTED));
        action(root,"语音检查与试听",this::showVoiceSettings,false);action(root,"平台连接设置",this::showPlatformSettings,false);
        refreshDiagnostics=new Runnable(){public void run(){if(destroyed||version!=pageVersion||!foreground)return;runtime.diagnostics((data,error)->{
            if(destroyed||version!=pageVersion||!foreground)return;
            if(error!=null){report.setText(error);copy[0]="";copyButton.setEnabled(false);exportButton.setEnabled(false);}else{
                JSONObject q=data.optJSONObject("sync");long age=data.optLong("heartbeatAgeSeconds",-1);
                String info="APP："+data.optString("appVersion")+"\nAndroid："+Build.VERSION.RELEASE+"\n平台："+savedOrigin()
                    +"\n本机标识摘要："+deviceIdentitySummary()+"\n标识用途：核对平台档案，不代表算法授权状态"+"\n算法设备指纹："+(license.fingerprintReady()?"已取得（不导出指纹值）":"未取得")+"\n激活阶段："+TerminalActivationGate.phaseText(license.state().phase)+"\n最近激活尝试："+deviceTime(license.lastAttempt())+"\n激活错误："+(license.state().error==0?"无":TerminalActivationGate.describe(license.state().error))
                    +"\n设备名称："+data.optString("deviceName")+"\nAPP配置版本："+data.optLong("appConfigRevision")+"\nAPP限制原因："+data.optString("appBlockedReason","无")+"\n设备授权："+(data.optBoolean("authorized")?"已完成":"未完成")+"\n账号会话："+(data.optBoolean("loginExpired")?"登录已失效，需重新验证":data.optBoolean("hasSession")?"已保存登录会话":"未登录")+"\n最近同步错误："+data.optString("lastSyncError","")
                    +"\n领路人脸算法："+(data.optBoolean("modelFailed")?"加载失败":data.optBoolean("sdkReady")?"就绪":data.optBoolean("loading")?"加载中":"等待初始化")
                    +"\n当前可核验："+(data.optBoolean("ready")?"是":"否")+"\n相机权限："+(cameraAllowed()?"已开启":"未开启")
                    +"\n算法授权备份："+data.optString("licenseBackup","尚未确认")
                    +"\n本地特征："+data.optLong("localFaces")+" 人\n启动加载："+data.optLong("loadedFaces")+" / "+data.optLong("loadTotal")+" 人，"+data.optLong("loadElapsed")/1000+" 秒"
                    +"\n待确认记录："+data.optLong("pendingEvents")+" 条\n待同步人员："+count(q,"pendingPeople")+"\n人员待回执："+count(q,"pendingReceipts")
                    +"\n当前同步步骤："+data.optString("syncStep")+"（"+data.optLong("syncStepSeconds")+"秒）"
                    +"\n最近成功心跳请求耗时："+data.optLong("lastSyncRequestMs")+" ms；连续同步失败："+data.optInt("syncFailures")+"；下次同步检查："+data.optLong("retrySeconds")+"秒后"
                    +"\n最近人员批次尝试耗时："+data.optLong("lastPersonnelBatchMs")+" ms（含下载、算法和回执）；最近人员回执尝试："+data.optLong("lastPersonnelAckMs")+" ms（0表示尚无采样或不足1ms）"
                    +"\n平台名单："+(q!=null&&q.optJSONObject("roster")!=null?q.optJSONObject("roster").optString("message"):"尚未取得平台名单诊断，请先完成登录并检查连接")
                    +"\n最近连接尝试："+deviceTime(data.optLong("lastAttempt"))+"\n最近连接成功："+deviceTime(data.optLong("lastSuccess"))
                    +"\n连续连接失败："+data.optInt("consecutiveFailures")+" 次\n连接检查："+(data.optBoolean("checking")?"进行中":data.optString("failureKind").isEmpty()?"无待处理错误":data.optString("failureKind"))
                    +"\n最近业务心跳："+deviceTime(data.optLong("lastHeartbeat"))+(age<0?"":"（"+age+" 秒前）")
                    +"\n最近相机异常："+lastCameraError+deviceResources()+"\n\n"+voiceDetails();report.setText(info);copy[0]="一卡通设备诊断 · "+deviceTime(System.currentTimeMillis())+"\n"+info;copyButton.setEnabled(true);exportButton.setEnabled(true);check.setEnabled(!data.optBoolean("checking"));
            }
            main.removeCallbacks(this);main.postDelayed(this,2000);
        });}};if(foreground)refreshDiagnostics.run();
    }
    private void showMaintenance(){
        LinearLayout root=page("设备维护");maintenancePage=true;status.setText(TerminalMessages.friendly(lastState));JSONObject p=runtime.uiProfile();LinearLayout device=card(root);LinearLayout licensed=iconLabel("check",authorized?"设备已授权":"设备未授权",0xff00b779,32);licensed.setPadding(0,dp(8),0,dp(8));licensed.setBackground(background(0xffeaf8f1,0));round(licensed,8);device.addView(licensed);deviceNameView=row(device,"设备名称",runtime.deviceName());maintenanceConnection=row(device,"平台通信",TerminalMessages.friendly(runtime.communicationStatus()).replace("平台通信：",""));row(device,"当前账号",p.optString("accountName","待同步"));row(device,"所属景区",p.optString("scenicName","待同步"));row(device,"识别模式","RGB活体");
        LinearLayout sync=card(root);label(sync,"数据与同步");TextView faceCount=row(sync,"本地特征","读取中…");TextView pendingCount=row(sync,"待确认记录","读取中…");final int maintenanceVersion=pageVersion;runtime.localRecords((data,error)->{if(destroyed||pageVersion!=maintenanceVersion)return;faceCount.setText(error==null?String.valueOf(data.optLong("localFaces")):"暂不可用");pendingCount.setText(error==null?String.valueOf(data.optLong("pending")):"暂不可用");});row(sync,"同步状态",runtime.loginExpired()?"登录失效，请重新验证":runtime.ready()?"已完成":"准备中");action(sync,"立即同步 / 查看进度",this::showSyncPage,false);
        LinearLayout menus=card(root);menu(menus,"APP版本与更新",this::checkAppUpdate);menu(menus,"设备诊断与连接检查",this::showDiagnostics);menu(menus,"管理员设置",this::openBaiduSettings);menu(menus,"景区入园统计",this::showStatistics);menu(menus,"本机核验记录",this::showLocalRecords);menu(menus,"相机权限管理",this::explainPermissions);menu(menus,"语音播报 · "+voiceLabel(),this::showVoiceSettings);menu(menus,"记录备份与恢复",this::showBackup);menu(menus,"卸载或清除数据前检查",this::showUninstallCheck);
        pair(root,"连接设置",this::showPlatformSettings,"重新验证账号",this::showReauthentication);
        Button logout=action(root,"同步后退出账号",()->{},false);logout.setTextColor(0xffba522d);logout.setOnClickListener(v->{logout.setEnabled(false);status.setText("正在确认原账号记录、人员任务和回执…");runtime.exit(error->{if(destroyed)return;logout.setEnabled(true);if(error!=null)status.setText(error);else if(!runtime.hasSession())showLogin();});});TextView note=text("ⓘ 原账号记录确认完成后才能切换",12,MUTED);note.setGravity(android.view.Gravity.CENTER);root.addView(note);
    }
    private void startScanning(){
        if(camera==null||!runtime.startCamera()){
            setScanState(ScanState.UNAVAILABLE);
            if(status!=null)status.setText("当前设备未就绪，请查看同步状态");
            if(scanPage&&resultView!=null){resultView.setText("暂时无法开始核验");diagnosticView.setText(TerminalMessages.friendly(lastState)+"。请返回首页查看同步或连接状态。");}
            return;
        }
        setScanState(ScanState.STARTING);cameraPreview.setVisibility(android.view.View.VISIBLE);
        resultView.setText("正在启动相机，请稍候");diagnosticView.setText("等待相机预览画面，画面就绪后开始识别。");
        final int version=pageVersion;
        scanStartupTimeout=()->{
            if(destroyed||version!=pageVersion||!scanPage||scanState!=ScanState.STARTING)return;
            if(camera!=null)camera.stop();runtime.pauseCamera();setScanState(ScanState.ERROR);
            lastCameraError="相机预览超过8秒仍未就绪，请重新开始；若再次失败，请返回首页并检查相机权限或其他应用占用";
            resultView.setText("相机启动超时，核验已暂停");diagnosticView.setText(lastCameraError);
        };
        main.postDelayed(scanStartupTimeout,8000);
        if(!camera.start()){runtime.pauseCamera();setScanState(ScanState.ERROR);}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request!=200)return;
        boolean resume=startAfterPermission;startAfterPermission=false;
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"相机已授权",Toast.LENGTH_SHORT).show();if(resume&&accountPage)showScan();
        }else{status.setText("相机权限未授权；可继续同步和统计，需要核验时请打开权限管理");}
    }

    private void showUninstallCheck(){
        LinearLayout root=page("卸载或清除数据前检查");final int version=pageVersion;
        TextView report=text("正在读取本机会话待确认记录…",15,INK);root.addView(report);
        runtime.localRecords((data,error)->{if(destroyed||version!=pageVersion)return;
            if(error!=null){report.setText("未能读取本机记录："+TerminalMessages.friendly(error)+"；尚不能判断数据是否已上传，请勿卸载或清除数据。");return;}
            long pending=data.optLong("pending");
            report.setText(TerminalSyncSchedule.uninstallNotice(pending,runtime.hasSession()));
        });
        action(root,"查看同步进度并处理待确认记录",this::showSyncPage,true);
        action(root,"查看加密记录备份说明",this::showBackup,false);
        action(root,"返回设备维护",this::showMaintenance,false);
    }

    private void showBackup(){
        LinearLayout root=page("记录备份与恢复");status.setText("备份由密码加密，请妥善保管文件与密码。");
        LinearLayout info=card(root);label(info,"本机会话记录");TextView help=text("备份当前会话的核验记录及回执。恢复仅支持原设备、原平台、原会话，合并缺失记录，不覆盖已有新记录。\n\n不包含人员特征库、算法授权、账号密码和算法设置；不用于卸载后恢复或迁移设备。",14,MUTED);help.setLineSpacing(dp(5),1);info.addView(help);
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
        LinearLayout root=page("本机核验记录");int version=pageVersion;status.setText("本机当前登录会话 · 北京时间今日。景区共享统计请查看首页。");
        LinearLayout summary=card(root);label(summary,"本机今日概览");String[] keys={"passed","denied","notEffectivePeople","awaiting","pending","today"},labels={"通过人次","拒绝记录","待生效人数","等待平台处理","未确认回执","今日原始记录"};TextView[] values=new TextView[keys.length];
        for(int i=0;i<keys.length;i+=2){LinearLayout line=new LinearLayout(this);summary.addView(line,new LinearLayout.LayoutParams(-1,-2));for(int j=i;j<i+2;j++){LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(dp(4),dp(8),dp(4),dp(12));values[j]=text("—",23,INK);values[j].setTypeface(null,android.graphics.Typeface.BOLD);cell.addView(values[j]);cell.addView(text(labels[j],13,MUTED));line.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}}
        TextView scope=text("待生效同人同日合并展示，原始记录和回执保留。未确认回执包含本会话历史待处理记录。",12,MUTED);scope.setPadding(0,dp(12),0,0);summary.addView(scope);
        LinearLayout filterBox=card(root);label(filterBox,"最近记录筛选");
        android.widget.Spinner resultFilter=new android.widget.Spinner(this),receiptFilter=new android.widget.Spinner(this);
        resultFilter.setAdapter(new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部核验结果","核验通过","未通过 / 待处理"}));
        receiptFilter.setAdapter(new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部回执状态","平台已确认","待确认"}));
        resultFilter.setMinimumHeight(dp(48));receiptFilter.setMinimumHeight(dp(48));resultFilter.setContentDescription("核验结果筛选");receiptFilter.setContentDescription("回执状态筛选");filterBox.addView(resultFilter);filterBox.addView(receiptFilter);
        Button refresh=action(filterBox,"查询 / 刷新记录",()->{},true);action(root,"重试同步与回执",runtime::retry,false);
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(0,dp(12),0,dp(8));root.addView(rows);
        refresh.setOnClickListener(v->{refresh.setEnabled(false);runtime.localRecords((data,error)->{
            if(destroyed||pageVersion!=version)return;refresh.setEnabled(true);rows.removeAllViews();
            if(error!=null){for(TextView value:values)value.setText("—");rows.addView(text(error,14,MUTED));return;}
            for(int i=0;i<keys.length;i++)values[i].setText(String.valueOf(data.optLong(keys[i])));
            label(rows,"最近20条内筛选 · 当前会话");JSONArray items=data.optJSONArray("items");
            if(items==null||items.length()==0){LinearLayout empty=card(rows);label(empty,"还没有核验记录");empty.addView(text("开始核验后，本机结果和回执状态会显示在这里。",14,MUTED));}
            int matchingRows=0;
            if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null)continue;
                int selectedResult=resultFilter.getSelectedItemPosition(),selectedReceipt=receiptFilter.getSelectedItemPosition();
                if(selectedResult==1&&!item.optBoolean("pass")||selectedResult==2&&item.optBoolean("pass"))continue;
                if(selectedReceipt==1&&!item.optBoolean("confirmed")||selectedReceipt==2&&item.optBoolean("confirmed"))continue;
                LinearLayout record=card(rows);recordRow(record,item);matchingRows++;
            }
            if(items!=null&&items.length()>0&&matchingRows==0)rows.addView(text("最近20条记录中没有符合筛选条件的记录",14,MUTED));
        });});refresh.performClick();
    }
    private void showStatistics(){
        LinearLayout root=page("景区入园统计");int version=pageVersion;status.setText("按所选日期查询景区入园人数，多个设备共享平台统计。");
        Calendar today=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);fmt.setTimeZone(today.getTimeZone());
        LinearLayout filters=card(root);label(filters,"选择日期范围");Button from=new Button(this),to=new Button(this);from.setText(fmt.format(today.getTime()));to.setText(fmt.format(today.getTime()));styleButton(from);styleButton(to);
        filters.addView(text("开始日期",13,MUTED));filters.addView(from);TextView endLabel=text("结束日期",13,MUTED);endLabel.setPadding(0,dp(12),0,0);filters.addView(endLabel);filters.addView(to);
        android.view.View.OnClickListener date=v->{Button button=(Button)v;String[] parts=button.getText().toString().split("-");DatePickerDialog dialog=new DatePickerDialog(this,(picker,y,m,d)->button.setText(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)),Integer.parseInt(parts[0]),Integer.parseInt(parts[1])-1,Integer.parseInt(parts[2]));dialog.getDatePicker().setMaxDate(System.currentTimeMillis());dialog.show();};from.setOnClickListener(date);to.setOnClickListener(date);
        LinearLayout quick=new LinearLayout(this);String[] shortcuts={"今天","近7天","近30天"};int[] days={0,6,29};for(int i=0;i<3;i++){final int offset=days[i];TextView link=text(shortcuts[i],14,BLUE);link.setGravity(android.view.Gravity.CENTER);link.setPadding(dp(3),dp(16),dp(3),dp(6));quick.addView(link,new LinearLayout.LayoutParams(0,-2,1));link.setOnClickListener(v->{Calendar now=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));to.setText(fmt.format(now.getTime()));now.add(Calendar.DAY_OF_MONTH,-offset);from.setText(fmt.format(now.getTime()));});}filters.addView(quick);
        Button query=action(root,"查询入园人数",()->{},true);LinearLayout results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);results.setPadding(0,dp(14),0,0);root.addView(results);
        query.setOnClickListener(v->{String start=from.getText().toString(),end=to.getText().toString();if(start.compareTo(end)>0){status.setText("开始日期不能晚于结束日期，请重新选择");results.removeAllViews();return;}
            query.setEnabled(false);from.setEnabled(false);to.setEnabled(false);for(int i=0;i<quick.getChildCount();i++)quick.getChildAt(i).setEnabled(false);query.setText("正在查询…");results.removeAllViews();status.setText("正在读取平台统计…");
            runtime.statistics(start,end,(data,error)->{if(destroyed||version!=pageVersion)return;query.setEnabled(true);query.setText("查询入园人数");from.setEnabled(true);to.setEnabled(true);for(int i=0;i<quick.getChildCount();i++)quick.getChildAt(i).setEnabled(true);
                if(error!=null){status.setText(TerminalMessages.friendly(error));return;}
                status.setText(start+" 至 "+end);LinearLayout total=card(results);label(total,"每日去重人数合计");TextView number=text(data.optLong("totalDailyPeople")+" 人",28,BLUE);number.setTypeface(null,android.graphics.Typeface.BOLD);total.addView(number);TextView explanation=text(data.optString("countingDescription"),13,MUTED);explanation.setPadding(0,dp(12),0,0);total.addView(explanation);
                JSONArray items=data.optJSONArray("items");if(items==null||items.length()==0){LinearLayout empty=card(results);empty.addView(text("所选日期暂无入园记录，可调整日期后查询。",14,MUTED));}
                if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null)continue;LinearLayout day=card(results);label(day,item.optString("date"));row(day,item.optString("scenicName"),item.optLong("people")+" 人");}
            });
        });query.performClick();
    }
    @Override public void status(String value,boolean ready,boolean hasSession){
        if(destroyed)return;applyAppConfig();if(!runtime.voiceAllowed()&&!speechUtterance.isEmpty())stopSpeech();updateVoiceStatus();lastState=value;if(maintenancePage)status.setText(TerminalMessages.friendly(value));if(syncPage){status.setText(TerminalMessages.friendly(value));updateSync();}
        if(loginPage){if(loginButton!=null)loginButton.setEnabled(!runtime.signingIn()&&!license.state().busy());status.setText(TerminalMessages.friendly(value));if(hasSession&&value.startsWith("账号已登录")){loginCompleted=true;if(authorized&&!runtime.signingIn()&&foreground){showSyncPage();return;}}}
        if(!hasSession&&authorized&&!baiduSettingsOpen&&(accountPage||scanPage||resultPage||syncPage||maintenancePage)){showLogin();return;}
        if(accountPage){homeStatus(value);JSONObject p=runtime.uiProfile();if(homeChips!=null){homeChips[1].setText(connectionLabel());homeChips[2].setText(p.optBoolean("sdkReady")?"算法就绪":"算法准备中");}if(homeAccount!=null)homeAccount.setText(p.optString("scenicName","等待景区同步")+" · "+p.optString("accountName","等待账号同步"));if(startButton!=null){startButton.setText(ready?"开始核验":"查看同步进度");startButton.setEnabled(true);}}
        if(resultPage&&resultReceipt!=null)resultReceipt.setText(receiptText());
        if(scanPage){status.setText(value);if(!ready){if(diagnosticView!=null)diagnosticView.setText(value);if(resultView!=null)resultView.setText("核验已暂停，请按下方原因处理");if(camera!=null)camera.stop();setScanState(ScanState.UNAVAILABLE);}if(startButton!=null)startButton.setEnabled(ready&&scanState!=ScanState.STARTING);}
    }
    @Override public void diagnostics(String value){if(!destroyed&&scanPage&&diagnosticView!=null)diagnosticView.setText(value);}
    @Override public void communication(String value){if(!destroyed&&communication!=null){if(deviceNameView!=null)deviceNameView.setText(runtime.deviceName());communication.setText(TerminalMessages.friendly(value));if(maintenanceConnection!=null)maintenanceConnection.setText(TerminalMessages.friendly(value).replace("平台通信：",""));if(accountPage&&homeChips!=null)homeChips[1].setText(connectionLabel());}}
    private android.media.AudioManager audioManager(){return (android.media.AudioManager)getSystemService(AUDIO_SERVICE);}
    private void stopSpeech(){if(speech!=null)speech.stop();speechUtterance="";android.media.AudioManager audio=audioManager();if(audio!=null)audio.abandonAudioFocus(speechFocus);}
    private void initSpeech(){
        final int generation=++speechGeneration;speechReady=false;speechStatus="正在连接中文语音引擎";stopSpeech();
        if(speechTimeout!=null)main.removeCallbacks(speechTimeout);if(speech!=null){speech.shutdown();speech=null;}
        final String preferred=getSharedPreferences("terminal_audio",MODE_PRIVATE).getString("engine","");speechEngine=preferred.isEmpty()?"系统默认":preferred;updateVoiceStatus();
        speechTimeout=()->{if(destroyed||generation!=speechGeneration)return;speechGeneration++;speechReady=false;speechStatus="语音引擎连接超时，请重新初始化或切换引擎";if(speech!=null)speech.shutdown();updateVoiceStatus();};main.postDelayed(speechTimeout,12000);
        try{speech=new TextToSpeech(this,code->main.post(()->{
            if(destroyed||generation!=speechGeneration)return;main.removeCallbacks(speechTimeout);
            if(code!=TextToSpeech.SUCCESS){speechStatus="语音引擎连接失败，请选择引擎或检查系统语音设置";updateVoiceStatus();return;}
            try{
                String actual=preferred.isEmpty()?speech.getDefaultEngine():preferred;
                for(TextToSpeech.EngineInfo info:speech.getEngines())if(info.name.equals(actual)){speechEngine=info.label;break;}
                int language=speech.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if(language==TextToSpeech.LANG_NOT_SUPPORTED)language=speech.setLanguage(Locale.CHINESE);
                speechReady=language>=0;
                speechStatus=language==TextToSpeech.LANG_MISSING_DATA?"缺少中文语音数据，请点安装语音数据":language==TextToSpeech.LANG_NOT_SUPPORTED?"此引擎不支持中文，请选择其他引擎":speechReady?"中文语音已就绪，点击试听检查声音":"中文语音初始化失败，请重试";
                // Prefer an installed offline Chinese voice when the engine provides one.
                java.util.Set<android.speech.tts.Voice> voices=speech.getVoices();android.speech.tts.Voice best=null;
                if(speechReady&&voices!=null)for(android.speech.tts.Voice voice:voices){
                    if(!"zh".equals(voice.getLocale().getLanguage())||voice.isNetworkConnectionRequired()||(voice.getFeatures()!=null&&voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))continue;
                    if(best==null||("CN".equals(voice.getLocale().getCountry())&&!"CN".equals(best.getLocale().getCountry())))best=voice;
                }
                if(best!=null)speech.setVoice(best);
                speech.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
                speech.setSpeechRate(1.0f);speech.setPitch(1.0f);
                speech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener(){
                    public void onStart(String id){main.post(()->{if(destroyed||generation!=speechGeneration||!id.equals(speechUtterance))return;speechStatus="正在播报";updateVoiceStatus();});}
                    public void onDone(String id){main.post(()->finishSpeech(generation,id,null));}
                    public void onError(String id){main.post(()->finishSpeech(generation,id,"播放失败，请检查中文数据、音量和声音输出设备"));}
                    public void onError(String id,int errorCode){main.post(()->finishSpeech(generation,id,"语音播放失败（"+errorCode+"），请检查中文数据与音量"));}
                });
            }catch(RuntimeException e){speechReady=false;speechStatus="语音引擎响应异常，请重新初始化或切换引擎";}
            updateVoiceStatus();
        }),preferred.isEmpty()?null:preferred);}catch(RuntimeException e){main.removeCallbacks(speechTimeout);speechReady=false;speechStatus="无法连接语音服务，请检查系统语音设置";updateVoiceStatus();}
    }
    private void finishSpeech(int generation,String id,String error){
        if(destroyed||generation!=speechGeneration||!id.equals(speechUtterance))return;
        speechUtterance="";android.media.AudioManager audio=audioManager();if(audio!=null)audio.abandonAudioFocus(speechFocus);
        speechStatus=error==null?"播报已完成；未听到声音时请检查媒体音量及蓝牙输出":error;updateVoiceStatus();
        if(error!=null)Toast.makeText(this,error,Toast.LENGTH_LONG).show();
    }
    private String voiceDetails(){
        android.media.AudioManager audio=audioManager();String volume=audio==null?"无法读取":audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)+" / "+audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        return "本机播报："+(localVoice()?"已开启":"已关闭")+"\n平台播报："+(runtime.voiceAllowed()?"允许":"已关闭")+"\n语音引擎："+speechEngine+"\n媒体音量："+volume+"\n"+speechStatus;
    }
    private void updateVoiceStatus(){if(voiceStatusView!=null)voiceStatusView.setText(voiceDetails());}
    private void speakResult(String words){
        if(!foreground||destroyed)return;
        if(words==null||words.trim().isEmpty())return;
        if(!localVoice()||!runtime.voiceAllowed()){Toast.makeText(this,!localVoice()?"请先开启本机语音播报":"后台已关闭核验语音，请联系管理员",Toast.LENGTH_SHORT).show();updateVoiceStatus();return;}
        android.media.AudioManager audio=audioManager();
        String problem=!speechReady?speechStatus:audio!=null&&audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)==0?"媒体音量为0，请按音量键调高":null;
        if(problem!=null){Toast.makeText(this,problem,Toast.LENGTH_LONG).show();updateVoiceStatus();return;}
        if(audio!=null&&audio.requestAudioFocus(speechFocus,android.media.AudioManager.STREAM_MUSIC,android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)!=android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED){speechStatus="声音正被其他应用占用，请稍后试听";updateVoiceStatus();Toast.makeText(this,speechStatus,Toast.LENGTH_LONG).show();return;}
        speechUtterance="terminal-"+(++speechSequence)+"-"+SystemClock.elapsedRealtime();Bundle options=new Bundle();options.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,android.media.AudioManager.STREAM_MUSIC);options.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,1f);
        if(speech.speak(words,TextToSpeech.QUEUE_FLUSH,options,speechUtterance)==TextToSpeech.ERROR){stopSpeech();speechStatus="播报提交失败，请重新初始化语音";updateVoiceStatus();Toast.makeText(this,speechStatus,Toast.LENGTH_LONG).show();}
    }
    private void chooseSpeechEngine(){
        java.util.List<TextToSpeech.EngineInfo> engines=speech==null?new ArrayList<>():speech.getEngines();
        if(engines.isEmpty()){new android.app.AlertDialog.Builder(this).setTitle("未检测到语音引擎").setMessage("请在手机系统设置中启用支持中文的文字转语音引擎，再返回重新初始化。部分精简系统需要先安装可信语音引擎。").setPositiveButton("系统语音设置",(d,w)->openSpeechSettings()).setNegativeButton("取消",null).show();return;}
        String[] names=new String[engines.size()+1];names[0]="跟随系统默认";for(int i=0;i<engines.size();i++)names[i+1]=engines.get(i).label;
        new android.app.AlertDialog.Builder(this).setTitle("选择中文语音引擎").setItems(names,(d,index)->{getSharedPreferences("terminal_audio",MODE_PRIVATE).edit().putString("engine",index==0?"":engines.get(index-1).name).apply();initSpeech();}).setNegativeButton("取消",null).show();
    }
    private void installSpeechData(){
        Intent intent=new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);String engine=getSharedPreferences("terminal_audio",MODE_PRIVATE).getString("engine","");if(engine.isEmpty()&&speech!=null)engine=speech.getDefaultEngine();if(engine!=null&&!engine.isEmpty())intent.setPackage(engine);
        try{startActivityForResult(intent,402);}catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"此引擎未提供语音数据安装入口，请从系统语音设置检查中文语音",Toast.LENGTH_LONG).show();openSpeechSettings();}
    }
    private void openSpeechSettings(){try{startActivityForResult(new Intent("com.android.settings.TTS_SETTINGS"),401);}catch(android.content.ActivityNotFoundException e){try{startActivityForResult(new Intent(Settings.ACTION_SETTINGS),401);}catch(android.content.ActivityNotFoundException ignored){Toast.makeText(this,"请手动打开手机设置中的文字转语音",Toast.LENGTH_LONG).show();}}}
    private void addVoiceToggle(LinearLayout box){
        LinearLayout line=new LinearLayout(this);line.setGravity(android.view.Gravity.CENTER_VERTICAL);TextView title=text("开启语音播报",16,INK);line.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(new android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Light_NoActionBar));toggle.setText("");toggle.setShowText(false);toggle.setSwitchMinWidth(dp(52));toggle.setMinHeight(dp(48));toggle.setChecked(localVoice());toggle.setContentDescription("开启或关闭本机核验语音");
        int[][] states={new int[]{android.R.attr.state_checked},new int[]{}};
        // Drawable tinting supports API 21; Switch tint setters require API 23.
        // Mutate each instance so the colors cannot affect another control.
        android.graphics.drawable.Drawable thumb=toggle.getThumbDrawable(),track=toggle.getTrackDrawable();
        if(thumb!=null)thumb.mutate().setTintList(new android.content.res.ColorStateList(states,new int[]{BLUE,0xff8995a5}));
        if(track!=null)track.mutate().setTintList(new android.content.res.ColorStateList(states,new int[]{0xff9bc5ff,0xffd7dfe9}));
        line.addView(toggle,new LinearLayout.LayoutParams(-2,-2));box.addView(line,new LinearLayout.LayoutParams(-1,-2));toggle.setOnCheckedChangeListener((v,on)->{getSharedPreferences("terminal_audio",MODE_PRIVATE).edit().putBoolean("enabled",on).apply();if(!on)stopSpeech();updateVoiceStatus();});title.setOnClickListener(v->toggle.setChecked(!toggle.isChecked()));
    }
    private void showVoiceSettings(){
        LinearLayout root=page("语音播报设置");status.setText("使用手机媒体音量。先开启播报，再点击试听；连接蓝牙时声音可能从蓝牙设备播放。");LinearLayout box=card(root);label(box,"核验语音播报");addVoiceToggle(box);
        voiceStatusView=text("",14,MUTED);voiceStatusView.setPadding(0,dp(12),0,dp(4));box.addView(voiceStatusView);updateVoiceStatus();
        action(root,"试听：核验通过",()->speakResult("核验通过，欢迎入园"),true);
        action(root,"试听：年票未生效",()->speakResult("年票未生效，请联系工作人员"),false);
        LinearLayout settings=card(root);label(settings,"语音故障处理");menu(settings,"选择语音引擎",this::chooseSpeechEngine);menu(settings,"安装中文语音数据",this::installSpeechData);menu(settings,"重新初始化语音",this::initSpeech);menu(settings,"打开系统语音设置",this::openSpeechSettings);
    }
    private String voiceLabel(){return !runtime.voiceAllowed()?"平台静音":localVoice()?"声音开":"已静音";}
    private String receiptText(){JSONObject out=runtime.outcome();return out.optBoolean("confirmed")?"记录状态：平台已确认":out.optBoolean("persisted")?"记录状态：平台已保存，回执确认中":"记录状态：等待补传或处理";}
    @Override public void result(String value,String voice,boolean open,String type){
        if(destroyed||!foreground||!scanPage)return;if(value.startsWith("已开始新一轮")){resultView.setText(value);return;}
        if(!voice.isEmpty())speakResult(voice);
        LinearLayout root=page("核验结果",false);resultPage=true;status.setText("");LinearLayout outcome=card(root);outcome.setGravity(android.view.Gravity.CENTER);TerminalResultBadge badge=new TerminalResultBadge(this,open);outcome.addView(badge,new LinearLayout.LayoutParams(dp(150),dp(150)));TextView title=text(TerminalResultType.title(type),28,open?0xff004b31:0xffb64520);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setPadding(0,dp(18),0,dp(8));outcome.addView(title);TextView detail=text(value,17,MUTED);detail.setGravity(android.view.Gravity.CENTER);outcome.addView(detail);
        LinearLayout facts=card(root);SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA);fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));String occurred=runtime.outcome().optString("occurredAt");String when="本机显示 "+fmt.format(new java.util.Date());try{SimpleDateFormat utc=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);utc.setTimeZone(TimeZone.getTimeZone("UTC"));if(!occurred.isEmpty())when=fmt.format(utc.parse(occurred));}catch(Exception ignored){}row(facts,"核验时间",when);row(facts,"所属景区",runtime.uiProfile().optString("scenicName","待同步"));row(facts,"核验方式","人脸核验");resultReceipt=text(receiptText(),14,runtime.outcome().optBoolean("confirmed")?0xff00ae71:MUTED);resultReceipt.setPadding(0,dp(8),0,0);facts.addView(resultReceipt);
        LinearLayout art=card(root);art.setBackground(artwork("terminal-result-mint.png"));round(art,12);art.setGravity(android.view.Gravity.CENTER);art.setMinimumHeight(dp(130));TextView message=text(open?"祝您游览愉快":"请核对提示后重试",23,0xff064e55);message.setTypeface(null,android.graphics.Typeface.BOLD);art.addView(message);TextView wish=text(open?"一山一水皆美景，期待下次再见":"本次未放行，可联系现场工作人员",13,0xff397878);wish.setPadding(0,dp(10),0,0);art.addView(wish);
        pair(root,"返回首页",this::showAccount,"继续核验",()->{if(runtime.ready())showScan();else showSyncPage();});
    }
    @Override public void onBackPressed(){if(scanPage||resultPage||syncPage||maintenancePage){showAccount();return;}if(runtime.hasSession()){showMaintenance();return;}super.onBackPressed();}
    @Override protected void onResume() { super.onResume();if(appUpdater!=null)appUpdater.resume();foreground=true;if(license!=null&&license.state().authorized()&&(activationPage||(loginPage&&loginCompleted&&runtime.hasSession()&&!runtime.signingIn()))){authorized=true;if(runtime.hasSession())showAccount();else showLogin();}updateVoiceStatus();if(refreshHomeStatistics!=null){main.removeCallbacks(refreshHomeStatistics);refreshHomeStatistics.run();}if(refreshDiagnostics!=null){main.removeCallbacks(refreshDiagnostics);refreshDiagnostics.run();}if(accountPage){homeStatus(lastState);if(startButton!=null)startButton.setText(runtime.ready()?"开始核验":"查看同步进度");if(camera!=null&&cameraAllowed())camera.preview();} }
    @Override protected void onPause() {
        foreground=false;stopSpeech();if(refreshHomeStatistics!=null)main.removeCallbacks(refreshHomeStatistics);if(refreshDiagnostics!=null)main.removeCallbacks(refreshDiagnostics);
        if(camera!=null)camera.stop();runtime.pauseCamera();if(scanPage){setScanState(ScanState.PAUSED);if(resultView!=null)resultView.setText("已暂停核验，返回后点击开始核验");}else if(startButton!=null)startButton.setText("开始核验");super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed=true;if(appUpdater!=null)appUpdater.close();if(scanStartupTimeout!=null)main.removeCallbacks(scanStartupTimeout);resetBrandImages();if(brandImages!=null)brandImages.close();if(speechTimeout!=null)main.removeCallbacks(speechTimeout);stopSpeech();if(refreshDiagnostics!=null)main.removeCallbacks(refreshDiagnostics);if(refreshHomeStatistics!=null)main.removeCallbacks(refreshHomeStatistics);adminWorker.shutdownNow();pageVersion++;if(license!=null)license.detach(this);runtime.detach(this);if(camera!=null)camera.stop();if(speech!=null){speech.stop();speech.shutdown();}super.onDestroy();
    }
}


