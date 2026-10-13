package com.lingluren.yikatong.terminal;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.baidu.idl.main.facesdk.*;
import com.baidu.idl.main.facesdk.callback.Callback;
import com.baidu.idl.main.facesdk.model.*;
import com.example.datalibrary.model.GlobalSet;
import com.example.datalibrary.threshold.BaseConfig;
import com.example.datalibrary.threshold.SingleBaseConfig;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Calls the supplied Demo's actual 8.5 SDK. All access is serialized by TerminalRuntime. */
public final class TerminalFaceEngine {
    private FaceDetect detect;
    private FaceLive live;
    private FaceFeature feature;
    private FaceSearch search;
    private volatile boolean ready;
    private String diagnostics="等待相机画面";
    public String diagnostics(){return diagnostics;}
    private interface Init { void run(Callback callback); }
    private void await(Init op,String stage) throws Exception {
        CountDownLatch done=new CountDownLatch(1);int[] code={-1};
        op.run((c,message)->{code[0]=c;done.countDown();});
        if(!done.await(45,TimeUnit.SECONDS))throw new IllegalStateException(stage+"超时，请重启应用后重试");
        if(code[0]!=0)throw new IllegalStateException(stage+"失败（"+code[0]+"）");
    }
    public boolean ready(){return ready;}
    public void init(Context context,TerminalStore store) throws Exception {
        if(ready)return;
        detect=new FaceDetect();live=new FaceLive();feature=new FaceFeature();search=new FaceSearch();
        applySettings();
        await(cb->detect.initModel(context,GlobalSet.DETECT_VIS_MODEL,GlobalSet.ALIGN_RGB_MODEL,BDFaceSDKCommon.DetectType.DETECT_VIS,BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,cb),"人脸检测模型加载");
        await(cb->detect.initQuality(context,GlobalSet.BLUR_MODEL,GlobalSet.OCCLUSION_MODEL,cb),"质量检测模型加载");
        await(cb->live.initModel(context,GlobalSet.LIVE_VIS_MODEL,"","","",GlobalSet.LIVE_NIR_MODEL,GlobalSet.LIVE_DEPTH_MODEL,cb),"活体模型加载");
        await(cb->feature.initModel(context,GlobalSet.RECOGNIZE_IDPHOTO_MODEL,GlobalSet.RECOGNIZE_VIS_MODEL,GlobalSet.RECOGNIZE_NIR_MODEL,"",cb),"特征模型加载");
        search.setNeedJoinDB(false);search.setMaxUpdateSize(0);
        check(search.featureClear(),"检索库清空");
        try(Cursor rows=store.allFaces()){while(rows.moveToNext())check(search.pushPersonById(rows.getInt(0),rows.getBlob(1)),"检索库恢复");}
        ready=true;
    }
    public void applySettings(){
        BaseConfig c=SingleBaseConfig.getBaseConfig();
        BDFaceSDKConfig config=new BDFaceSDKConfig();config.maxDetectNum=2;config.minFaceSize=c.getMinimumFace();config.notRGBFaceThreshold=c.getFaceThreshold();
        config.isCheckBlur=true;config.isOcclusion=true;config.isIllumination=true;config.isHeadPose=true;detect.loadConfig(config);
    }
    private void check(int code,String operation){if(code!=0){ready=false;throw new IllegalStateException(operation+"失败（"+code+"），禁止确认人员任务");}}
    private static boolean finite(float n){return !Float.isNaN(n)&&!Float.isInfinite(n);}
    private void require(){if(!ready)throw new IllegalStateException("算法尚未就绪");}
    public void reset(TerminalStore store){require();check(search.featureClear(),"检索库重置");store.clearFaces();}
    private FaceInfo[] detect(BDFaceImageInstance image){
        BDFaceDetectListConf flags=new BDFaceDetectListConf();flags.usingDetect=true;flags.usingAlign=true;flags.usingQuality=true;flags.usingHeadPose=true;
        return detect.detect(BDFaceSDKCommon.DetectType.DETECT_VIS,BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,image,null,flags);
    }
    private boolean quality(FaceInfo f){
        BaseConfig c=SingleBaseConfig.getBaseConfig();
        if(Math.abs(f.yaw)>c.getGesture()||Math.abs(f.roll)>c.getGesture()||Math.abs(f.pitch)>c.getGesture()||f.bluriness>c.getBlur()||f.illum<c.getIllumination())return false;
        BDFaceOcclusion o=f.occlusion;
        return o!=null && o.leftEye<=c.getLeftEye()&&o.rightEye<=c.getRightEye()&&o.nose<=c.getNose()&&o.mouth<=c.getMouth()&&o.leftCheek<=c.getLeftCheek()&&o.rightCheek<=c.getRightCheek()&&o.chin<=c.getChinContour();
    }
    private byte[] feature(BDFaceImageInstance image,FaceInfo face){
        byte[] bytes=new byte[TerminalSdkContract.FEATURE_BYTES];float size=feature.feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,image,face.landmarks,bytes);
        if(!TerminalSdkContract.featureSucceeded(size))throw new IllegalStateException("SDK特征提取失败（返回值："+size+"），请检查算法状态");return bytes;
    }
    public static final class PhotoRejectedException extends Exception {
        PhotoRejectedException(String message){super(message);}
    }
    public boolean reuse(TerminalStore store,long user,String hash){
        require();byte[] cached=store.faceFeature(user,hash);
        if(cached==null||cached.length!=TerminalSdkContract.FEATURE_BYTES)return false;
        // Reapply to native search before ACK, including recovery after process death.
        check(search.pushPersonById(store.faceId(user),cached),"缓存人员特征恢复");return true;
    }
    public void upsert(TerminalStore store,long user,String hash,byte[] photo) throws Exception {
        require();BitmapFactory.Options dimensions=new BitmapFactory.Options();dimensions.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(photo,0,photo.length,dimensions);
        if(dimensions.outWidth<80||dimensions.outHeight<80||(long)dimensions.outWidth*dimensions.outHeight>16000000)throw new PhotoRejectedException("照片尺寸无效，请重新上传清晰正面照");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(dimensions.outWidth,dimensions.outHeight)/options.inSampleSize>1600)options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(photo,0,photo.length,options);if(bitmap==null)throw new PhotoRejectedException("照片无法解码，请重新上传JPG或PNG照片");
        BDFaceImageInstance image=null;
        try {
            image=new BDFaceImageInstance(bitmap);FaceInfo[] faces=detect(image);
            if(faces==null||faces.length==0)throw new PhotoRejectedException("照片未检测到人脸，请更换清晰正面照");
            if(faces.length!=1)throw new PhotoRejectedException("照片检测到多张人脸，请只保留本人");
            FaceInfo face=faces[0];BaseConfig limits=SingleBaseConfig.getBaseConfig();
            if(Math.abs(face.yaw)>limits.getGesture()||Math.abs(face.roll)>limits.getGesture()||Math.abs(face.pitch)>limits.getGesture())throw new PhotoRejectedException("人脸角度过大，请更换正面照");
            if(face.bluriness>limits.getBlur())throw new PhotoRejectedException("人脸照片模糊，请重新拍摄");
            if(face.illum<limits.getIllumination())throw new PhotoRejectedException("人脸光照不足，请更换明亮照片");
            if(!quality(face))throw new PhotoRejectedException("人脸存在遮挡或质量检测未通过，请露出完整五官");
            byte[] template=feature(image,faces[0]);int id=store.saveFace(user,hash,template);check(search.pushPersonById(id,template),"人员特征入库");
        }finally{if(image!=null)image.destory();bitmap.recycle();}
    }
    public void delete(TerminalStore store,long user){
        require();int id=store.faceId(user);if(id==0)return;
        // Persist removal first: a process death cannot restore a revoked feature.
        store.deleteFace(user);check(search.delPersonById(id),"人员特征删除");
    }
    public static final class Match {
        public final boolean present;public final long user;public final String failure;
        Match(boolean present,long user,String failure){this.present=present;this.user=user;this.failure=failure;}
    }
    public Match identify(TerminalStore store,byte[] nv21,int width,int height,int rotation) {
        require();BDFaceImageInstance image=new BDFaceImageInstance(nv21,height,width,BDFaceSDKCommon.BDFaceImageType.BDFACE_IMAGE_TYPE_YUV_NV21,rotation,0);
        try {
            FaceInfo[] faces=detect(image);
            if(faces==null||faces.length==0){diagnostics="未检测到人脸";return new Match(false,0,"");}
            diagnostics="正在检测人脸质量";
            if(faces.length!=1)return new Match(true,0,"GUIDE_SINGLE_FACE");
            FaceInfo f=faces[0];BaseConfig c=SingleBaseConfig.getBaseConfig();
            if(Math.abs(f.yaw)>c.getGesture()||Math.abs(f.roll)>c.getGesture()||Math.abs(f.pitch)>c.getGesture())return new Match(true,0,"GUIDE_POSE");
            if(f.bluriness>c.getBlur())return new Match(true,0,"GUIDE_BLUR");
            if(f.illum<c.getIllumination())return new Match(true,0,"GUIDE_LIGHT");
            if(!quality(f))return new Match(true,0,"GUIDE_OCCLUSION");
            // Always enforce RGB liveness; the generic Demo's disable switch cannot bypass admission.
            float threshold=SingleBaseConfig.getBaseConfig().getRgbLiveScore();
            if(!finite(threshold)||threshold<=0||threshold>1)throw new IllegalStateException("活体阈值无效");
            float score=live.silentLive(BDFaceSDKCommon.LiveType.BDFACE_SILENT_LIVE_TYPE_RGB,image,faces[0].landmarks,threshold);
            diagnostics=String.format(java.util.Locale.US,"RGB活体：%.3f / %.3f",score,threshold);
            if(!finite(score)||score<threshold)return new Match(true,0,"LIVENESS_FAILED");
            float minimum=SingleBaseConfig.getBaseConfig().getLiveThreshold();
            if(!finite(minimum)||minimum<=0||minimum>100)throw new IllegalStateException("识别阈值无效");
            List<? extends Feature> found=search.search(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,minimum,1,feature(image,faces[0]),true);
            if(found==null||found.isEmpty()){diagnostics+=" · 未找到达到识别阈值 "+minimum+" 的人员";return new Match(true,0,"NO_MATCH");}
            float similarity=found.get(0).getScore();
            diagnostics+=String.format(java.util.Locale.US," · 匹配：%.2f / %.2f",similarity,minimum);
            if(!finite(similarity)||similarity<minimum)return new Match(true,0,"NO_MATCH");
            long user=store.userForFace(found.get(0).getId());diagnostics+=user>0?" · 本地人员已匹配":" · 检索编号未映射到本地人员";return new Match(true,user,user>0?"":"NO_MATCH");
        }finally{image.destory();}
    }
}
