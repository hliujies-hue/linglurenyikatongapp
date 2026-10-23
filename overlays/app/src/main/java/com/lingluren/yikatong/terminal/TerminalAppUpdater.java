package com.lingluren.yikatong.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, foreground self-update. Never opens a web page or clears application data. */
final class TerminalAppUpdater {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final Activity activity;
    private volatile boolean closed, cancelled;
    private volatile HttpURLConnection connection;
    private AlertDialog progress;
    private ProgressBar progressBar;
    private TextView progressText;
    private File ready;
    private boolean awaitingPermission;
    TerminalAppUpdater(Activity activity) { this.activity=activity; }
    void start(JSONObject config) {
        final String url=config.optString("updateUrl").trim();
        final String hash=config.optString("updateSha256").trim().toLowerCase(Locale.ROOT);
        final String version=config.optString("latestVersion").trim();
        if (!hash.matches("[a-f0-9]{64}") || version.isEmpty()) { message("更新信息不完整", "请管理员上传并发布 APK，平台需要提供版本号和 SHA256。"); return; }
        if (!RUNNING.compareAndSet(false,true)) { message("正在下载", "已有更新下载任务，请等待完成。"); return; }
        cancelled=false;ready=null;
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(24*activity.getResources().getDisplayMetrics().density);box.setPadding(pad,pad/2,pad,pad);
        progressText=new TextView(activity);progressText.setTextSize(15);progressText.setTextColor(0xff536580);progressText.setText("正在连接，已有进度将继续下载");box.addView(progressText);
        progressBar=new ProgressBar(new android.view.ContextThemeWrapper(activity,android.R.style.Theme_Material_Light),null,android.R.attr.progressBarStyleHorizontal);progressBar.setMax(100);progressBar.setIndeterminate(true);progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff247bfa));box.addView(progressBar,new LinearLayout.LayoutParams(-1,pad));
        progress=dialog().setTitle("更新至 "+version).setView(box).setCancelable(false).setNegativeButton("暂停下载",(d,w)->cancel()).create();progress.show();
        new Thread(()->download(url,hash,version),"terminal-apk-update").start();
    }
    private void download(String raw,String hash,String version) {
        File file=null;
        try {
            File dir=new File(activity.getCacheDir(),"app-updates");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建更新缓存目录");
            file=new File(dir,"update-"+hash+".apk");
            File[] old=dir.listFiles();if(old!=null)for(File f:old)if(f.isFile()&&!f.equals(file))f.delete();
            if(file.isFile()&&hash.equals(sha256(file))){validate(file,version);ready=file;ui(()->{progress.dismiss();offerInstall(version);});return;}
            long offset=file.isFile()?file.length():0;
            URL url=new URL(raw);
            for(int redirects=0;;redirects++) {
                if(cancelled)throw new IOException("下载已取消");
                if(!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo()!=null || url.getRef()!=null)throw new IOException("更新地址必须是有效的 HTTPS 安装包地址");
                connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);connection.setReadTimeout(20000);
                connection.setRequestProperty("Accept-Encoding","identity");
                if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
                int status=connection.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=connection.getHeaderField("Location");connection.disconnect();
                    if(location==null||redirects>=4)throw new IOException("下载地址重定向异常");
                    url=new URL(url,location);continue;
                }
                if(status==416&&offset>0){connection.disconnect();if(!file.delete())throw new IOException("无法重置更新缓存");offset=0;continue;}
                if(status!=200&&status!=206)throw new IOException("安装包下载失败，HTTP "+status);
                break;
            }
            TerminalDownloadRange range=TerminalDownloadRange.parse(connection.getResponseCode(),offset,connection.getHeaderField("Content-Range"),connection.getHeaderField("Content-Length"));
            offset=range.offset;final long length=range.total;long limit=512L*1024*1024;
            if(length>limit)throw new IOException("安装包超过 512MB");
            if(dir.getUsableSpace()<(length>0?Math.max(0,length-offset):limit)+32L*1024*1024)throw new IOException("存储空间不足，请释放空间后重试");
            long total=offset,last=0,start=System.currentTimeMillis();
            try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(file,offset>0)) {
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1) {
                    if(cancelled)throw new IOException("下载已取消");
                    total+=n;if(total>limit)throw new IOException("安装包超过 512MB");
                    if(System.currentTimeMillis()-start>30*60*1000L)throw new IOException("下载超时，请重试");
                    out.write(buffer,0,n);
                    if(System.currentTimeMillis()-last>250) {
                        last=System.currentTimeMillis();final long done=total;
                        ui(()->{progressBar.setIndeterminate(length<=0);if(length>0)progressBar.setProgress((int)Math.min(100,done*100/length));progressText.setText(String.format(Locale.CHINA,length>0?"已下载 %.1f / %.1f MB":"已下载 %.1f MB",done/1048576.0,length/1048576.0));});
                    }
                }
            }
            if(total==0 || (length>=0&&total!=length))throw new IOException("安装包下载不完整，请重试");
            if(!hash.equals(sha256(file))){file.delete();throw new IOException("安装包校验失败，请重新下载");}
            validate(file,version);
            if(cancelled)throw new IOException("下载已取消");
            ready=file;file=null;
            ui(()->{progress.dismiss();offerInstall(version);});
        } catch(Exception e) {final boolean retained=file!=null&&file.isFile()&&file.length()>0;final String reason=e.getMessage()==null?"网络或安装包读取失败":e.getMessage();ui(()->{progress.dismiss();if(!cancelled)message("更新未完成",reason+(retained?"\n已保留下载进度，重新点击更新即可继续。":"\n请重新点击更新。"));});}
        finally {if(connection!=null)connection.disconnect();connection=null;RUNNING.set(false);}
    }
    private AlertDialog.Builder dialog(){int theme=activity.getResources().getIdentifier("YktDialogTheme","style",activity.getPackageName());return new AlertDialog.Builder(new android.view.ContextThemeWrapper(activity,theme==0?android.R.style.Theme_Material_Light_Dialog_Alert:theme));}
    private void offerInstall(String version){dialog().setTitle("更新已准备好").setMessage("版本 "+version+"，点击安装即可完成更新。").setNegativeButton("稍后",null).setPositiveButton("安装更新",(d,w)->install()).show();}
    private static String sha256(File file) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    @SuppressWarnings("deprecation")
    private void validate(File file,String version) throws Exception {
        PackageManager pm=activity.getPackageManager();
        PackageInfo next=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES);
        PackageInfo current=pm.getPackageInfo(activity.getPackageName(),PackageManager.GET_SIGNATURES);
        if(next==null||!current.packageName.equals(next.packageName))throw new IOException("安装包不是当前应用，已拒绝安装");
        if(!version.equals(next.versionName))throw new IOException("安装包版本与平台发布版本不一致");
        long nextCode=Build.VERSION.SDK_INT>=28?next.getLongVersionCode():next.versionCode;
        long currentCode=Build.VERSION.SDK_INT>=28?current.getLongVersionCode():current.versionCode;
        if(nextCode<=currentCode)throw new IOException("安装包版本号未递增，当前已安装版本无需覆盖，请管理员发布更高版本");
        if(next.applicationInfo==null||(next.applicationInfo.flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)throw new IOException("禁止安装调试版本");
        Set<Signature> a=signatures(current.signatures),b=signatures(next.signatures);
        if(a.isEmpty()||!a.equals(b))throw new IOException("安装包签名与当前应用不一致，已拒绝安装");
    }
    private static Set<Signature> signatures(Signature[] values) {return values==null?Collections.emptySet():new HashSet<>(Arrays.asList(values));}
    private void install() {
        if(ready==null||!ready.isFile()){message("安装包已失效","请重新下载更新。");return;}
        try {
            if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()) {
                dialog().setTitle("允许安装应用更新").setMessage("请在系统页面允许本应用安装更新，返回后继续安装。").setNegativeButton("取消",null).setPositiveButton("去设置",(d,w)->{
                    try{awaitingPermission=true;activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}catch(RuntimeException e){awaitingPermission=false;message("无法打开安装权限设置","请在系统设置中允许本应用安装未知应用，再重试。");}
                }).show();return;
            }
            Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".updates",ready);
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("应用更新",uri));
            activity.startActivity(intent);
        }catch(RuntimeException e){message("无法启动安装程序","请检查系统安装权限，返回版本更新页重试。" );}
    }
    void resume(){if(awaitingPermission){awaitingPermission=false;if(Build.VERSION.SDK_INT<26||activity.getPackageManager().canRequestPackageInstalls())install();else message("尚未允许安装更新","可重新点击更新并允许本应用安装未知应用。");}}
    private void ui(Runnable action){activity.runOnUiThread(()->{if(!closed&&!activity.isFinishing())action.run();});}
    private void message(String title,String body){if(!closed)dialog().setTitle(title).setMessage(body).setPositiveButton("知道了",null).show();}
    private void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    void close(){closed=true;cancel();if(progress!=null)progress.dismiss();}
}
