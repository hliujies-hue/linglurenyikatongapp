package com.lingluren.yikatong.terminal;
import android.content.Context;
import android.graphics.Bitmap;
import com.baidu.idl.main.facesdk.*;
import com.baidu.idl.main.facesdk.callback.Callback;
import com.baidu.idl.main.facesdk.model.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.baidu.idl.main.facesdk.model.BDFaceSDKCommon.*;
/** Adapted to the exact API signatures in the supplied 20251124 AAR.
 * Every search, extraction and index mutation uses the same monitor.
 * Models are initialized once per process, outside the camera thread.
 */
public final class FaceEngine {
 private final FaceAuth auth=new FaceAuth();
 private FaceDetect detector;
 private FaceFeature feature;
 private FaceLive live;
 private FaceSearch search;
 private volatile boolean authorized,ready;
 private static final String M="face-sdk-models/";
 public record Match(String status,int localId,float score) {}
 public void activate(Context context,String code,Callback callback) {
  auth.initLicenseOnLine(context,code,(rc,msg)->{authorized=rc==0;callback.onResponse(rc,msg);});
 }
 public boolean authorized(){return authorized;}
 public String fingerprint(Context c){return auth.getDeviceId(c);}
 private interface Loader { void load(Callback cb); }
 private void await(Loader l) throws Exception {
  CountDownLatch latch=new CountDownLatch(1); AtomicInteger result=new AtomicInteger(-1);
  l.load((rc,msg)->{result.set(rc);latch.countDown();});
  if(!latch.await(60,TimeUnit.SECONDS)) throw new IllegalStateException("模型加载超时，请重启应用");
  if(result.get()!=0) throw new IllegalStateException("模型加载失败，错误码 "+result.get());
 }
 public synchronized void initialize(Context c) throws Exception {
  if(ready)return;
  if(!authorized) throw new IllegalStateException("设备未激活");
  detector=new FaceDetect(); feature=new FaceFeature(); live=new FaceLive(); search=new FaceSearch();
  BDFaceSDKConfig config=new BDFaceSDKConfig();config.maxDetectNum=2;config.minFaceSize=80;
  config.isCheckBlur=true;config.isOcclusion=true;config.isIllumination=true;config.isHeadPose=true;
  detector.loadConfig(config);
  await(cb->detector.initModel(c,M+"detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1",
   M+"align/align_rgb-customized-pa-80.model.float32-6.4.14.4",DetectType.DETECT_VIS,AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,cb));
  await(cb->detector.initQuality(c,M+"blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3",
   M+"occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3",cb));
  String model=M+"feature/feature_live-mnasnet-pa-attention_v4.model.int8-2.0.239.1";
  await(cb->feature.initModel(c,model,model,"","",cb));
  await(cb->live.initModel(c,M+"silent_live/liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1","","","","","",cb));
  search.setMaxUpdateSize(0);ready=true;
 }
 private FaceInfo[] detect(BDFaceImageInstance image){
  BDFaceDetectListConf config=new BDFaceDetectListConf();
  config.usingDetect=true;config.usingAlign=true;config.usingQuality=true;config.usingHeadPose=true;
  return detector.detect(DetectType.DETECT_VIS,AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,image,null,config);
 }
 private boolean quality(FaceInfo f){
  if(f.landmarks==null || f.width<80 || Math.abs(f.yaw)>25 || Math.abs(f.pitch)>25 || Math.abs(f.roll)>25
    || f.bluriness>0.7f || f.illum<40)return false;
  BDFaceOcclusion o=f.occlusion;
  return o!=null && o.leftEye<0.6f && o.rightEye<0.6f && o.nose<0.6f && o.mouth<0.6f
   && o.leftCheek<0.6f && o.rightCheek<0.6f && o.chin<0.6f;
 }
 public synchronized byte[] extract(Bitmap bitmap) throws Exception {
  if(!ready)throw new IllegalStateException("NOT_READY");
  BDFaceImageInstance image=new BDFaceImageInstance(bitmap);
  try{
   FaceInfo[] faces=detect(image);
   if(faces==null||faces.length==0)throw new IllegalArgumentException("NO_FACE");
   if(faces.length!=1)throw new IllegalArgumentException("MULTIPLE_FACES");
   if(!quality(faces[0]))throw new IllegalArgumentException("QUALITY");
   byte[] data=new byte[512];
   if(feature.feature(FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,image,faces[0].landmarks,data)!=128)
    throw new IllegalStateException("FEATURE_FAILED");
   return data;
  }finally{image.destory();}
 }
 public synchronized Match recognize(byte[] nv21,int width,int height,int rotation,boolean mirror,float liveThreshold,float matchThreshold){
  if(!ready)return new Match("NOT_READY",0,0);
  BDFaceImageInstance image=new BDFaceImageInstance(nv21,height,width,BDFaceImageType.BDFACE_IMAGE_TYPE_YUV_NV21,rotation,mirror?1:0);
  try{
   FaceInfo[] faces=detect(image);
   if(faces==null||faces.length==0)return new Match("NO_FACE",0,0);
   if(faces.length!=1)return new Match("MULTIPLE_FACES",0,0);
   FaceInfo f=faces[0]; if(!quality(f))return new Match("QUALITY",0,0);
   float score=live.silentLive(LiveType.BDFACE_SILENT_LIVE_TYPE_RGB,image,f.landmarks);
   if(!Float.isFinite(score)||score<liveThreshold)return new Match("LIVE_FAILED",0,score);
   byte[] data=new byte[512];
   if(feature.feature(FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,image,f.landmarks,data)!=128)return new Match("QUALITY",0,0);
   List<? extends Feature> found=search.search(FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,matchThreshold,1,data);
   if(found==null||found.isEmpty()||!Float.isFinite(found.get(0).getScore())||found.get(0).getScore()<matchThreshold)
    return new Match("NO_MATCH",0,0);
   return new Match("MATCH",found.get(0).getId(),found.get(0).getScore());
  }finally{image.destory();}
 }
 public synchronized void put(int id,byte[] data){
  if(!ready||id<=0||data.length!=512)throw new IllegalStateException("Invalid index input");
  if(search.pushPersonById(id,data)!=0)throw new IllegalStateException("SDK_INDEX_WRITE_FAILED");
 }
 public synchronized void remove(int id){if(!ready)throw new IllegalStateException("NOT_READY"); if(search.delPersonById(id)!=0)throw new IllegalStateException("SDK_INDEX_DELETE_FAILED");}
 public synchronized void clear(){if(!ready||search.featureClear()!=0)throw new IllegalStateException("SDK_INDEX_CLEAR_FAILED");}
}
