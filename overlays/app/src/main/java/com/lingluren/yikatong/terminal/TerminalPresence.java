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
    private volatile String status="平台通信：等待配置";
    TerminalPresence(Context context,TerminalStore store,Listener listener){
        prefs=context.getSharedPreferences("terminal_presence",Context.MODE_PRIVATE);this.store=store;this.listener=listener;
        worker.scheduleWithFixedDelay(this::tick,1,15,TimeUnit.SECONDS);
    }
    String status(){return status;}
    void configure(String origin,String fingerprint){
        final String normalized=TerminalEndpointPolicy.normalizeOrigin(origin);
        worker.execute(()->{this.origin=normalized;this.fingerprint=fingerprint;tick();});
    }
    void sdk(String authorization,String sdkStatus,String license){worker.execute(()->{String nextLicense=license==null||license.isEmpty()?this.license:license;if(this.authorization.equals(authorization)&&this.sdkStatus.equals(sdkStatus)&&this.license.equals(nextLicense))return;this.authorization=authorization;this.sdkStatus=sdkStatus;this.license=nextLicense;tick();});}
    private void tick(){
        if(origin.isEmpty()||fingerprint.isEmpty())return;
        try {
            String key="credential:"+origin,token=prefs.getString(key,"");
            if(token.isEmpty()){
                byte[] random=new byte[32];new SecureRandom().nextBytes(random);StringBuilder value=new StringBuilder();
                for(byte b:random)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));token=value.toString();
                if(!prefs.edit().putString(key,token).commit())throw new IllegalStateException("设备通信凭据保存失败");
            }
            JSONObject body=new JSONObject().put("fingerprint",fingerprint).put("connectionToken",token).put("appVersion",TerminalProtocol.VERSION)
                .put("sdkVersion","8.5").put("sdkStatus",sdkStatus).put("authorizationStatus",authorization).put("license",license);
            // Original session survives logout in the journal, enabling automatic legacy upgrades.
            JSONObject proof=store.presenceProof(origin);
            if(proof!=null)body.put("sessionId",proof.getString("sessionId")).put("sessionToken",proof.getString("sessionToken")).put("generation",proof.getLong("generation"));
            JSONObject result=new PlatformClient(origin).request("/api/terminal/presence",body);
            if(!result.optBoolean("connected"))throw new IllegalStateException("平台尚未确认设备通信");
            publish("平台通信：在线 · "+result.optString("deviceName")+("DISABLED".equals(result.optString("deviceStatus"))?"（后台已停用核验）":""));
        }catch(Exception e){publish("平台通信：未连接 · "+(e.getMessage()==null?"网络异常，将自动重试":e.getMessage()));}
    }
    private void publish(String text){status=text;main.post(()->listener.changed(text));}
}
