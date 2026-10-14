package com.lingluren.yikatong.terminal;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.view.Surface;
import android.view.TextureView;
import com.example.datalibrary.threshold.SingleBaseConfig;

/** Camera frames stay in RAM and are never written to the photo gallery. */
@SuppressWarnings("deprecation")
public final class TerminalCamera implements TextureView.SurfaceTextureListener {
    public interface Error {void show(String text);}
    private final Activity activity;private final TerminalRuntime runtime;private final TextureView view;private final Error error;
    private Camera camera;private boolean wanted,previewOnly;private int rotation,bufferWidth,bufferHeight;
    public TerminalCamera(Activity activity,TerminalRuntime runtime,TextureView view,Error error){this.activity=activity;this.runtime=runtime;this.view=view;this.error=error;view.setSurfaceTextureListener(this);}
    public boolean preview(){previewOnly=true;wanted=true;if(view.isAvailable())open(view.getSurfaceTexture());return wanted;}
    public boolean start(){previewOnly=false;wanted=true;if(view.isAvailable())open(view.getSurfaceTexture());return wanted;}
    private void open(SurfaceTexture texture){
        if(camera!=null||!wanted)return;
        try{
            int count=Camera.getNumberOfCameras(),id=SingleBaseConfig.getBaseConfig().getRBGCameraId();
            if(id<0||id>=count){id=0;for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT){id=i;break;}}}
            if(count==0){stop();error.show("未检测到摄像头，请检查设备硬件或外接摄像头连接");return;}
            Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(id,info);camera=Camera.open(id);
            Camera.Parameters p=camera.getParameters();Camera.Size best=null;long distance=Long.MAX_VALUE;
            for(Camera.Size size:p.getSupportedPreviewSizes()){long d=Math.abs((long)size.width*size.height-640L*480);if(d<distance){distance=d;best=size;}}
            if(best==null)throw new IllegalStateException("摄像头没有可用预览尺寸");
            if(p.getSupportedPreviewFormats()==null||!p.getSupportedPreviewFormats().contains(android.graphics.ImageFormat.NV21)){stop();error.show("此摄像头不支持NV21图像格式，当前核验引擎无法使用");return;}
            p.setPreviewSize(best.width,best.height);p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            if(p.getSupportedFocusModes()!=null && p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            camera.setParameters(p);
            int screen=activity.getWindowManager().getDefaultDisplay().getRotation(),degrees=screen==Surface.ROTATION_90?90:screen==Surface.ROTATION_180?180:screen==Surface.ROTATION_270?270:0;
            boolean front=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT;
            rotation=front?(info.orientation+degrees)%360:(info.orientation-degrees+360)%360;
            camera.setDisplayOrientation(front?(360-rotation)%360:rotation);camera.setPreviewTexture(texture);
            Camera.Size actual=camera.getParameters().getPreviewSize();bufferWidth=actual.width;bufferHeight=actual.height;fitPreview();
            camera.setErrorCallback((code,c)->{if(c!=camera)return;stop();error.show("摄像头异常（"+code+"），请关闭其他相机应用后重新开始核验");});
            camera.setPreviewCallback((data,c)->{if(c==camera&&wanted&&!previewOnly)runtime.frame(data,actual.width,actual.height,rotation);});camera.startPreview();
        }catch(SecurityException e){stop();error.show("相机权限已关闭，请在系统应用权限中开启后重新核验");}
        catch(Exception e){stop();error.show("摄像头无法打开，请检查相机权限或关闭其他占用相机的应用");}
    }
    private void fitPreview(){
        if(view.getWidth()==0||view.getHeight()==0||bufferWidth==0||bufferHeight==0)return;
        float source=(rotation==90||rotation==270)?(float)bufferHeight/bufferWidth:(float)bufferWidth/bufferHeight;
        float target=(float)view.getWidth()/view.getHeight();
        android.graphics.Matrix transform=new android.graphics.Matrix();
        transform.setScale(source>target?source/target:1f,source<target?target/source:1f,view.getWidth()/2f,view.getHeight()/2f);
        view.setTransform(transform);
    }
    public void stop(){boolean active=wanted||camera!=null;wanted=false;if(active&&!previewOnly)runtime.pauseCamera();if(camera!=null){Camera c=camera;camera=null;try{c.setPreviewCallback(null);}catch(RuntimeException ignored){}try{c.setErrorCallback(null);}catch(RuntimeException ignored){}try{c.stopPreview();}catch(RuntimeException ignored){}try{c.release();}catch(RuntimeException ignored){}}}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){open(surface);}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){fitPreview();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){stop();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface){}
}
