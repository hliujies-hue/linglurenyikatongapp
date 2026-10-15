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

/** Runtime actor owns sessions/network/journal; algorithm queue alone owns native SDK calls. */
public final class TerminalRuntime {
    public interface Listener { void status(String text,boolean ready,boolean hasSession); void result(String text,String speech,boolean open,String type); void communication(String text); void diagnostics(String text); }
    public interface Done { void done(String error); }
    public interface Stats { void done(JSONObject data,String error); }
    private static TerminalRuntime instance;
    public static synchronized TerminalRuntime get(Context context){if(instance==null)instance=new TerminalRuntime(context.getApplicationContext());return instance;}
    private final Context context;
    private final TerminalStore store;
    private final TerminalPresence presence;
    private final TerminalFaceEngine engine=new TerminalFaceEngine();
    private final TerminalAlgorithmQueue algorithms=new TerminalAlgorithmQueue();
    private final TerminalAttemptGate gate=new TerminalAttemptGate();
    private final ScheduledExecutorService actor=Executors.newSingleThreadScheduledExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean frameQueued=new AtomicBoolean(),retryQueued=new AtomicBoolean();
    private volatile Listener listener;
    private volatile boolean ready,hasSession,scanning,authorized;
    private volatile long viewEpoch;
    private boolean modelFailed,restoreContinuationQueued;
    private boolean pendingPeople=true;
    private long nextTick;
    private int syncFailures;
    private final java.util.Random jitter=new java.util.Random();
    private volatile boolean settingsOpen,platformSynced;
    private volatile boolean rosterReady;
    private volatile String syncStep="尚未开始同步";
    private long syncStepSince=SystemClock.elapsedRealtime();
    private long lastSyncRequestMs,lastPersonnelBatchMs,lastPersonnelAckMs;
    private void syncStep(String value){if(!value.equals(syncStep)){syncStep=value;syncStepSince=SystemClock.elapsedRealtime();}}
    private volatile long lastHeartbeat,lastHeartbeatWall,pendingEvents;
    private long lastLoadNotice;
    private volatile String overview="平台信息：等待同步";
    private String lastPhotoIssue="";
    private volatile long lastGuidance,lastDiagnostics;
    private volatile String lastStatus="正在恢复设备状态";
    private volatile JSONObject session;
    private JSONObject feedback=new JSONObject();
    private PlatformClient client;
    // Credentials are kept only for this in-flight login; passwords are never stored.
    private PendingLogin pendingLogin;
    private volatile boolean signingIn;
    private static final class PendingLogin {
        final PlatformClient client;final JSONObject info;final String origin,fingerprint,username;final Done done;
        PendingLogin(PlatformClient c,JSONObject i,String o,String f,String u,Done d){client=c;info=i;origin=o;fingerprint=f;username=u;done=d;}
    }
    public boolean signingIn(){return signingIn;}
    private TerminalRuntime(Context context){
        this.context=context;store=new TerminalStore(context);
        store.migrationListener((table,count)->emit("正在升级本机数据加密："+("faces".equals(table)?"人脸特征":"events".equals(table)?"核验记录":"sessions".equals(table)?"账号会话":"设备配置")+"，已处理至编号 "+count+"；请保持应用运行，完成后自动恢复"));
        presence=new TerminalPresence(context,store,text->{Listener view=listener;if(view!=null)view.communication(text);});
        actor.execute(()->{try{session=store.active();hasSession=session!=null;if(hasSession)connect();String cfg=store.metadata("feedback","{}");feedback=new JSONObject(cfg);emit(hasSession?"已恢复原账号，等待设备授权":"请登录景区子账号，已激活设备将自动恢复算法授权");}catch(Exception e){failure(e);}});
        actor.scheduleWithFixedDelay(this::tick,1+jitter.nextInt(5),1,TimeUnit.SECONDS);
    }
    public void attach(Listener value){listener=value;emit(lastStatus);value.communication(presence.status());}
    public void detach(Listener value){if(listener==value)listener=null;pauseCamera();}
    public void configureConnection(String origin,String fingerprint){presence.configure(origin,fingerprint);}
    public void sdkAuthorization(String state,String code){presence.sdk(state,"AUTHORIZED".equals(state)?"AUTHORIZED":"UNAUTHORIZED",code);}
    public String communicationStatus(){return presence.status();}
    public void retryConnection(){presence.retry();if(hasSession)retry();}
    public void diagnostics(Stats done){actor.execute(()->{try{
        JSONObject out=presence.diagnostics();
        out.put("appVersion",TerminalProtocol.VERSION).put("authorized",authorized).put("hasSession",hasSession)
            .put("sdkReady",engine.ready()).put("modelFailed",modelFailed).put("loading",engine.loading())
            .put("rosterReady",rosterReady).put("ready",ready()).put("settingsOpen",settingsOpen).put("platformSynced",platformSynced)
            .put("localFaces",store.faces()).put("loadedFaces",loadedFaces).put("loadTotal",loadTotal).put("loadElapsed",loadElapsed)
            .put("lastHeartbeat",lastHeartbeatWall).put("heartbeatAgeSeconds",lastHeartbeat==0?-1:(SystemClock.elapsedRealtime()-lastHeartbeat)/1000)
            .put("pendingEvents",session==null?0:store.pendingCount(session.getString("sessionId")))
            .put("syncStep",syncStep).put("syncStepSeconds",Math.max(0,(SystemClock.elapsedRealtime()-syncStepSince)/1000)).put("lastSyncRequestMs",lastSyncRequestMs).put("lastPersonnelBatchMs",lastPersonnelBatchMs).put("lastPersonnelAckMs",lastPersonnelAckMs).put("syncFailures",syncFailures).put("retrySeconds",Math.max(0,(nextTick-SystemClock.elapsedRealtime()+999)/1000))
            .put("sync",uiProfile());
        // Whitelist only operational counts; no tokens, face features, people or event payloads.
        JSONObject profile=out.getJSONObject("sync");JSONObject counts=new JSONObject();
        for(String key:new String[]{"pendingPeople","pendingReceipts","photoRejected","syncOperations","roster"})if(profile.has(key))counts.put(key,profile.get(key));
        out.put("sync",counts);main.post(()->done.done(out,null));
    }catch(Exception e){main.post(()->done.done(null,"设备诊断暂不可用，请重试"));}});}
    public boolean hasSession(){return hasSession;}
    public boolean ready(){return TerminalSdkContract.canStart(ready,platformSynced,settingsOpen,pendingEvents,lastHeartbeat,SystemClock.elapsedRealtime());}
    public String overview(){return overview;}
    private volatile JSONObject uiProfile=new JSONObject();
    private volatile long loadedFaces,loadTotal,loadElapsed;
    public JSONObject uiProfile(){try{return new JSONObject(uiProfile.toString());}catch(Exception e){return new JSONObject();}}
    public boolean libraryReady(){return engine.ready();} public boolean libraryFailed(){return modelFailed;}
    public long loadedFaces(){return loadedFaces;} public long loadTotal(){return loadTotal;} public long loadElapsed(){return loadElapsed;}

    public void beginSettings(Done done){
        settingsOpen=true;ready=false;pauseCamera();
        actor.execute(()->{try{
            algorithms.call(()->{if(!ConfigUtils.isConfigExit(context)||!ConfigUtils.initConfig(context))throw new IllegalStateException("算法配置读取失败");return null;});
            complete(done,null);
        }catch(Exception e){settingsOpen=false;complete(done,safe(e));}});
    }
    public void endSettings(){
        actor.execute(()->{try{
            algorithms.call(()->{if(!ConfigUtils.isConfigExit(context)||!ConfigUtils.initConfig(context))throw new IllegalStateException("算法配置读取失败");return null;});
            if(engine.ready())algorithms.call(()->{engine.applySettings();return null;});
            settingsOpen=false;ready=false;nextTick=0;emit("算法设置已读取，正在重新同步平台状态；请手动开始核验");tick();
        }catch(Exception e){ready=false;settingsOpen=false;modelFailed=true;failure(e);}});
    }
    public void authorize(){authorized=true;actor.execute(()->{if(pendingLogin!=null)finishLogin(pendingLogin);nextTick=0;tick();});}
    public void activationFailed(String error){actor.execute(()->{if(pendingLogin!=null)failLogin(pendingLogin,error);});}
    public void pauseCamera(){scanning=false;viewEpoch++;}
    public boolean startCamera(){if(!ready())return false;scanning=true;viewEpoch++;algorithms.offer(gate::reset);return true;}
    public void nextAttempt(){algorithms.offer(()->{gate.reset();lastGuidance=0;showResult(viewEpoch,"已开始新一轮，请正对镜头保持稳定","",false);});}
    private void connect() throws Exception {client=new PlatformClient(session.getString("origin"));client.token(session.optString("accessToken"));}
    public synchronized void login(String origin,String fingerprint,String username,String password,Done done){
        if(signingIn){complete(done,"上一笔登录和授权恢复仍在处理中，请等待当前结果");return;}
        signingIn=true;
        pauseCamera();actor.execute(()->{
          PlatformClient next=null;
          try {
            if(TerminalLicenseManager.get(context).state().busy())throw new IllegalStateException("正在校验本机算法授权，请等待校验完成后登录");
            if(session!=null&&(!origin.equals(session.getString("origin"))||!username.equals(session.getString("username"))))throw new IllegalStateException("请先在原账号完成同步退出，再切换账号或平台");
            next=new PlatformClient(origin);
            JSONObject info=next.request("/api/terminal/login",new JSONObject().put("username",username).put("password",password));
            next.token(info.getString("accessToken"));
            PendingLogin job=new PendingLogin(next,info,origin,fingerprint,username,done);
            if(!authorized){
                String code=TerminalLicenseManager.get(context).savedCode();
                if(code.isEmpty()){
                    emit("账号已验证，正在恢复本机算法授权…");
                    JSONObject restored;
                    try{restored=next.request("/api/terminal/license/restore",new JSONObject().put("fingerprint",fingerprint));}
                    catch(PlatformClient.ApiException e){if(e.status==404)throw new IllegalStateException("平台尚未提供算法授权恢复接口，请管理员先更新平台后重新登录");throw e;}
                    code=restored.getString("license");
                }
                code=TerminalActivationGate.normalizeCode(code);
                pendingLogin=job;
                if(!TerminalLicenseManager.get(context).start(code))throw new IllegalStateException("本机算法授权已有校验任务，请等待完成后登录");
                return;
            }
            pendingLogin=job;finishLogin(job);
          }catch(Exception e){pendingLogin=null;if(next!=null)next.logout();signingIn=false;String error=safe(e);emit(error);complete(done,error);}
        });
    }
    private void failLogin(PendingLogin job,String error){
        if(pendingLogin!=job)return;pendingLogin=null;job.client.logout();signingIn=false;emit(error);complete(job.done,error);
    }
    private void finishLogin(PendingLogin job){
        if(pendingLogin!=job)return;
        PlatformClient next=job.client;JSONObject info=job.info;String origin=job.origin,fingerprint=job.fingerprint,username=job.username;
        try{
            if(!authorized)throw new IllegalStateException("算法授权尚未通过校验，无法开始核验会话");
            if(!store.libraryBelongsTo(origin)){algorithms.call(()->{if(engine.ready())engine.reset(store);engine.invalidateLibrary();return null;});store.switchLibrary(origin);}
            String libraryId=store.libraryId(origin);
            JSONObject binding=next.request("/api/terminal/bind",new JSONObject().put("protocolVersion",2).put("fingerprint",fingerprint).put("appVersion",TerminalProtocol.VERSION).put("libraryId",libraryId));
            if(!"SHARED".equals(binding.optString("libraryMode"))||!libraryId.equals(binding.optString("libraryId"))){throw new IllegalStateException("平台未确认共享人员库，请先更新平台");}
            binding.put("origin",origin).put("fingerprint",fingerprint).put("username",username).put("accessToken",info.getString("accessToken"));
            store.bind(binding);session=store.active();hasSession=true;client=next;ready=false;pendingPeople=true;nextTick=0;syncFailures=0;platformSynced=false;rosterReady=false;lastHeartbeat=0;lastHeartbeatWall=0;uiProfile=new JSONObject();overview="账号："+username+"\n平台信息：等待同步";pendingLogin=null;signingIn=false;presence.retry();emit("账号已登录，正在同步人员与记录");complete(job.done,null);tick();
        }catch(Exception e){failLogin(job,safe(e));}
    }
    private void ensureSharedLibrary() throws Exception {
        if("SHARED".equals(session.optString("libraryMode"))){
            if(!session.optString("libraryId").equals(store.metadata("library_id",""))||!session.optString("origin").equals(store.metadata("library_origin","")))throw new IllegalStateException("共享人员库标识不一致，请联系平台核对，禁止清除数据");
            return;
        }
        JSONObject binding=client.request("/api/terminal/bind",new JSONObject().put("protocolVersion",2)
            .put("fingerprint",session.getString("fingerprint")).put("appVersion",TerminalProtocol.VERSION)
            .put("libraryId",store.libraryId(session.getString("origin"))));
        if(!"SHARED".equals(binding.optString("libraryMode"))||!store.metadata("library_id", "").equals(binding.optString("libraryId")))throw new IllegalStateException("请先更新配套平台以启用共享人员库");
        binding.put("origin",session.getString("origin")).put("fingerprint",session.getString("fingerprint"))
            .put("username",session.getString("username")).put("accessToken",session.getString("accessToken"));
        store.bind(binding);session=store.active();platformSynced=false;pendingPeople=true;ready=false;
    }
    private boolean prepare() throws Exception {
        if(!authorized)throw new IllegalStateException("等待设备激活");
        if(modelFailed)throw new IllegalStateException("算法加载失败，请重启应用后重试；原账号记录已保留");
        if(!engine.ready()){
            try{
                TerminalFaceEngine.Progress progress=(loaded,total,elapsed)->{loadedFaces=loaded;loadTotal=total;loadElapsed=elapsed;long now=SystemClock.elapsedRealtime();if(loaded==0||loaded==total||now-lastLoadNotice>=500){lastLoadNotice=now;emit("正在加载本地特征："+loaded+" / "+total+"，已用时 "+elapsed/1000+" 秒；无需重新下载照片");}};
                if(!engine.loading()){
                    emit("正在初始化领路人脸算法，请稍候");presence.sdk("AUTHORIZED","MODEL_LOADING",null);
                    algorithms.call(()->{if(!ConfigUtils.isConfigExit(context)||!ConfigUtils.initConfig(context))throw new IllegalStateException("算法配置读取失败");return null;});
                    algorithms.call(()->{engine.init(context,store,progress);return null;});
                }
                algorithms.call(()->{engine.restoreBatch(store,progress);return null;});
                if(!engine.ready())return false;
                algorithms.call(()->{engine.applySettings();return null;});presence.sdk("AUTHORIZED","READY",null);
            }catch(Exception|LinkageError e){modelFailed=true;presence.sdk("AUTHORIZED","ERROR",null);throw new IllegalStateException("算法加载失败："+safe(e));}
        }
        if(!session.optBoolean("resetDone")){
            if(!"SHARED".equals(session.optString("libraryMode")))algorithms.call(()->{engine.reset(store);return null;});
            JSONObject ack=client.request("/api/terminal/recovery/reset/ack",TerminalProtocol.ref(session));
            if(!ack.optBoolean("acknowledged"))throw new IllegalStateException("本地人员库就绪尚未获平台确认");
            store.resetDone(session.getString("sessionId"));session=store.active();
        }
        return true;
    }
    private void syncPeople() throws Exception {
        long started=SystemClock.elapsedRealtime();
        try{syncPeopleBatch();}finally{lastPersonnelBatchMs=SystemClock.elapsedRealtime()-started;}
    }
    private void syncPeopleBatch() throws Exception {
        JSONArray tasks=client.request("/api/terminal/recovery/sync",TerminalProtocol.ref(session).put("pageSize",16)).getJSONArray("items");
        if(tasks.length()>0)ready=false;else pendingPeople=false;
        long batchStarted=SystemClock.elapsedRealtime();
        JSONArray receipts=new JSONArray();Exception failure=null;
        for(int i=0;i<tasks.length()&&i<16;i++){
            if(i>0&&SystemClock.elapsedRealtime()-batchStarted>1500)break;
            emit("正在同步人员，本批第"+(i+1)+"项；本地已保存特征："+store.faces());
            JSONObject task=tasks.getJSONObject(i),ack=TerminalProtocol.ref(session);
            ack.put("taskId",task.getLong("taskId")).put("receipt",task.getString("receipt")).put("targetHash",task.getString("targetHash")).put("grantGeneration",task.getLong("grantGeneration"));
            try {
                String op=task.getString("operation");long user=task.getLong("userId");
                if("UPSERT".equals(op)){
                    String hash=task.getString("targetHash");
                    if(!algorithms.call(()->engine.reuse(store,user,hash))){
                        byte[] photo=client.photo(task.getString("photoUrl"));
                        algorithms.call(()->{engine.upsert(store,user,hash,photo);return null;});
                    }
                }
                else if("DELETE".equals(op))algorithms.call(()->{engine.delete(store,user);return null;});
                else if(!"DISCARD".equals(op))throw new IllegalStateException("未知人员同步任务");
                ack.put("applied",true);
            }catch(TerminalFaceEngine.PhotoRejectedException e){
                // Remove any previous template before recording a rejected photo.
                // A rejection is a completed failure receipt, never a successful face grant.
                algorithms.call(()->{engine.delete(store,task.getLong("userId"));return null;});
                String person=task.optString("personName","待核对人员");
                lastPhotoIssue=person+"："+safe(e);
                ack.put("applied",false).put("photoRejected",true).put("localFaceRemoved",true).put("error",lastPhotoIssue);
                receipts.put(ack);
                emit("照片待处理："+lastPhotoIssue+"\n该人员不能刷脸，继续同步其他人员；后台更换照片后重试");
                continue;
            }catch(Exception e){ack.put("applied",false).put("error",safe(e));receipts.put(ack);failure=e;break;}
            receipts.put(ack);
        }
        if(receipts.length()>0){
            long ackStarted=SystemClock.elapsedRealtime();
            try{
                JSONArray results=client.request("/api/terminal/recovery/sync/ack-batch",TerminalProtocol.ref(session).put("items",receipts)).getJSONArray("items");
                if(results.length()!=receipts.length())throw new IllegalStateException("平台批量回执数量不一致，保留本地特征并重试");
                for(int i=0;i<results.length();i++)if(results.getJSONObject(i).optLong("taskId")!=receipts.getJSONObject(i).getLong("taskId")||!results.getJSONObject(i).optBoolean("acknowledged")){
                    if(failure==null)throw new IllegalStateException("人员任务回执尚未确认，将自动重试");
                }
            }finally{lastPersonnelAckMs=SystemClock.elapsedRealtime()-ackStarted;}
        }
        if(failure!=null)throw failure;
    }
    private void acknowledge(JSONObject e,JSONObject response) throws Exception {
        JSONObject ack=TerminalProtocol.ref(e).put("eventId",e.getString("eventId")).put("sequence",e.getLong("sequence")).put("receipt",response.getString("receipt"));
        if(!client.request("/api/terminal/recovery/events/ack",ack).optBoolean("acknowledged"))throw new IllegalStateException("核验回执尚未确认");store.confirmed(e);pendingEvents=store.pendingCount(session.getString("sessionId"));if(e.optString("eventId").equals(latestOutcome.optString("eventId"))){latestOutcome=new JSONObject(latestOutcome.toString()).put("confirmed",true);emit(lastStatus);}
    }
    private void upload() throws Exception {
        for(JSONObject e:store.pending(session.getString("sessionId"))){
            JSONObject result=e.optJSONObject("savedResponse");e.remove("savedResponse");
            if(result==null){result=client.request("/api/terminal/recovery/events",e);store.receipt(e,result);}
            if(e.optString("eventId").equals(latestOutcome.optString("eventId"))){latestOutcome=new JSONObject(result.toString()).put("occurredAt",e.optString("occurredAt")).put("confirmed",false);emit(lastStatus);}
            todayCache=null;acknowledge(e,result); // Recovery only refreshes receipt status, never displays PASS or repeats speech.
        }
    }
    private void heartbeat(boolean allowReady) throws Exception {
        pendingEvents=store.pendingCount(session.getString("sessionId"));
        JSONObject request=TerminalProtocol.ref(session).put("appVersion",TerminalProtocol.VERSION)
            .put("sdkStatus",engine.ready()?"READY":"MODEL_LOADING").put("localFaces",store.faces()).put("pendingEvents",pendingEvents);
        long requestStarted=SystemClock.elapsedRealtime();
        JSONObject result=client.request("/api/terminal/heartbeat",request);
        lastSyncRequestMs=SystemClock.elapsedRealtime()-requestStarted;
        JSONObject profile=result.optJSONObject("profile"),messages=result.optJSONObject("feedback");
        platformSynced=profile!=null&&profile.optLong("scenicId")>0&&!profile.optString("scenicName").isEmpty()
            &&profile.optLong("deviceId")==session.optLong("deviceId")
            &&profile.optString("accountName").equals(session.optString("username"))&&messages!=null;
        if(!platformSynced){ready=false;overview="平台信息未完整同步，请更新配套平台后重试";throw new IllegalStateException(overview);}
        JSONObject nextProfile=new JSONObject(profile.toString()).put("localFaces",store.faces()).put("pendingPeople",result.optLong("pendingPeople")).put("pendingReceipts",result.optLong("pendingReceipts")).put("sdkReady",engine.ready()).put("pendingEvents",pendingEvents).put("platformSynced",true);
        if(result.has("roster"))nextProfile.put("roster",result.getJSONObject("roster"));
        if(result.has("syncOperations"))nextProfile.put("syncOperations",result.getJSONObject("syncOperations"));
        if(result.has("photoRejected"))nextProfile.put("photoRejected",result.getLong("photoRejected"));
        nextProfile.put("serverTime",result.optString("serverTime"));uiProfile=nextProfile;
        feedback=messages;store.metadata("feedback",feedback.toString(),true);
        lastHeartbeat=SystemClock.elapsedRealtime();lastHeartbeatWall=System.currentTimeMillis();
        rosterReady=result.optBoolean("rosterReady");
        pendingPeople=result.optLong("pendingSync")>0;
        ready=allowReady&&result.optBoolean("productionReady")&&"ACTIVE".equals(session.optString("state"))&&pendingEvents==0;
        String rule=profile.optString("ruleType");
        rule="NORMAL".equals(rule)?"常规核验":"SINGLE_POINT".equals(rule)?"单景点核验":rule;
        overview="景区："+profile.optString("scenicName")+"\n设备："+profile.optString("deviceName")
            +"\n账号："+profile.optString("accountName")+" · 规则："+rule
            +"\n平台配置：已同步 · 人员清单："+(result.optBoolean("rosterReady")?"已生成":"生成中")
            +"\n本地已保存特征："+store.faces()+" · 待同步："+result.optLong("pendingPeople")+" · 待回执："+result.optLong("pendingReceipts")
            +"\n待确认入园记录："+pendingEvents+" · 照片待处理："+result.optLong("photoRejected")
            +"\n人员库："+("SHARED".equals(session.optString("libraryMode"))?"同平台共享 · 换账号保留":"旧版会话库")
            +"\n领路人脸算法："+(engine.ready()?"已就绪":"加载中")+" · APP："+TerminalProtocol.VERSION
            +"\n平台状态更新时间："+result.optString("serverTime")
            +(lastPhotoIssue.isEmpty()?"":"\n最近照片问题："+lastPhotoIssue);
        String stage=TerminalSyncSchedule.stage(ready(),rosterReady,engine.ready(),result.optLong("pendingPeople"),result.optLong("pendingReceipts"),pendingEvents);
        if(!rosterReady&&result.optJSONObject("roster")!=null)stage=result.getJSONObject("roster").optString("message",stage);
        syncStep(ready()?"同步完成":!rosterReady?"等待平台生成人员清单":!engine.ready()?"加载本地特征":pendingPeople?"同步人员及回执":pendingEvents>0?"确认入园记录":"等待平台核验条件确认");
        if(result.optLong("photoRejected")>0)stage+="\n另有"+result.optLong("photoRejected")+"项照片处理失败，对应人员暂不能刷脸；请管理员查看照片失败原因并更换照片";
        emit(stage);
    }
    private void tick(){
        if(session==null||!authorized||settingsOpen||SystemClock.elapsedRealtime()<nextTick)return;
        boolean failed=false;
        try {
            // Events can still drain even if the local algorithm cannot initialize.
            syncStep("上传并确认本机入园记录");upload();
            // Retry release first: the server may have committed exit before its response was lost.
            if("DRAINING".equals(session.getString("state"))){
                // A previous release probe requested sync; finish bounded restore without hammering release.
                if(engine.loading()&&!prepare())return;
                finishDrain();return;
            }
            if("ACTIVE".equals(session.optString("state")))ensureSharedLibrary();
            if(!platformSynced||(!ready&&SystemClock.elapsedRealtime()-lastHeartbeat>=10000))heartbeat(false);
            syncStep("初始化算法与加载本地特征");if(!prepare())return;presence.sdk("AUTHORIZED",engine.ready()?"READY":"ERROR",null);
            if("PAUSING".equals(session.getString("state")))drain();
            if(pendingPeople){syncStep("下载人员并确认回执");syncPeople();}
            if("DRAINING".equals(session.getString("state"))){finishDrain();return;}
            syncStep("获取平台核验条件");if(TerminalSyncSchedule.heartbeatDue(lastHeartbeat,SystemClock.elapsedRealtime(),pendingPeople,rosterReady,ready))heartbeat(true);
            store.cleanup(System.currentTimeMillis());
        }catch(Exception|LinkageError e){failed=true;ready=false;emit("未完成步骤："+syncStep+"\n原因："+safe(e)+"\n本机记录保留，自动重试；重试倒计时见设备诊断，暂不可通行");}
        finally{
            syncFailures=failed?Math.min(8,syncFailures+1):0;
            boolean work=session!=null&&(!rosterReady||pendingPeople||!"ACTIVE".equals(session.optString("state"))||pendingEvents>0);
            boolean loading=session!=null&&engine.loading()&&!modelFailed&&!failed;
            nextTick=SystemClock.elapsedRealtime()+Math.max(client==null?0:client.retryDelayMillis(),loading?25:TerminalSyncSchedule.delay(work,syncFailures,jitter.nextInt(5000)));
            if((loading||(work&&!failed))&&!restoreContinuationQueued){restoreContinuationQueued=true;actor.schedule(()->{restoreContinuationQueued=false;tick();},Math.max(1,nextTick-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);}
        }
    }
    public void retry(){if(retryQueued.compareAndSet(false,true)){emit("正在检查平台变更与待确认记录；本地人员库保留");actor.execute(()->{try{nextTick=0;tick();}finally{retryQueued.set(false);}});}}
    public void exit(Done done){
        pauseCamera();ready=false;
        actor.execute(()->{try{
            if(session==null){complete(done,null);return;}
            // reset ack must precede drain when exiting an as-yet unsynchronized login.
            if("DRAINING".equals(session.getString("state"))){nextTick=0;tick();complete(done,session==null?null:"正在完成原账号同步，请稍候");return;}
            store.state(session.getString("sessionId"),"PAUSING");session=store.active();
            if(!prepare()){nextTick=0;tick();complete(done,"正在加载人员库，完成后继续同步退出；原账号记录已保留");return;}drain();
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
            public void sync() throws Exception {if(prepare())syncPeople();}
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
        store.state(current.getString("sessionId"),"RELEASED");client.logout();session=null;hasSession=false;ready=false;platformSynced=false;rosterReady=false;lastHeartbeat=0;lastHeartbeatWall=0;overview="平台信息：未登录";uiProfile=new JSONObject();scanning=false;emit("同步与回执已完成，可以登录其他账号");
    }
    public void frame(byte[] bytes,int width,int height,int rotation){
        if(!scanning||!authorized||!engine.ready()||!frameQueued.compareAndSet(false,true))return;
        final long epoch=viewEpoch;byte[] copy=bytes.clone();
        if(!algorithms.offer(()->{
            boolean handedOff=false;
            try{
                if(!scanning||settingsOpen||epoch!=viewEpoch||!engine.ready())return;
                TerminalFaceEngine.Match match=engine.identify(store,copy,width,height,rotation);
                long now=SystemClock.elapsedRealtime();
                if(!scanning||epoch!=viewEpoch)return;
                if(now-lastDiagnostics>=800){
                    lastDiagnostics=now;String info=engine.diagnostics()+(match.failure.startsWith("GUIDE_")?" · "+guidance(match.failure):"");
                    main.post(()->{Listener l=listener;if(l!=null&&scanning&&epoch==viewEpoch)l.diagnostics(info);});
                }
                if(!match.present){gate.absent(now);return;}
                if(match.failure.startsWith("GUIDE_"))return;
                if(!gate.decision(match.user,match.failure,now))return;
                // At most one decision waits for the actor. No SDK thread ever waits for network.
                actor.execute(()->acceptMatch(epoch,now,match));handedOff=true;
            }catch(Exception|LinkageError e){
                actor.execute(()->{if(epoch==viewEpoch){ready=false;failure(e);}});
            }finally{java.util.Arrays.fill(copy,(byte)0);if(!handedOff)frameQueued.set(false);}
        }))frameQueued.set(false);
    }
    private void acceptMatch(long epoch,long matchedAt,TerminalFaceEngine.Match match){
        try{
            if(!scanning||epoch!=viewEpoch||session==null||!"ACTIVE".equals(session.optString("state")))return;
            if(SystemClock.elapsedRealtime()-matchedAt>1500){algorithms.offer(gate::reset);return;}
            boolean online=ready()&&match.failure.isEmpty();
            String failure=!ready()?"NETWORK_UNAVAILABLE":match.failure.isEmpty()?"PLATFORM_TIMEOUT":match.failure;
            JSONObject event=store.event(match.user,failure),result=null;pendingEvents=store.pendingCount(session.getString("sessionId"));boolean saved=false;
            try{
                result=client.request(online?"/api/terminal/verify":"/api/terminal/recovery/events",event);
                store.receipt(event,result);todayCache=null;saved=true;latestOutcome=new JSONObject(result.toString()).put("occurredAt",event.optString("occurredAt")).put("confirmed",false);
                boolean open=online&&TerminalProtocol.canOpen(result);
                String display=result.optString("displayText",open?"核验成功，请通行":"暂不可通行");
                if(!open)display=TerminalDecisionText.describe(match.user>0,result.optString("reason"),display);
                showResult(epoch,result.optBoolean("duplicate")?"该记录已处理，请点击下一位或移开后重新核验":display,result.optBoolean("duplicate")?"":result.optString("speechText"),open,TerminalResultType.of(result.optString("result"),result.optString("reason"),result.optBoolean("duplicate")));
                acknowledge(event,result);
            }catch(Exception e){
                if(!saved){ready=false;localFeedback(epoch,"network","网络异常，暂不可通行",new JSONObject().put("eventId",event.getString("eventId")).put("occurredAt",event.optString("occurredAt")));}
                // A saved first response is never re-presented if only its ACK failed.
                emit("记录等待补传或确认："+safe(e));
            }
        }catch(Exception|LinkageError e){ready=false;localFeedback(epoch,"sdk","设备尚未就绪，请联系工作人员");failure(e);}
        finally{frameQueued.set(false);}
    }
    private static String guidance(String code){
        switch(code){
            case "GUIDE_SINGLE_FACE":return "请保持画面内只有一人";
            case "GUIDE_POSE":return "请正对摄像头，调整手机至面部高度";
            case "GUIDE_BLUR":return "请保持稳定，等待画面清晰";
            case "GUIDE_LIGHT":return "光线不足，请移至明亮位置";
            default:return "请露出完整五官，避免遮挡";
        }
    }
    private void localFeedback(long epoch,String key,String fallback){localFeedback(epoch,key,fallback,new JSONObject());}
    private void localFeedback(long epoch,String key,String fallback,JSONObject outcome){latestOutcome=outcome;JSONObject messages=feedback.optJSONObject("messages"),item=messages==null?null:messages.optJSONObject(key);showResult(epoch,item==null?fallback:item.optString("text",fallback),feedback.optBoolean("voiceEnabled",true)?(item==null?fallback:item.optString("speech")):"",false,key);}
    private void showResult(long epoch,String text,String speech,boolean open){showResult(epoch,text,speech,open,open?"pass":"denied");}
    private void showResult(long epoch,String text,String speech,boolean open,String type){main.post(()->{Listener l=listener;if(l!=null&&scanning&&epoch==viewEpoch)l.result(text,speech,open,type);});}
    private volatile JSONObject latestOutcome=new JSONObject();
    public JSONObject outcome(){try{return new JSONObject(latestOutcome.toString());}catch(Exception e){return new JSONObject();}}
    public boolean voiceAllowed(){return feedback.optBoolean("voiceEnabled",true);}
    private JSONObject todayCache;private String todayCacheSession="",todayCacheDay="";private long todayCachedAt;
    public void recordBackup(android.net.Uri uri,char[] password,boolean restore,String origin,String fingerprint,Done done){
        pauseCamera();actor.execute(()->{try{
            if(restore){try(java.io.InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IllegalStateException("无法读取备份文件");byte[] plain=TerminalBackupCrypto.decrypt(in,password);try{int count=store.restoreRecords(new JSONObject(new String(plain,"UTF-8")),origin,fingerprint);emit("已合并恢复"+count+"条记录，正在核对平台回执");}finally{java.util.Arrays.fill(plain,(byte)0);}}ready=false;nextTick=0;todayCache=null;}
            else {byte[] plain=store.backupRecords(origin,fingerprint).toString().getBytes("UTF-8");try(java.io.OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IllegalStateException("无法写入备份文件");TerminalBackupCrypto.encrypt(plain,password,out);}finally{java.util.Arrays.fill(plain,(byte)0);}}
            complete(done,null);
        }catch(Exception e){complete(done,safe(e));}finally{java.util.Arrays.fill(password,'\0');}});
    }
    public void today(Stats done){actor.execute(()->{try{if(session==null)throw new IllegalStateException("请先登录");java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US);fmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));String day=fmt.format(new java.util.Date()),id=session.getString("sessionId");if(todayCache==null||!id.equals(todayCacheSession)||!day.equals(todayCacheDay)||SystemClock.elapsedRealtime()-todayCachedAt>15000){todayCache=client.request("/api/terminal/today",null);todayCacheSession=id;todayCacheDay=day;todayCachedAt=SystemClock.elapsedRealtime();}JSONObject result=new JSONObject(todayCache.toString());main.post(()->done.done(result,null));}catch(Exception e){main.post(()->done.done(null,safe(e)));}});}
    public void localRecords(Stats done){actor.execute(()->{try{
        if(session==null)throw new IllegalStateException("请先登录");
        JSONObject result=store.dashboard(session.getString("sessionId"));
        result.put("libraryMode",session.optString("libraryMode")).put("sdkReady",engine.ready()).put("appVersion",TerminalProtocol.VERSION);
        main.post(()->done.done(result,null));
    }catch(Exception e){main.post(()->done.done(null,safe(e)));}});}
    public void statistics(String from,String to,Stats done){actor.execute(()->{try{if(session==null)throw new IllegalStateException("请先登录");JSONObject result=client.statistics(from,to);main.post(()->done.done(result,null));}catch(Exception e){main.post(()->done.done(null,safe(e)));}});}
    private void failure(Throwable e){emit(safe(e));}
    private static String safe(Throwable e){String m=e.getMessage();return m==null||m.length()>180?"设备操作失败，请检查连接和算法状态":TerminalMessages.friendly(m);}
    private void complete(Done done,String error){main.post(()->done.done(error));}
    private void emit(String text){lastStatus=text;boolean active=hasSession,can=ready();main.post(()->{Listener l=listener;if(l!=null)l.status(text,can,active);});}
}
