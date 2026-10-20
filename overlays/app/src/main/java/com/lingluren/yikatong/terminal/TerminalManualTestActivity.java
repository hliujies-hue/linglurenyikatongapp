package com.lingluren.yikatong.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Private test surface. No runner bridge means immediate exit; never starts TerminalRuntime. */
@SuppressWarnings("deprecation")
public final class TerminalManualTestActivity extends Activity implements TextureView.SurfaceTextureListener {
    public interface Session {
        void frame(byte[] data,int width,int height,int rotation,long capturedNs);
        void enroll();
        void clearTester();
        void close(String reason);
    }
    // Set only by signed instrumentation in the target process. No intent/file can enable this UI.
    public static volatile Session pendingSession;
    private Session session;
    private Camera camera;
    private TextureView preview;
    private TextView message;
    private boolean resumed;
    private int selected=-1,rotation,width,height;
    private long lastFrame;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        session=pendingSession;pendingSession=null;
        if(session==null){finish();return;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16,16,16,16);root.setBackgroundColor(0xff101827);
        TextView title=new TextView(this);title.setText("大库摄像头测试 · 不产生通行记录");title.setTextColor(0xffffffff);title.setTextSize(19);root.addView(title);
        preview=new TextureView(this);preview.setSurfaceTextureListener(this);
        root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this);LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);scroll.addView(controls);
        message=new TextView(this);message.setTextColor(0xffffffff);message.setTextSize(16);message.setText("准备测试库，请稍候……");controls.addView(message);
        button(controls,"录入 / 替换本人（仅本次测试）",()->new AlertDialog.Builder(this)
            .setTitle("录入测试者").setMessage("本人同意后，正对摄像头完成质量与活体检测。特征只在本次内存中使用，退出即丢弃，不上传平台；不会保存照片。")
            .setNegativeButton("取消",null).setPositiveButton("同意，录入本人",(d,w)->session.enroll()).show());
        button(controls,"移除本人，测试未录入情况",()->session.clearTester());
        button(controls,"切换前后摄像头",()->{stopCamera();selected=otherCamera();if(preview.isAvailable())open(preview.getSurfaceTexture());});
        button(controls,"结束测试并恢复 APP",()->finish());
        root.addView(scroll,new LinearLayout.LayoutParams(-1,(int)(getResources().getDisplayMetrics().heightPixels*0.48)));
        setContentView(root);
    }
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());parent.addView(b);}
    public void showStatus(String text){runOnUiThread(()->{if(!isFinishing()&&message!=null)message.setText(text);});}
    @Override protected void onResume(){super.onResume();resumed=true;if(session!=null&&preview.isAvailable())open(preview.getSurfaceTexture());}
    @Override protected void onPause(){resumed=false;stopCamera();if(session!=null)session.close("ACTIVITY_LEFT");super.onPause();if(!isFinishing())finish();}
    @Override protected void onDestroy(){stopCamera();if(session!=null)session.close("ACTIVITY_DESTROYED");super.onDestroy();}
    private int otherCamera(){
        int count=Camera.getNumberOfCameras();
        if(count<1)return -1;
        Camera.CameraInfo current=new Camera.CameraInfo();if(selected>=0)Camera.getCameraInfo(selected,current);
        for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);if(info.facing!=current.facing)return i;}
        return selected>=0?selected:0;
    }
    private void open(SurfaceTexture surface){
        if(camera!=null||!resumed||session==null)return;
        try {
            if(selected<0){for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT){selected=i;break;}}if(selected<0)selected=0;}
            Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(selected,info);camera=Camera.open(selected);
            Camera.Parameters p=camera.getParameters();Camera.Size best=null;long distance=Long.MAX_VALUE;
            for(Camera.Size size:p.getSupportedPreviewSizes()){long d=Math.abs((long)size.width*size.height-640L*480);if(d<distance){best=size;distance=d;}}
            if(best==null||p.getSupportedPreviewFormats()==null||!p.getSupportedPreviewFormats().contains(ImageFormat.NV21))throw new IllegalStateException("NV21 unavailable");
            p.setPreviewSize(best.width,best.height);p.setPreviewFormat(ImageFormat.NV21);
            if(p.getSupportedFocusModes()!=null&&p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            camera.setParameters(p);Camera.Size actual=camera.getParameters().getPreviewSize();width=actual.width;height=actual.height;
            int degrees=getWindowManager().getDefaultDisplay().getRotation()*90;
            boolean front=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT;
            rotation=front?(info.orientation+degrees)%360:(info.orientation-degrees+360)%360;
            camera.setDisplayOrientation(front?(360-rotation)%360:rotation);camera.setPreviewTexture(surface);fit();
            camera.setErrorCallback((code,c)->{showStatus("相机错误："+code+"，请结束后重试");stopCamera();session.close("CAMERA_ERROR");});
            camera.setPreviewCallback((data,c)->{long now=SystemClock.elapsedRealtimeNanos();if(c==camera&&resumed&&data!=null&&now-lastFrame>=250000000L){lastFrame=now;session.frame(data,width,height,rotation,now);}});
            camera.startPreview();
        } catch(Exception e){stopCamera();showStatus("无法打开摄像头，请确认相机权限并结束后重试");session.close("CAMERA_OPEN_FAILED");}
    }
    private void fit(){if(preview.getWidth()==0||preview.getHeight()==0||width==0)return;float source=(rotation==90||rotation==270)?(float)height/width:(float)width/height;float target=(float)preview.getWidth()/preview.getHeight();Matrix m=new Matrix();m.setScale(source>target?source/target:1,source<target?target/source:1,preview.getWidth()/2f,preview.getHeight()/2f);preview.setTransform(m);}
    private void stopCamera(){Camera c=camera;camera=null;if(c!=null){try{c.setPreviewCallback(null);c.stopPreview();}catch(RuntimeException ignored){}finally{c.release();}}}
    public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){open(s);}
    public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){fit();}
    public boolean onSurfaceTextureDestroyed(SurfaceTexture s){stopCamera();return true;}
    public void onSurfaceTextureUpdated(SurfaceTexture s){}
}
