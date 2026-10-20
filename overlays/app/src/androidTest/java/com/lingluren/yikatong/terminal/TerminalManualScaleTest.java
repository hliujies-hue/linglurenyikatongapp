package com.lingluren.yikatong.terminal;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import com.baidu.idl.main.facesdk.*;
import com.baidu.idl.main.facesdk.callback.Callback;
import com.baidu.idl.main.facesdk.model.*;
import com.baidu.idl.main.facesdk.utils.PreferencesUtil;
import com.example.authlibrary.BdFaceAuth;
import com.example.datalibrary.model.GlobalSet;
import com.example.datalibrary.threshold.BaseConfig;
import com.example.datalibrary.threshold.SingleBaseConfig;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Camera + isolated in-memory index. Never opens TerminalStore or TerminalRuntime. */
public final class TerminalManualScaleTest extends InstrumentationTestCase {
    private FaceDetect detect;
    private FaceLive live;
    private FaceFeature feature;
    private FaceSearch search;
    private TerminalManualTestActivity activity;
    private File directory;
    private final JSONObject report=new JSONObject();
    private final ArrayBlockingQueue<Frame> frames=new ArrayBlockingQueue<>(1);
    private final AtomicBoolean enrolling=new AtomicBoolean(),clearing=new AtomicBoolean();
    private volatile boolean ready,closed;
    private volatile String closeReason="";
    private boolean tester;
    private int count,queries,matches,syntheticMatches,noMatches,liveRejected,qualityRejected,enrollments;
    private float threshold,liveThreshold;
    private final List<Double> queryTimes=new ArrayList<>(),frameTimes=new ArrayList<>();
    private long started;
    private static final class Frame {
        final byte[] data;final int width,height,rotation;final long at;
        Frame(byte[] d,int w,int h,int r,long t){data=d.clone();width=w;height=h;rotation=r;at=t;}
    }
    private final TerminalManualTestActivity.Session session=new TerminalManualTestActivity.Session(){
        public void frame(byte[] d,int w,int h,int r,long t){if(ready&&!closed&&frames.isEmpty())frames.offer(new Frame(d,w,h,r,t));}
        public void enroll(){if(ready&&!closed)enrolling.set(true);}
        public void clearTester(){if(ready&&!closed){enrolling.set(false);clearing.set(true);}}
        public void close(String reason){if(!closed){closeReason=reason;closed=true;}ready=false;}
    };
    private interface Init {void run(Callback callback);}
    private void await(Init init,String stage) throws Exception {
        phase(stage);show(stage);CountDownLatch latch=new CountDownLatch(1);int[] code={-1};
        init.run((c,m)->{code[0]=c;latch.countDown();});
        assertTrue(stage+" timeout",latch.await(45,TimeUnit.SECONDS));assertEquals(stage,0,code[0]);guard();
    }
    private void phase(String value) throws Exception {report.put("phase",value);persist();android.util.Log.i("YktManualScale",value);}
    private void show(String text){if(activity!=null)activity.showStatus("合成背景库："+count+" 条；测试者："+(tester?1:0)+"\n"+text+"\n仅测试：不上传平台，不生成核验记录");}
    private void guard() throws Exception {
        if(closed)throw new IOException("SESSION_CLOSED");
        if(SystemClock.elapsedRealtime()-started>30L*60*1000)throw new IOException("TIME_LIMIT");
        if(new File(directory,"STOP").exists())throw new IOException("STOP_REQUESTED");
        ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)getInstrumentation().getTargetContext().getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);
        if(m.lowMemory||m.availMem<384L*1024*1024)throw new IOException("LOW_MEMORY");
    }
    private static String hex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private JSONObject readJson(File file) throws Exception {assertTrue(file.isFile()&&file.length()<16384);byte[] b=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(b);}return new JSONObject(new String(b,"UTF-8"));}
    public void testPrepareManualStorage() throws Exception {
        File dir=new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"ykt-test-v2/manual-scale");
        assertTrue(dir.isDirectory()||dir.mkdirs());
        File probe=new File(dir,"probe.tmp");try(FileOutputStream out=new FileOutputStream(probe)){out.write(1);}assertTrue(probe.delete());
    }
    public void testManualCamera() throws Exception {
        Context context=getInstrumentation().getTargetContext();started=SystemClock.elapsedRealtime();
        directory=new File(context.getExternalFilesDir(null),"ykt-test-v2/manual-scale");assertTrue(directory.isDirectory());
        report.put("status","STARTING").put("scope","manual-camera-native-index").put("endToEndTested",false).put("accuracyMeasured",false).put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE);
        phase("VALIDATING_MANIFEST");
        try {
            assertEquals("Grant camera permission in the normal app first",PackageManager.PERMISSION_GRANTED,context.checkCallingOrSelfPermission(Manifest.permission.CAMERA));
            JSONObject manifest=readJson(new File(directory,"manifest.json"));count=manifest.getInt("count");
            assertTrue(count>=1&&count<=200000);assertEquals("8.5",manifest.getString("sdkVersion"));assertEquals("LIVE_PHOTO",manifest.getString("featureType"));
            assertTrue(manifest.getBoolean("synthetic"));assertFalse(manifest.getBoolean("genuineDistinctIdentities"));
            String expected=TerminalScaleRunner.arguments.getString("datasetSha256","");assertTrue(expected.matches("[a-f0-9]{64}"));assertEquals(expected,manifest.getString("sha256"));
            report.put("records",count).put("datasetSha256",expected).put("datasetMode","synthetic");
            TerminalManualTestActivity.pendingSession=session;
            Intent intent=new Intent(context,TerminalManualTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // startActivitySync can wait indefinitely when an OEM denies background UI launch.
            // Use an observational monitor and a bounded wait; do not weaken exported/permission rules.
            phase("OPENING_TEST_UI");
            android.app.Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(TerminalManualTestActivity.class.getName(),null,false);
            try {
                context.startActivity(intent);
                android.app.Activity opened=monitor.waitForActivityWithTimeout(30000);
                if(opened==null)throw new IOException("TEST_UI_NOT_OPENED_WITHIN_30S: Check this app's background pop-up permission; do not open the normal launcher during instrumentation");
                activity=(TerminalManualTestActivity)opened;
            } finally {getInstrumentation().removeMonitor(monitor);}
            phase("VALIDATING_FEATURES");
            File data=new File(directory,"features.bin");assertEquals((long)count*512,data.length());
            show("校验特征文件……");MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new BufferedInputStream(new FileInputStream(data))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){digest.update(b,0,n);guard();}}
            assertEquals(expected,hex(digest.digest()));
            PreferencesUtil.initPrefs(context);
            String code=context.getSharedPreferences("terminal_license",Context.MODE_PRIVATE).getString("successful_code","");assertFalse("Activate app first",code.isEmpty());
            phase("LICENSE_INIT");show("初始化已保存的算法授权……");BdFaceAuth auth=new BdFaceAuth();assertNotNull(auth.getDeviceId(context));CountDownLatch licensed=new CountDownLatch(1);int[] result={-1};
            auth.initLicenseOnLine(context,code,(c,m)->{result[0]=c;licensed.countDown();});assertTrue(licensed.await(90,TimeUnit.SECONDS));assertEquals(0,result[0]);guard();
            detect=new FaceDetect();live=new FaceLive();feature=new FaceFeature();
            BaseConfig config=SingleBaseConfig.getBaseConfig();threshold=config.getLiveThreshold();liveThreshold=config.getRgbLiveScore();
            assertTrue(finite(threshold)&&threshold>0&&threshold<=100&&finite(liveThreshold)&&liveThreshold>0&&liveThreshold<=1);
            BDFaceSDKConfig options=new BDFaceSDKConfig();options.maxDetectNum=2;options.minFaceSize=config.getMinimumFace();options.notRGBFaceThreshold=config.getFaceThreshold();options.isCheckBlur=true;options.isOcclusion=true;options.isIllumination=true;options.isHeadPose=true;detect.loadConfig(options);
            await(cb->detect.initModel(context,GlobalSet.DETECT_VIS_MODEL,GlobalSet.ALIGN_RGB_MODEL,BDFaceSDKCommon.DetectType.DETECT_VIS,BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,cb),"加载检测模型");
            await(cb->detect.initQuality(context,GlobalSet.BLUR_MODEL,GlobalSet.OCCLUSION_MODEL,cb),"加载质量模型");
            await(cb->live.initModel(context,GlobalSet.LIVE_VIS_MODEL,"","","",GlobalSet.LIVE_NIR_MODEL,GlobalSet.LIVE_DEPTH_MODEL,cb),"加载活体模型");
            await(cb->feature.initModel(context,GlobalSet.RECOGNIZE_IDPHOTO_MODEL,GlobalSet.RECOGNIZE_VIS_MODEL,GlobalSet.RECOGNIZE_NIR_MODEL,"",cb),"加载特征模型");
            search=new FaceSearch();search.setNeedJoinDB(false);search.setMaxUpdateSize(0);assertEquals(0,search.featureClear());
            phase("LOADING_INDEX");long load=SystemClock.elapsedRealtime();byte[] bytes=new byte[512];
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(data)))){for(int i=1;i<=count;i++){in.readFully(bytes);assertEquals("Push row "+i,0,search.pushPersonById(i,bytes));if(i%1000==0){show("加载索引："+i+" / "+count);guard();}}}
            report.put("loadWallMs",SystemClock.elapsedRealtime()-load).put("threshold",threshold).put("rgbLiveThreshold",liveThreshold).put("status","RUNNING");persist();
            phase("CAMERA_READY");ready=true;show("已就绪。先测试未录入情况，再点击录入本人。");
            while(!closed){
                guard();
                if(clearing.getAndSet(false)){if(tester){assertEquals(0,search.delPersonById(count+1));tester=false;}show("测试者已移除。可以观察未录入结果。");}
                Frame frame=frames.poll(300,TimeUnit.MILLISECONDS);if(frame!=null)process(frame);
                persist();
            }
            report.put("status","SESSION_ENDED").put("endReason",closeReason);
            assertFalse("Camera failed",closeReason.startsWith("CAMERA_"));
        } catch(Throwable error){report.put("status","INTERRUPTED").put("errorType",error.getClass().getSimpleName()).put("endReason",closed?closeReason:String.valueOf(error.getMessage()));throw new AssertionError("Manual session incomplete; retain report",error);}
        finally {
            ready=false;frames.clear();TerminalManualTestActivity.pendingSession=null;
            if(activity!=null)getInstrumentation().runOnMainSync(()->activity.finish());
            if(search!=null){int cleanup=search.featureClear();report.put("nativeCleanupCode",cleanup);if(cleanup!=0)report.put("status","CLEANUP_FAILED");}
            persist();
        }
    }
    private static boolean finite(float n){return !Float.isNaN(n)&&!Float.isInfinite(n);}
    private boolean quality(FaceInfo f){BaseConfig c=SingleBaseConfig.getBaseConfig();BDFaceOcclusion o=f.occlusion;return Math.abs(f.yaw)<=c.getGesture()&&Math.abs(f.roll)<=c.getGesture()&&Math.abs(f.pitch)<=c.getGesture()&&f.bluriness<=c.getBlur()&&f.illum>=c.getIllumination()&&o!=null&&o.leftEye<=c.getLeftEye()&&o.rightEye<=c.getRightEye()&&o.nose<=c.getNose()&&o.mouth<=c.getMouth()&&o.leftCheek<=c.getLeftCheek()&&o.rightCheek<=c.getRightCheek()&&o.chin<=c.getChinContour();}
    private void process(Frame f) throws Exception {
        BDFaceImageInstance image=new BDFaceImageInstance(f.data,f.height,f.width,BDFaceSDKCommon.BDFaceImageType.BDFACE_IMAGE_TYPE_YUV_NV21,f.rotation,0);
        try {
            BDFaceDetectListConf flags=new BDFaceDetectListConf();flags.usingDetect=true;flags.usingAlign=true;flags.usingQuality=true;flags.usingHeadPose=true;
            FaceInfo[] faces=detect.detect(BDFaceSDKCommon.DetectType.DETECT_VIS,BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,image,null,flags);
            String prefix=enrolling.get()?"等待录入：":"";
            if(faces==null||faces.length==0){show(prefix+"请正对摄像头");return;}
            if(faces.length!=1||!quality(faces[0])){qualityRejected++;show(prefix+"质量未通过：请保持单人、正面、明亮无遮挡");return;}
            float liveScore=live.silentLive(BDFaceSDKCommon.LiveType.BDFACE_SILENT_LIVE_TYPE_RGB,image,faces[0].landmarks,liveThreshold);
            if(!finite(liveScore)||liveScore<liveThreshold){liveRejected++;show(prefix+"活体未通过，分数："+liveScore);return;}
            byte[] template=new byte[512];float size=feature.feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,image,faces[0].landmarks,template);assertTrue("Feature failed",TerminalSdkContract.featureSucceeded(size));
            if(enrolling.getAndSet(false)){
                if(tester){assertEquals(0,search.delPersonById(count+1));tester=false;}
                assertEquals(0,search.pushPersonById(count+1,template));tester=true;enrollments++;
                show("本人已录入。请移开再返回，使用新的画面验证。");return;
            }
            long at=System.nanoTime();List<? extends Feature> found=search.search(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,threshold,1,template,true);
            double queryMs=(System.nanoTime()-at)/1e6,totalMs=(SystemClock.elapsedRealtimeNanos()-f.at)/1e6;queryTimes.add(queryMs);frameTimes.add(totalMs);queries++;
            String result="未匹配";float score=0;
            if(found!=null&&!found.isEmpty()&&finite(found.get(0).getScore())&&found.get(0).getScore()>=threshold){score=found.get(0).getScore();if(tester&&found.get(0).getId()==count+1){matches++;result="匹配本次测试者";}else{syntheticMatches++;result="匹配到合成样本（需人工核对，不代表身份正确）";}}else noMatches++;
            show(String.format(Locale.CHINA,"%s\n匹配 %.2f / %.2f；活体 %.3f / %.3f\n纯检索 %.2f ms；帧回调至结果 %.2f ms\n累计查询 %d 次",result,score,threshold,liveScore,liveThreshold,queryMs,totalMs,queries));
        } finally {image.destory();}
    }
    private void persist() throws Exception {
        report.put("queries",queries).put("testerMatches",matches).put("syntheticMatches",syntheticMatches).put("noMatches",noMatches).put("qualityRejectedFrames",qualityRejected).put("livenessRejectedFrames",liveRejected).put("enrollments",enrollments).put("testerStoredOnDisk",false).put("elapsedMs",SystemClock.elapsedRealtime()-started);
        // Live periodic counts; percentile calculation only on completion to avoid distorting each frame.
        if(!"RUNNING".equals(report.optString("status"))&&!queryTimes.isEmpty()){
            Collections.sort(queryTimes);Collections.sort(frameTimes);
            for(int percentile:new int[]{50,95,99}){int index=Math.min(queryTimes.size()-1,(int)Math.ceil(queryTimes.size()*percentile/100.0)-1);report.put("queryP"+percentile+"Ms",queryTimes.get(index));report.put("frameToResultP"+percentile+"Ms",frameTimes.get(index));}
        }
        File temp=new File(directory,"report.tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(report.toString(2).getBytes("UTF-8"));}if(!temp.renameTo(new File(directory,"report.json")))throw new IOException("REPORT_WRITE_FAILED");
    }
}
