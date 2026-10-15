package com.lingluren.yikatong.terminal;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.view.Surface;
import android.view.TextureView;


/** Camera frames stay in RAM and are never written to the photo gallery. */
@SuppressWarnings("deprecation")
public final class TerminalCamera implements TextureView.SurfaceTextureListener {
    public interface Error {void show(String text);}
    public interface State {void changed(boolean running);}
    private final Activity activity;private final TerminalRuntime runtime;private final TextureView view;private final Error error;
    private final android.content.SharedPreferences lensPreferences;
    private State stateListener;
    private Camera camera;private boolean wanted,previewOnly;private int rotation,bufferWidth,bufferHeight,selectedId=-1;
    public TerminalCamera(Activity activity,TerminalRuntime runtime,TextureView view,Error error){this.activity=activity;this.runtime=runtime;this.view=view;this.error=error;lensPreferences=activity.getSharedPreferences("terminal_camera",android.content.Context.MODE_PRIVATE);selectedId=lensPreferences.getInt("selectedId",-1);view.setSurfaceTextureListener(this);}
    public void setStateListener(State listener){stateListener=listener;}
    public boolean preview(){previewOnly=true;wanted=true;if(view.isAvailable())open(view.getSurfaceTexture());return wanted;}
    public boolean start(){previewOnly=false;wanted=true;if(view.isAvailable())open(view.getSurfaceTexture());return wanted;}
    private void open(SurfaceTexture texture){
        if(camera!=null||!wanted)return;
        try{
            int count=Camera.getNumberOfCameras(),id=selectedId;
            if(count==0){stop();error.show("未检测到摄像头，请检查设备硬件或外接摄像头连接");return;}
            int preferred=lensPreferences.getInt("facing",Camera.CameraInfo.CAMERA_FACING_BACK);
            if(id>=0&&id<count){Camera.CameraInfo selected=new Camera.CameraInfo();Camera.getCameraInfo(id,selected);if(selected.facing!=preferred)id=-1;}
            if(id<0||id>=count){id=-1;for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);if(info.facing==preferred){id=i;break;}}}
            boolean fallback=id<0;
            if(fallback){id=0;for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){id=i;break;}}}
            selectedId=id;Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(id,info);camera=Camera.open(id);
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
            boolean hadPreference=lensPreferences.contains("facing");rememberLens(id,info.facing);
            if(fallback&&hadPreference)android.widget.Toast.makeText(activity,"上次选择的摄像头当前不可用，已切换至"+(front?"前置":"后置")+"摄像头",android.widget.Toast.LENGTH_LONG).show();
            if(stateListener!=null)stateListener.changed(true);
        }catch(SecurityException e){stop();error.show("相机权限已关闭，请在系统应用权限中开启后重新核验");}
        catch(Exception e){stop();error.show("摄像头无法打开，请检查相机权限或关闭其他占用相机的应用");}
    }
    public boolean selectOtherLens(){
        try{
            int count=Camera.getNumberOfCameras();
            Camera.CameraInfo current=new Camera.CameraInfo();
            if(selectedId>=0&&selectedId<count)Camera.getCameraInfo(selectedId,current);
            else current.facing=Camera.CameraInfo.CAMERA_FACING_BACK;
            for(int i=0;i<count;i++){Camera.CameraInfo next=new Camera.CameraInfo();Camera.getCameraInfo(i,next);
                if(next.facing!=current.facing){stop();selectedId=i;rememberLens(i,next.facing);return true;}}
            stop();error.show("未检测到另一方向的摄像头，无法切换前后置；可重新开始使用当前摄像头");
        }catch(RuntimeException e){stop();error.show("读取前后置摄像头信息失败，请重新进入核验页面");}
        return false;
    }
    private void rememberLens(int id,int facing){lensPreferences.edit().putInt("selectedId",id).putInt("facing",facing).apply();}
    public String lensName(){
        if(selectedId<0)return "后置优先";
        try{Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(selectedId,info);return info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?"前置":"后置";}
        catch(RuntimeException e){return "未打开";}
    }
    private void fitPreview(){
        if(view.getWidth()==0||view.getHeight()==0||bufferWidth==0||bufferHeight==0)return;
        float source=(rotation==90||rotation==270)?(float)bufferHeight/bufferWidth:(float)bufferWidth/bufferHeight;
        float target=(float)view.getWidth()/view.getHeight();
        android.graphics.Matrix transform=new android.graphics.Matrix();
        transform.setScale(source>target?source/target:1f,source<target?target/source:1f,view.getWidth()/2f,view.getHeight()/2f);
        view.setTransform(transform);
    }
    public void stop(){boolean active=wanted||camera!=null;wanted=false;if(active&&!previewOnly)runtime.pauseCamera();if(camera!=null){Camera c=camera;camera=null;try{c.setPreviewCallback(null);}catch(RuntimeException ignored){}try{c.setErrorCallback(null);}catch(RuntimeException ignored){}try{c.stopPreview();}catch(RuntimeException ignored){}try{c.release();}catch(RuntimeException ignored){}}if(active&&stateListener!=null)stateListener.changed(false);}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){open(surface);}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){fitPreview();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){stop();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface){}
}
