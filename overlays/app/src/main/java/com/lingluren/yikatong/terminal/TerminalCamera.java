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
    private Camera camera;private boolean wanted;private int rotation;
    public TerminalCamera(Activity activity,TerminalRuntime runtime,TextureView view,Error error){this.activity=activity;this.runtime=runtime;this.view=view;this.error=error;view.setSurfaceTextureListener(this);}
    public boolean start(){wanted=true;if(view.isAvailable())open(view.getSurfaceTexture());return wanted;}
    private void open(SurfaceTexture texture){
        if(camera!=null||!wanted)return;
        try{
            int count=Camera.getNumberOfCameras(),id=SingleBaseConfig.getBaseConfig().getRBGCameraId();
            if(id<0||id>=count){id=0;for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT){id=i;break;}}}
            Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(id,info);camera=Camera.open(id);
            Camera.Parameters p=camera.getParameters();Camera.Size best=null;long distance=Long.MAX_VALUE;
            for(Camera.Size size:p.getSupportedPreviewSizes()){long d=Math.abs((long)size.width*size.height-640L*480);if(d<distance){distance=d;best=size;}}
            if(best==null)throw new IllegalStateException("摄像头没有可用预览尺寸");
            p.setPreviewSize(best.width,best.height);p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            if(p.getSupportedFocusModes()!=null && p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            camera.setParameters(p);
            int screen=activity.getWindowManager().getDefaultDisplay().getRotation(),degrees=screen==Surface.ROTATION_90?90:screen==Surface.ROTATION_180?180:screen==Surface.ROTATION_270?270:0;
            boolean front=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT;
            rotation=front?(info.orientation+degrees)%360:(info.orientation-degrees+360)%360;
            camera.setDisplayOrientation(front?(360-rotation)%360:rotation);camera.setPreviewTexture(texture);
            Camera.Size actual=camera.getParameters().getPreviewSize();
            camera.setErrorCallback((code,c)->{stop();error.show("摄像头异常（"+code+"），请重试");});
            camera.setPreviewCallback((data,c)->{if(wanted)runtime.frame(data,actual.width,actual.height,rotation);});camera.startPreview();
        }catch(Exception e){stop();error.show("摄像头无法打开，请检查相机权限或关闭其他占用相机的应用");}
    }
    public void stop(){wanted=false;runtime.pauseCamera();if(camera!=null){Camera c=camera;camera=null;try{c.setPreviewCallback(null);c.stopPreview();}catch(RuntimeException ignored){}finally{c.release();}}}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){open(surface);}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){stop();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface){}
}
