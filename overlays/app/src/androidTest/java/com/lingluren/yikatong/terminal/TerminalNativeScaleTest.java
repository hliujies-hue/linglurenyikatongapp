package com.lingluren.yikatong.terminal;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import com.baidu.idl.main.facesdk.FaceSearch;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon;
import com.baidu.idl.main.facesdk.model.Feature;
import com.baidu.idl.main.facesdk.utils.PreferencesUtil;
import com.example.authlibrary.BdFaceAuth;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Real native index, isolated instrumentation process; no TerminalRuntime or business DB. */
public final class TerminalNativeScaleTest extends InstrumentationTestCase {
    private static final int BYTES=512;
    private int count;
    private File directory;
    private final JSONObject report=new JSONObject();
    private long peakPss;
    private final long started=SystemClock.elapsedRealtime();
    private void checkpoint(String phase,int completed) throws Exception {
        Debug.MemoryInfo memory=new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
        peakPss=Math.max(peakPss,memory.getTotalPss());
        ActivityManager.MemoryInfo system=new ActivityManager.MemoryInfo();
        ((ActivityManager)getInstrumentation().getTargetContext().getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(system);
        report.put("phase",phase).put("completed",completed).put("sampledPeakPssKiB",peakPss)
            .put("nativeAllocatedBytes",Debug.getNativeHeapAllocatedSize()).put("availableSystemBytes",system.availMem);
        persist();
        if(SystemClock.elapsedRealtime()-started>30L*60*1000)throw new IOException("TIME_LIMIT_STOP");
        if(new File(directory,"STOP").exists())throw new IOException("STOP_REQUESTED");
        if(system.lowMemory || system.availMem<384L*1024*1024)throw new IOException("LOW_MEMORY_STOP");
    }
    private void persist() throws Exception {
        File pending=new File(directory,"report.tmp"),dest=new File(directory,"report.json");
        try(FileOutputStream out=new FileOutputStream(pending)) {out.write(report.toString(2).getBytes("UTF-8"));out.getFD().sync();}
        if(!pending.renameTo(dest))throw new IOException("REPORT_WRITE_FAILED");
    }
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();
    }
    public void testNativeIndex() throws Exception {
        assertNotNull("Use TerminalScaleRunner",TerminalScaleRunner.arguments);
        count=Integer.parseInt(TerminalScaleRunner.arguments.getString("nativeScale","0"));
        assertTrue("Unsupported tier",count==560||count==1000||count==10000||count==50000||count==100000||count==200000);
        Context context=getInstrumentation().getTargetContext();
        directory=new File(context.getExternalFilesDir(null),"ykt-test-v2/native-scale");
        assertTrue(directory.isDirectory());
        File data=new File(directory,"features.bin");
        report.put("status","RUNNING").put("nativeSDKTested",false).put("endToEndTested",false)
            .put("testTier",count==560?"baseline-560":"scale").put("scope","native-index-self-query").put("records",count).put("featureBytes",BYTES)
            .put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE);
        FaceSearch search=null;
        try {
            File manifestFile=new File(directory,"manifest.json");
            assertTrue("Dataset manifest missing or oversized",manifestFile.isFile()&&manifestFile.length()<16384);
            byte[] manifestBytes=new byte[(int)manifestFile.length()];
            try(DataInputStream input=new DataInputStream(new FileInputStream(manifestFile))){input.readFully(manifestBytes);}
            JSONObject manifest=new JSONObject(new String(manifestBytes,"UTF-8"));
            assertEquals("SDK version mismatch","8.5",manifest.getString("sdkVersion"));
            assertEquals("Feature type mismatch","LIVE_PHOTO",manifest.getString("featureType"));
            int datasetCount=manifest.getInt("count");
            assertTrue("Insufficient or oversized dataset",datasetCount>=count&&datasetCount<=200000);
            boolean synthetic=manifest.getBoolean("synthetic");
            String mode=TerminalScaleRunner.arguments.getString("datasetMode","");
            assertEquals("Dataset mode must be explicit",synthetic?"synthetic":"real",mode);
            if(!synthetic)assertTrue("Real identities require provider attestation",manifest.getBoolean("genuineDistinctIdentities"));
            report.put("datasetMode",mode).put("datasetCount",datasetCount)
                .put("identityDistinctness",synthetic?"synthetic-not-real-identities":"provider-attested-not-independently-verified");
            assertEquals("Dataset byte length mismatch",(long)datasetCount*BYTES,data.length());
            String expected=TerminalScaleRunner.arguments.getString("datasetSha256","");
            assertTrue("Dataset SHA256 required",expected.matches("[a-f0-9]{64}"));
            MessageDigest whole=MessageDigest.getInstance("SHA-256"),single=MessageDigest.getInstance("SHA-256");
            Set<String> seen=new HashSet<>();byte[] bytes=new byte[BYTES];
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(data)))) {
                for(int i=0;i<datasetCount;i++) {
                    input.readFully(bytes);whole.update(bytes);
                    assertTrue("Duplicate template at row "+(i+1),seen.add(hex(single.digest(bytes))));
                    boolean nonzero=false;for(byte b:bytes)if(b!=0){nonzero=true;break;}
                    assertTrue("Zero template at row "+(i+1),nonzero);
                    if((i+1)%5000==0)checkpoint("VALIDATING",i+1);
                }
            }
            String actual=hex(whole.digest());assertEquals("Dataset hash mismatch",expected,actual);
            assertEquals("Manifest hash mismatch",actual,manifest.getString("sha256"));
            seen.clear();report.put("datasetSha256",actual);
            // Do not use TerminalLicenseManager: it starts the production runtime/synchronizer.
            PreferencesUtil.initPrefs(context);
            String code=context.getSharedPreferences("terminal_license",Context.MODE_PRIVATE).getString("successful_code","");
            assertFalse("Activate the production app first",code.isEmpty());
            CountDownLatch licensed=new CountDownLatch(1);int[] result={-1};
            BdFaceAuth auth=new BdFaceAuth();
            assertNotNull("SDK fingerprint unavailable",auth.getDeviceId(context));
            auth.initLicenseOnLine(context,code,(error,message)->{result[0]=error;licensed.countDown();});
            assertTrue("License initialization timed out",licensed.await(90,TimeUnit.SECONDS));
            assertEquals("License initialization failed",0,result[0]);
            search=new FaceSearch();search.setNeedJoinDB(false);search.setMaxUpdateSize(0);
            assertEquals("Native clear failed",0,search.featureClear());
            report.put("nativeSDKTested",true);
            long start=SystemClock.elapsedRealtime();
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(data)))) {
                for(int i=1;i<=count;i++) {
                    input.readFully(bytes);assertEquals("Native push failed at "+i,0,search.pushPersonById(i,bytes));
                    if(i%1000==0)checkpoint("LOADING",i);
                }
            }
            report.put("loadWallMs",SystemClock.elapsedRealtime()-start);
            long[] durations=new long[1000];int correct=0;
            try(RandomAccessFile input=new RandomAccessFile(data,"r")) {
                for(int i=0;i<durations.length;i++) {
                    int id=1+(int)((long)i*(count-1)/(durations.length-1));
                    input.seek((long)(id-1)*BYTES);input.readFully(bytes);
                    long at=System.nanoTime();
                    List<? extends Feature> found=search.search(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,80f,1,bytes,true);
                    durations[i]=System.nanoTime()-at;
                    if(found!=null&&!found.isEmpty()&&found.get(0).getId()==id&&found.get(0).getScore()>=80f)correct++;
                    if((i+1)%50==0)checkpoint("SEARCHING",i+1);
                }
            }
            Arrays.sort(durations);
            report.put("queries",durations.length).put("selfMatches",correct).put("threshold",80)
                .put("queryP50Ms",durations[500]/1e6).put("queryP95Ms",durations[950]/1e6).put("queryP99Ms",durations[990]/1e6);
            assertEquals("Self-query mismatch; not a real-face accuracy measurement",1000,correct);
            checkpoint("COMPLETE",count);report.put("status","PASS");persist();
        } catch(Throwable error) {
            report.put("status","FAIL").put("errorType",error.getClass().getSimpleName());persist();
            throw new AssertionError("Native scale failed; see phase/report and instrumentation output",error);
        } finally {
            if(search!=null) {int code=search.featureClear();report.put("nativeCleanupCode",code);if(code!=0)report.put("status","FAIL");persist();assertEquals("Native cleanup failed",0,code);}
        }
    }
}
