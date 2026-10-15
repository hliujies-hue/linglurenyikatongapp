package com.lingluren.yikatong.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.baidu.idl.main.facesdk.utils.PreferencesUtil;
import com.example.authlibrary.BdFaceAuth;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Application-context owner survives Activity rotation; one vendor activation at a time. */
final class TerminalLicenseManager {
    interface Listener {void changed(TerminalActivationGate.State state);}
    private static TerminalLicenseManager instance;
    static synchronized TerminalLicenseManager get(Context context){if(instance==null)instance=new TerminalLicenseManager(context.getApplicationContext());return instance;}
    private final Context context;
    private final TerminalActivationGate gate=new TerminalActivationGate();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private BdFaceAuth auth;
    private volatile Listener listener;
    private volatile boolean fingerprintReady;
    private volatile long lastAttempt;
    private TerminalLicenseManager(Context context){this.context=context;}
    TerminalActivationGate.State state(){return gate.state();}
    boolean fingerprintReady(){return fingerprintReady;}
    long lastAttempt(){return lastAttempt;}
    String savedCode(){
        String saved=context.getSharedPreferences("terminal_license",Context.MODE_PRIVATE).getString("successful_code", "");
        return saved.isEmpty()?PreferencesUtil.getString("activate_online_key", ""):saved;
    }
    void attach(Listener value){listener=value;publish();}
    void detach(Listener value){if(listener==value)listener=null;}
    private void publish(){main.post(()->{Listener view=listener;if(view!=null)view.changed(gate.state());});}
    boolean start(String raw){
        String code=TerminalActivationGate.normalizeCode(raw);long request=gate.start();if(request==0)return false;
        lastAttempt=System.currentTimeMillis();fingerprintReady=false;
        TerminalRuntime.get(context).sdkAuthorization("CHECKING",null);publish();
        worker.execute(()->prepare(request,code));return true;
    }
    private void prepare(long request,String code){
        if(gate.state().generation!=request||!gate.state().phase.equals("PREPARING"))return;
        try{
            if(auth==null)auth=new BdFaceAuth();
            String device=auth.getDeviceId(context); // Vendor LH path only; never Android ID, random IDs or copied fingerprints.
            fingerprintReady=device!=null&&!device.trim().isEmpty();
            int action=gate.fingerprint(request,fingerprintReady);publish();
            if(action==TerminalActivationGate.RETRY){worker.schedule(()->prepare(request,code),1,TimeUnit.SECONDS);return;}
            if(action==TerminalActivationGate.FAILED){TerminalRuntime.get(context).sdkAuthorization("ERROR",null);TerminalRuntime.get(context).activationFailed(TerminalActivationGate.describe(1007));return;}
            if(action!=TerminalActivationGate.READY)return;
            worker.schedule(()->{if(gate.slow(request))publish();},60,TimeUnit.SECONDS);
            auth.initLicenseOnLine(context,code,(error,message)->worker.execute(()->complete(request,code,error)));
        }catch(LinkageError e){complete(request,code,-2001);}catch(RuntimeException e){complete(request,code,-2002);}
    }
    private void complete(long request,String code,int error){
        TerminalActivationGate.State state=gate.state();
        if(state.generation!=request||!state.busy())return;
        if(error==0){try{
            if(!context.getSharedPreferences("terminal_license",Context.MODE_PRIVATE).edit().putString("successful_code",code).commit())error=-2003;
            if(error==0)PreferencesUtil.putString("activate_online_key",code);
        }catch(RuntimeException e){error=-2003;}}
        if(!gate.finish(request,error))return;
        TerminalRuntime runtime=TerminalRuntime.get(context);
        if(error==0){runtime.sdkAuthorization("AUTHORIZED",code);runtime.authorize();}
        else {runtime.sdkAuthorization("ERROR",null);runtime.activationFailed(TerminalActivationGate.describe(error));}
        publish();
    }
}
