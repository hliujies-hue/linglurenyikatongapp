package com.lingluren.yikatong.terminal;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import com.baidu.idl.main.facesdk.utils.PreferencesUtil;
import com.example.authlibrary.BdFaceAuth;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Offline photo extraction into test-only files. Never opens terminal-v2.db. */
public final class TerminalFeatureExtractTest extends InstrumentationTestCase {
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static String sha(File file)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];
        try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());
    }
    private static void save(File file,JSONObject json)throws Exception{
        File tmp=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(json.toString(2).getBytes("UTF-8"));out.getFD().sync();}
        if(!tmp.renameTo(file))throw new IOException("CHECKPOINT_WRITE_FAILED");
    }
    private static JSONObject read(File file)throws Exception{
        if(file.length()>16384)throw new IOException("CHECKPOINT_TOO_LARGE");
        byte[] data=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(data);}
        return new JSONObject(new String(data,"UTF-8"));
    }
    public void testExtractPhotos()throws Exception{
        assertNotNull(TerminalScaleRunner.arguments);
        assertEquals("Explicit extraction opt-in required","yes",TerminalScaleRunner.arguments.getString("extractPhotos"));
        String mode=TerminalScaleRunner.arguments.getString("datasetMode","");
        assertTrue("Explicit dataset mode required",mode.equals("real")||mode.equals("synthetic"));
        int limit=Integer.parseInt(TerminalScaleRunner.arguments.getString("maxPhotos","1000"));
        assertTrue(limit>=1&&limit<=200000);
        Context context=getInstrumentation().getTargetContext();
        File root=new File(context.getExternalFilesDir(null),"ykt-test/feature-extract");
        File images=new File(root,"images");
        File[] files=images.listFiles(f->f.isFile()&&f.getName().toLowerCase(Locale.US).matches(".*\\.(jpg|jpeg|png|webp)"));
        assertNotNull("Cannot read images: "+images.getAbsolutePath()+"; exists="+images.exists()+"; readable="+images.canRead(),files);
        assertTrue("Photo count must be 1..200000",files.length>0&&files.length<=200000);
        Arrays.sort(files,Comparator.comparing(File::getName));
        int processCount=Math.min(limit,files.length);
        MessageDigest corpus=MessageDigest.getInstance("SHA-256");
        for(File f:files){assertTrue("Photo exceeds 16MiB",f.length()>0&&f.length()<=16L*1024*1024);corpus.update((f.getName()+"\n"+sha(f)+"\n").getBytes("UTF-8"));}
        String corpusHash=hex(corpus.digest());
        File checkpoint=new File(root,"checkpoint.json"),output=new File(root,"features.bin"),manifest=new File(root,"manifest.json");
        JSONObject state=checkpoint.isFile()?read(checkpoint):new JSONObject().put("corpusSha256",corpusHash).put("datasetMode",mode).put("next",0).put("accepted",0).put("duplicates",0).put("rejected",0);
        assertEquals("Image corpus changed; use another extraction directory after exporting previous results",corpusHash,state.getString("corpusSha256"));
        assertEquals("Mode changed",mode,state.getString("datasetMode"));
        int next=state.getInt("next"),accepted=state.getInt("accepted");
        assertTrue(next>=0&&next<=processCount&&accepted>=0&&accepted<=next);
        if(next>0)assertTrue("Partial features missing",output.isFile()&&output.length()>=(long)accepted*512);
        if(manifest.exists()&&!manifest.delete())throw new IOException("STALE_MANIFEST_REMOVE_FAILED");
        state.put("status","RUNNING");save(checkpoint,state);
        String dbName="native-feature-extract-test.db";
        context.deleteDatabase(dbName);
        TerminalStore store=null;TerminalFaceEngine engine=null;
        try(RandomAccessFile out=new RandomAccessFile(output,"rw")){
            out.setLength((long)accepted*512);
            Set<String> seen=new HashSet<>();byte[] template=new byte[512];
            for(int i=0;i<accepted;i++){out.readFully(template);seen.add(hex(MessageDigest.getInstance("SHA-256").digest(template)));}
            PreferencesUtil.initPrefs(context);
            String code=context.getSharedPreferences("terminal_license",Context.MODE_PRIVATE).getString("successful_code","");
            assertFalse("Activate original APP first",code.isEmpty());
            BdFaceAuth auth=new BdFaceAuth();assertNotNull(auth.getDeviceId(context));
            CountDownLatch done=new CountDownLatch(1);int[] result={-1};
            auth.initLicenseOnLine(context,code,(error,message)->{result[0]=error;done.countDown();});
            assertTrue(done.await(90,TimeUnit.SECONDS));assertEquals(0,result[0]);
            store=new TerminalStore(context,dbName);engine=new TerminalFaceEngine();
            engine.init(context,store,(loaded,total,elapsed)->{});
            while(!engine.ready())engine.restoreBatch(store,(loaded,total,elapsed)->{});
            long started=SystemClock.elapsedRealtime();
            try(FileWriter rejects=new FileWriter(new File(root,"rejections.jsonl"),true)){
                for(int i=next;i<processCount;i++){
                    android.app.ActivityManager.MemoryInfo mem=new android.app.ActivityManager.MemoryInfo();
                    ((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mem);
                    if(new File(root,"STOP").exists()||mem.lowMemory||mem.availMem<384L*1024*1024||SystemClock.elapsedRealtime()-started>30L*60*1000){state.put("status","PAUSED");save(checkpoint,state);fail("Extraction paused; resume with unchanged corpus");}
                    byte[] photo=new byte[(int)files[i].length()];try(DataInputStream input=new DataInputStream(new FileInputStream(files[i]))){input.readFully(photo);}
                    String hash=hex(MessageDigest.getInstance("SHA-256").digest(photo));
                    try{
                        engine.upsert(store,1,hash,photo);template=store.faceFeature(1,hash);
                        assertEquals(512,template.length);
                        if(seen.add(hex(MessageDigest.getInstance("SHA-256").digest(template)))){out.write(template);accepted++;}
                        else {state.put("duplicates",state.getInt("duplicates")+1);rejects.write(new JSONObject().put("row",i+1).put("reason","DUPLICATE_FEATURE").toString()+"\n");}
                        engine.delete(store,1);
                    }catch(TerminalFaceEngine.PhotoRejectedException rejected){
                        state.put("rejected",state.getInt("rejected")+1);rejects.write(new JSONObject().put("row",i+1).put("reason",rejected.getMessage()).toString()+"\n");
                    }
                    out.getFD().sync();rejects.flush();
                    state.put("next",i+1).put("accepted",accepted).put("status","RUNNING");save(checkpoint,state);
                }
            }
            out.getFD().sync();
            state.put("status","COMPLETE").put("processedLimit",processCount);save(checkpoint,state);
            assertTrue("No valid templates extracted",accepted>0);
            save(manifest,new JSONObject().put("sdkVersion","8.5").put("featureType","LIVE_PHOTO").put("count",accepted)
                .put("synthetic",mode.equals("synthetic")).put("genuineDistinctIdentities",mode.equals("real"))
                .put("sha256",sha(output)).put("corpusSha256",corpusHash).put("extraction","TerminalFaceEngine.upsert"));
        }catch(Throwable error){
            if(!"PAUSED".equals(state.optString("status")))state.put("status","FAIL");
            state.put("errorType",error.getClass().getSimpleName());save(checkpoint,state);
            throw new AssertionError("Extraction incomplete; see checkpoint and instrumentation output",error);
        }finally{
            if(store!=null)store.close();context.deleteDatabase(dbName);
            // Process termination by the host script releases all native models/indexes.
        }
    }
}
