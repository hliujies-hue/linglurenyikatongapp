package com.lingluren.yikatong.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Communication inventory runs without login or SDK initialization on its own worker. */
final class TerminalPresence {
    interface Listener { void changed(String text); }
    private final SharedPreferences prefs;
    private final TerminalStore store;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private String origin="",fingerprint="",license="",authorization="UNAUTHORIZED",sdkStatus="UNAUTHORIZED";
    private final java.util.concurrent.atomic.AtomicBoolean retryQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long lastAttempt,lastSuccess;
    private volatile int consecutiveFailures;
    private volatile String failureKind="";
    private volatile String licenseBackup="尚未确认算法授权备份";
    private volatile boolean checking;
    private volatile String deviceName="";
    String deviceName(){return deviceName;}
    void rememberName(String platform,String name){worker.execute(()->{if(origin.equals(platform)&&!name.isEmpty()){deviceName=name;prefs.edit().putString("deviceName:"+origin,name).apply();publish(status);}});}
    JSONObject diagnostics(){
        JSONObject out=new JSONObject();try{out.put("deviceName",deviceName).put("checking",checking||retryQueued.get()).put("lastAttempt",lastAttempt).put("lastSuccess",lastSuccess).put("consecutiveFailures",consecutiveFailures).put("failureKind",failureKind).put("licenseBackup",licenseBackup);}catch(Exception ignored){}return out;
    }
    void retry(){if(retryQueued.compareAndSet(false,true))worker.execute(()->{try{tick();}finally{retryQueued.set(false);}});}
    private volatile String status="平台通信：等待配置";
    TerminalPresence(Context context,TerminalStore store,Listener listener){
        prefs=context.getSharedPreferences("terminal_presence",Context.MODE_PRIVATE);this.store=store;this.listener=listener;
        worker.scheduleWithFixedDelay(this::tick,5+new java.util.Random().nextInt(20),30,TimeUnit.SECONDS);
    }
    String status(){return status;}
    void configure(String origin,String fingerprint){
        final String normalized=TerminalEndpointPolicy.normalizeOrigin(origin);
        worker.execute(()->{if(!this.origin.equals(normalized)){lastAttempt=0;lastSuccess=0;consecutiveFailures=0;failureKind="";licenseBackup="尚未确认当前平台的算法授权备份";}this.origin=normalized;deviceName=prefs.getString("deviceName:"+normalized,"");this.fingerprint=fingerprint;tick();});
    }
    void sdk(String authorization,String sdkStatus,String license){worker.execute(()->{String nextLicense=license==null||license.isEmpty()?this.license:license;if(this.authorization.equals(authorization)&&this.sdkStatus.equals(sdkStatus)&&this.license.equals(nextLicense))return;this.authorization=authorization;this.sdkStatus=sdkStatus;this.license=nextLicense;tick();});}
    private void tick(){
        if(origin.isEmpty()||fingerprint.isEmpty())return;
        checking=true;lastAttempt=System.currentTimeMillis();
        try {
            String key="credential:"+origin,token=store.metadata(key,"");
            if(token.isEmpty()){token=prefs.getString(key,"");if(!token.isEmpty())store.metadata(key,token,true);}
            if(prefs.contains(key)&&!prefs.edit().remove(key).commit())throw new IllegalStateException("旧版设备通信凭据清理失败，凭据已加密保存在本机记录库，请重试");
            if(token.isEmpty()){
                byte[] random=new byte[32];new SecureRandom().nextBytes(random);StringBuilder value=new StringBuilder();
                for(byte b:random)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));token=value.toString();
                store.metadata(key,token,true);
            }
            JSONObject body=new JSONObject().put("fingerprint",fingerprint).put("connectionToken",token).put("appVersion",TerminalProtocol.VERSION)
                .put("sdkVersion","8.5").put("sdkStatus",sdkStatus).put("authorizationStatus",authorization).put("license",license);
            // Original session survives logout in the journal, enabling automatic legacy upgrades.
            JSONObject proof=store.presenceProof(origin);
            if(proof!=null)body.put("sessionId",proof.getString("sessionId")).put("sessionToken",proof.getString("sessionToken")).put("generation",proof.getLong("generation"));
            JSONObject result=new PlatformClient(origin).request("/api/terminal/presence",body);
            if(!result.optBoolean("connected"))throw new IllegalStateException("平台尚未确认设备通信");
            if("AUTHORIZED".equals(authorization)&&!license.isEmpty())licenseBackup=result.optBoolean("licenseBackedUp")?"算法授权码已备份到当前平台":"平台未确认授权备份，请管理员更新平台后重试连接";
            deviceName=result.optString("deviceName");prefs.edit().putString("deviceName:"+origin,deviceName).apply();
            lastSuccess=System.currentTimeMillis();consecutiveFailures=0;failureKind="";
            publish("平台通信：在线 · "+result.optString("deviceName")+("DISABLED".equals(result.optString("deviceStatus"))?"（后台已停用核验）":""));
        }catch(Exception e){consecutiveFailures=Math.min(9999,consecutiveFailures+1);String message=TerminalMessages.friendly(e.getMessage());
            if("AUTHORIZED".equals(authorization)&&!license.isEmpty())licenseBackup="本机算法授权已保存，平台备份待重试："+message;
            failureKind=message.contains("域名")?"平台域名解析失败":message.contains("超时")?"连接超时":message.contains("证书")?"安全连接校验失败":"平台通信失败";
            publish("平台通信：未连接 · "+message);
        }finally{checking=false;}
    }
    private void publish(String text){status=text;main.post(()->listener.changed(text));}
}
