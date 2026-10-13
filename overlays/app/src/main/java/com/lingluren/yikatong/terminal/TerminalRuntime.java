package com.lingluren.yikatong.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.example.settinglibrary.utils.ConfigUtils;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One serial owner of camera decisions, native SDK, journal, synchronization and exit barrier. */
public final class TerminalRuntime {
    public interface Listener { void status(String text,boolean ready,boolean hasSession); void result(String text,String speech,boolean open); void communication(String text); }
    public interface Done { void done(String error); }
    public interface Stats { void done(JSONObject data,String error); }
    private static TerminalRuntime instance;
    public static synchronized TerminalRuntime get(Context context){if(instance==null)instance=new TerminalRuntime(context.getApplicationContext());return instance;}
    private final Context context;
    private final TerminalStore store;
    private final TerminalPresence presence;
    private final TerminalFaceEngine engine=new TerminalFaceEngine();
    private final TerminalAttemptGate gate=new TerminalAttemptGate();
    private final ScheduledExecutorService actor=Executors.newSingleThreadScheduledExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean frameQueued=new AtomicBoolean(),retryQueued=new AtomicBoolean();
    private volatile Listener listener;
    private volatile boolean ready,hasSession,scanning,authorized;
    private volatile long viewEpoch;
    private boolean modelFailed;
    private String lastPhotoIssue="";
    private volatile String lastStatus="正在恢复设备状态";
    private JSONObject session,feedback=new JSONObject();
    private PlatformClient client;
    private TerminalRuntime(Context context){
        this.context=context;store=new TerminalStore(context);
        presence=new TerminalPresence(context,store,text->{Listener view=listener;if(view!=null)view.communication(text);});
        actor.execute(()->{try{session=store.active();hasSession=session!=null;if(hasSession)connect();String cfg=store.metadata("feedback","{}");feedback=new JSONObject(cfg);emit(hasSession?"已恢复原账号，等待设备授权":"请激活设备后登录景区子账号");}catch(Exception e){failure(e);}});
        actor.scheduleWithFixedDelay(this::tick,2,3,TimeUnit.SECONDS);
    }
    public void attach(Listener value){listener=value;emit(lastStatus);value.communication(presence.status());}
    public void detach(Listener value){if(listener==value)listener=null;pauseCamera();}
    public void configureConnection(String origin,String fingerprint){presence.configure(origin,fingerprint);}
    public void sdkAuthorization(String state,String code){presence.sdk(state,"AUTHORIZED".equals(state)?"AUTHORIZED":"UNAUTHORIZED",code);}
    public String communicationStatus(){return presence.status();}
    public boolean hasSession(){return hasSession;}
    public boolean ready(){return ready;}
    public void authorize(){authorized=true;actor.execute(this::tick);}
    public void pauseCamera(){scanning=false;viewEpoch++;}
    public boolean startCamera(){if(!ready)return false;scanning=true;viewEpoch++;actor.execute(gate::reset);return true;}
    private void connect() throws Exception {client=new PlatformClient(session.getString("origin"));client.token(session.optString("accessToken"));}
    public void login(String origin,String fingerprint,String username,String password,Done done){
        pauseCamera();actor.execute(()->{try {
            if(!authorized)throw new IllegalStateException("请先完成设备激活");
            if(session!=null&&(!origin.equals(session.getString("origin"))||!username.equals(session.getString("username"))))throw new IllegalStateException("请先在原账号完成同步退出，再切换账号或平台");
            PlatformClient next=new PlatformClient(origin);
            JSONObject info=next.request("/api/terminal/login",new JSONObject().put("username",username).put("password",password));
            next.token(info.getString("accessToken"));
            JSONObject binding;
            try{binding=next.request("/api/terminal/bind",new JSONObject().put("protocolVersion",2).put("fingerprint",fingerprint).put("appVersion",TerminalProtocol.VERSION));}
            catch(Exception e){next.logout();throw e;}
            binding.put("origin",origin).put("fingerprint",fingerprint).put("username",username).put("accessToken",info.getString("accessToken"));
            store.bind(binding);session=store.active();hasSession=true;client=next;ready=false;emit("账号已登录，正在同步人员与记录");complete(done,null);tick();
        }catch(Exception e){complete(done,safe(e));}});
    }
    private void prepare() throws Exception {
        if(!authorized)throw new IllegalStateException("等待设备激活");
        if(modelFailed)throw new IllegalStateException("算法加载失败，请重启应用后重试；原账号记录已保留");
        if(!engine.ready()){
            emit("正在加载算法和本地人员库，请稍候");presence.sdk("AUTHORIZED","MODEL_LOADING",null);
            try{if(!ConfigUtils.isConfigExit(context)||!ConfigUtils.initConfig(context))throw new IllegalStateException("算法配置读取失败");engine.init(context,store);presence.sdk("AUTHORIZED","READY",null);}catch(Exception|LinkageError e){modelFailed=true;presence.sdk("AUTHORIZED","ERROR",null);throw new IllegalStateException("算法加载失败："+safe(e));}
        }
        if(!session.optBoolean("resetDone")){
            engine.reset(store);
            JSONObject ack=client.request("/api/terminal/recovery/reset/ack",TerminalProtocol.ref(session));
            if(!ack.optBoolean("acknowledged"))throw new IllegalStateException("本地库重置尚未获平台确认");
            store.resetDone(session.getString("sessionId"));session=store.active();
        }
    }
    private void syncPeople() throws Exception {
        JSONArray tasks=client.request("/api/terminal/recovery/sync",TerminalProtocol.ref(session)).getJSONArray("items");
        if(tasks.length()>0)ready=false;
        long batchStarted=SystemClock.elapsedRealtime();
        for(int i=0;i<tasks.length()&&i<4;i++){
            if(i>0&&SystemClock.elapsedRealtime()-batchStarted>1500)break;
            emit("正在同步人员，本批第"+(i+1)+"项；本地人员："+store.faces());
            JSONObject task=tasks.getJSONObject(i),ack=TerminalProtocol.ref(session);
            ack.put("taskId",task.getLong("taskId")).put("receipt",task.getString("receipt")).put("targetHash",task.getString("targetHash")).put("grantGeneration",task.getLong("grantGeneration"));
            try {
                String op=task.getString("operation");long user=task.getLong("userId");
                if("UPSERT".equals(op))engine.upsert(store,user,task.getString("targetHash"),client.photo(task.getString("photoUrl")));
                else if("DELETE".equals(op))engine.delete(store,user);
                else if(!"DISCARD".equals(op))throw new IllegalStateException("未知人员同步任务");
                ack.put("applied",true);
            }catch(TerminalFaceEngine.PhotoRejectedException e){
                // Remove any previous template before recording a rejected photo.
                // A rejection is a completed failure receipt, never a successful face grant.
                engine.delete(store,task.getLong("userId"));
                String person=task.optString("personName","待核对人员");
                lastPhotoIssue=person+"："+safe(e);
                ack.put("applied",false).put("photoRejected",true).put("localFaceRemoved",true).put("error",lastPhotoIssue);
                JSONObject rejected=client.request("/api/terminal/recovery/sync/ack",ack);
                if(!rejected.optBoolean("acknowledged")||!rejected.optBoolean("photoRejected"))throw new IllegalStateException("照片失败回执未确认，请先更新配套平台");
                emit("照片待处理："+lastPhotoIssue+"\n该人员不能刷脸，继续同步其他人员；后台更换照片后重试");
                continue;
            }catch(Exception e){ack.put("applied",false).put("error",safe(e));client.request("/api/terminal/recovery/sync/ack",ack);throw e;}
            if(!client.request("/api/terminal/recovery/sync/ack",ack).optBoolean("acknowledged"))throw new IllegalStateException("人员任务回执尚未确认");
        }
    }
    private void acknowledge(JSONObject e,JSONObject response) throws Exception {
        JSONObject ack=TerminalProtocol.ref(e).put("eventId",e.getString("eventId")).put("sequence",e.getLong("sequence")).put("receipt",response.getString("receipt"));
        if(!client.request("/api/terminal/recovery/events/ack",ack).optBoolean("acknowledged"))throw new IllegalStateException("核验回执尚未确认");store.confirmed(e);
    }
    private void upload() throws Exception {
        for(JSONObject e:store.pending(session.getString("sessionId"))){
            JSONObject result=e.optJSONObject("savedResponse");e.remove("savedResponse");
            if(result==null){result=client.request("/api/terminal/recovery/events",e);store.receipt(e,result);}
            acknowledge(e,result); // Recovery never displays PASS or repeats speech.
        }
    }
    private void tick(){
        if(session==null||!authorized)return;
        try {
            // Events can still drain even if the local algorithm cannot initialize.
            upload();
            // Retry release first: the server may have committed exit before its response was lost.
            if("DRAINING".equals(session.getString("state"))){
                finishDrain();return;
            }
            prepare();presence.sdk("AUTHORIZED",engine.ready()?"READY":"ERROR",null);
            if("PAUSING".equals(session.getString("state")))drain();
            syncPeople();
            if("DRAINING".equals(session.getString("state"))){finishDrain();return;}
            JSONObject heartbeat=TerminalProtocol.ref(session).put("appVersion",TerminalProtocol.VERSION).put("sdkStatus",engine.ready()?"READY":"ERROR").put("localFaces",store.faces()).put("pendingEvents",store.pendingCount(session.getString("sessionId")));
            JSONObject result=client.request("/api/terminal/heartbeat",heartbeat);
            ready=result.optBoolean("productionReady");
            if(result.optJSONObject("feedback")!=null){feedback=result.getJSONObject("feedback");store.metadata("feedback",feedback.toString(),true);}
            store.cleanup(System.currentTimeMillis());
            emit("设备："+session.optString("deviceName")+"\n账号："+session.optString("username")+"\n人员："+store.faces()+" · 待确认记录："+store.pendingCount(session.getString("sessionId"))+"\n"+(ready?"平台通信正常，可以开始核验":"正在同步，暂不可通行")+(result.optLong("photoRejected")>0?"\n照片待处理："+result.optLong("photoRejected")+"人，不影响已同步人员；请后台更换照片后重试"+(lastPhotoIssue.isEmpty()?"":"\n最近问题："+lastPhotoIssue):""));
        }catch(Exception|LinkageError e){ready=false;emit(safe(e)+"\n记录保留，将自动重试；暂不可通行");}
    }
    public void retry(){if(retryQueued.compareAndSet(false,true)){emit("同步重试已安排，正在处理原账号任务；记录会保留");actor.execute(()->{try{tick();}finally{retryQueued.set(false);}});}}
    public void exit(Done done){
        pauseCamera();ready=false;
        actor.execute(()->{try{
            if(session==null){complete(done,null);return;}
            // reset ack must precede drain when exiting an as-yet unsynchronized login.
            if("DRAINING".equals(session.getString("state"))){tick();complete(done,session==null?null:"正在完成原账号同步，请稍候");return;}
            store.state(session.getString("sessionId"),"PAUSING");session=store.active();
            prepare();drain();
            upload();finishDrain();
            complete(done,session==null?null:"正在完成原账号同步，请稍候");
        }catch(Exception|LinkageError e){complete(done,safe(e)+"；原账号保留，请重试同步退出");}});
    }
    private void drain() throws Exception {
        JSONObject current=store.active();client.request("/api/terminal/recovery/drain",TerminalProtocol.ref(current).put("lastSequence",current.getLong("lastSequence")));
        store.state(current.getString("sessionId"),"DRAINING");session=store.active();
    }
    private void finishDrain() throws Exception {
        ready=false;
        boolean released=TerminalDrainCoordinator.run(new TerminalDrainCoordinator.Work(){
            public void release() throws Exception {tryRelease();}
            public void sync() throws Exception {prepare();syncPeople();}
        });
        if(!released){
            JSONObject current=store.active();
            JSONObject progress=client.request("/api/terminal/recovery/drain",TerminalProtocol.ref(current).put("lastSequence",current.getLong("lastSequence")));
            String problem=progress.optString("lastSyncError");
            emit("正在同步后退出，完成后自动返回登录\n剩余人员任务："+progress.optLong("pendingPeople",progress.optLong("pendingSync"))+" · 人员回执："+progress.optLong("pendingReceipts")+"\n待确认入园记录："+store.pendingCount(current.getString("sessionId"))+(problem.isEmpty()?"":"\n同步失败原因："+problem)+"\n可重试同步、查看统计或连接设置；暂不可通行");
        }
    }
    private void tryRelease() throws Exception {
        JSONObject current=store.active();
        JSONObject result=client.request("/api/terminal/recovery/release",TerminalProtocol.ref(current).put("lastSequence",current.getLong("lastSequence")));
        if(!result.optBoolean("released"))throw new IllegalStateException("原账号同步尚未完成");
        store.state(current.getString("sessionId"),"RELEASED");client.logout();session=null;hasSession=false;ready=false;scanning=false;emit("同步与回执已完成，可以登录其他账号");
    }
    public void frame(byte[] bytes,int width,int height,int rotation){
        if(!scanning||!authorized||!engine.ready()||!frameQueued.compareAndSet(false,true))return;
        final long epoch=viewEpoch;byte[] copy=bytes.clone();
        actor.execute(()->{try{
            if(!scanning||epoch!=viewEpoch||session==null||!"ACTIVE".equals(session.getString("state")))return;
            TerminalFaceEngine.Match match=engine.identify(store,copy,width,height,rotation);long now=SystemClock.elapsedRealtime();
            if(!match.present){gate.absent(now);return;}if(!gate.face(now))return;
            boolean online=ready&&match.failure.isEmpty();
            String failure=!ready?"NETWORK_UNAVAILABLE":match.failure.isEmpty()?"PLATFORM_TIMEOUT":match.failure;
            JSONObject event=store.event(match.user,failure),result=null;boolean saved=false;
            try{
                result=client.request(online?"/api/terminal/verify":"/api/terminal/recovery/events",event);
                store.receipt(event,result);saved=true;
                boolean open=online&&TerminalProtocol.canOpen(result);
                showResult(epoch,result.optBoolean("duplicate")?"该记录已处理，请移开后重新核验":result.optString("displayText",open?"核验成功，请通行":"暂不可通行"),result.optBoolean("duplicate")?"":result.optString("speechText"),open);
                acknowledge(event,result);
            }catch(Exception e){
                if(!saved){ready=false;localFeedback(epoch,"network","网络异常，暂不可通行");}
                // A saved first response is never re-presented if only its ACK failed.
                emit("记录等待补传或确认："+safe(e));
            }
        }catch(Exception|LinkageError e){ready=false;localFeedback(epoch,"sdk","设备尚未就绪，请联系工作人员");failure(e);}
        finally{frameQueued.set(false);}});
    }
    private void localFeedback(long epoch,String key,String fallback){JSONObject messages=feedback.optJSONObject("messages"),item=messages==null?null:messages.optJSONObject(key);showResult(epoch,item==null?fallback:item.optString("text",fallback),feedback.optBoolean("voiceEnabled",true)?(item==null?fallback:item.optString("speech")):"",false);}
    private void showResult(long epoch,String text,String speech,boolean open){main.post(()->{Listener l=listener;if(l!=null&&scanning&&epoch==viewEpoch)l.result(text,speech,open);});}
    public void statistics(String from,String to,Stats done){actor.execute(()->{try{if(session==null)throw new IllegalStateException("请先登录");JSONObject result=client.statistics(from,to);main.post(()->done.done(result,null));}catch(Exception e){main.post(()->done.done(null,safe(e)));}});}
    private void failure(Throwable e){emit(safe(e));}
    private static String safe(Throwable e){String m=e.getMessage();return m==null||m.length()>180?"设备操作失败，请检查连接和算法状态":m;}
    private void complete(Done done,String error){main.post(()->done.done(error));}
    private void emit(String text){lastStatus=text;boolean active=hasSession,can=ready;main.post(()->{Listener l=listener;if(l!=null)l.status(text,can,active);});}
}
