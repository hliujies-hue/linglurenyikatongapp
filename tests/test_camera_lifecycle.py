"""Host regression using fake Android camera callbacks; not a hardware test.
Run with python3 tests/test_camera_lifecycle.py (JDK required).
"""
from pathlib import Path
import subprocess
import tempfile
import shutil

SOURCES = {
'android/app/Activity.java': '''package android.app; public class Activity { public android.view.WindowManager getWindowManager(){return new android.view.WindowManager();} }''',
'android/view/WindowManager.java': '''package android.view; public class WindowManager {public Display getDefaultDisplay(){return new Display();} public static class Display {public int getRotation(){return 0;}}}''',
'android/view/Surface.java': '''package android.view; public class Surface {public static final int ROTATION_90=1,ROTATION_180=2,ROTATION_270=3;}''',
'android/graphics/SurfaceTexture.java': '''package android.graphics; public class SurfaceTexture {}''',
'android/graphics/ImageFormat.java': '''package android.graphics; public class ImageFormat {public static final int NV21=17;}''',
'android/graphics/Matrix.java': '''package android.graphics; public class Matrix {public void setScale(float a,float b,float c,float d){}}''',
'android/view/TextureView.java': '''package android.view; import android.graphics.*; public class TextureView {
 public interface SurfaceTextureListener {void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h);void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h);boolean onSurfaceTextureDestroyed(SurfaceTexture s);void onSurfaceTextureUpdated(SurfaceTexture s);}
 public void setSurfaceTextureListener(SurfaceTextureListener l){} public boolean isAvailable(){return true;} public SurfaceTexture getSurfaceTexture(){return new SurfaceTexture();}public int getWidth(){return 640;}public int getHeight(){return 480;}public void setTransform(Matrix m){}
}''',
'com/example/datalibrary/threshold/SingleBaseConfig.java': '''package com.example.datalibrary.threshold; public class SingleBaseConfig {public static SingleBaseConfig getBaseConfig(){return new SingleBaseConfig();}public int getRBGCameraId(){return 0;}}''',
'com/lingluren/yikatong/terminal/TerminalRuntime.java': '''package com.lingluren.yikatong.terminal; public class TerminalRuntime {public int frames,pauses;public void frame(byte[] b,int w,int h,int r){frames++;}public void pauseCamera(){pauses++;}}''',
'android/hardware/Camera.java': '''package android.hardware;
import java.util.*;import android.graphics.SurfaceTexture;
public class Camera {
 public static int count=1,opened=-1;public static boolean deny,unsupported;public static Camera last;
 public boolean failCleanup,released;public ErrorCallback error;public PreviewCallback preview;
 public interface ErrorCallback {void onError(int code,Camera c);}public interface PreviewCallback {void onPreviewFrame(byte[] b,Camera c);}
 public static class CameraInfo {public static final int CAMERA_FACING_FRONT=1,CAMERA_FACING_BACK=0;public int facing=1,orientation=90;}
 public static class Size {public int width=640,height=480;}
 public static class Parameters {public static final String FOCUS_MODE_CONTINUOUS_VIDEO="continuous-video";public List<Size> getSupportedPreviewSizes(){return Arrays.asList(new Size());}public List<Integer> getSupportedPreviewFormats(){return Arrays.asList(unsupported?99:17);}public List<String> getSupportedFocusModes(){return null;}public void setPreviewSize(int w,int h){}public void setPreviewFormat(int f){}public void setFocusMode(String f){}public Size getPreviewSize(){return new Size();}}
 public static int getNumberOfCameras(){return count;}public static void getCameraInfo(int i,CameraInfo info){info.facing=i==0?CameraInfo.CAMERA_FACING_FRONT:CameraInfo.CAMERA_FACING_BACK;}public static Camera open(int i){if(deny)throw new SecurityException();opened=i;last=new Camera();return last;}
 public Parameters getParameters(){return new Parameters();}public void setParameters(Parameters p){}public void setDisplayOrientation(int r){}public void setPreviewTexture(SurfaceTexture t){}public void startPreview(){}
 public void setErrorCallback(ErrorCallback e){if(e==null&&failCleanup)throw new RuntimeException();error=e;}public void setPreviewCallback(PreviewCallback p){if(p==null&&failCleanup)throw new RuntimeException();preview=p;}public void stopPreview(){if(failCleanup)throw new RuntimeException();}public void release(){released=true;if(failCleanup)throw new RuntimeException();}
}''',
'com/lingluren/yikatong/terminal/CameraLifecycleTest.java': '''package com.lingluren.yikatong.terminal;
import android.hardware.Camera;import android.app.Activity;import android.view.TextureView;
public class CameraLifecycleTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  TerminalRuntime runtime=new TerminalRuntime();String[] error={""};TerminalCamera camera=new TerminalCamera(new Activity(),runtime,new TextureView(),s->error[0]=s);
  check(camera.start(),"start");Camera old=Camera.last;Camera.ErrorCallback delayedError=old.error;Camera.PreviewCallback delayedFrame=old.preview;
  old.failCleanup=true;camera.stop();check(old.released,"release attempted despite callback/stop failures");
  check(camera.start(),"restart after cleanup failure");Camera current=Camera.last;int pauses=runtime.pauses;
  delayedError.onError(100,old);delayedFrame.onPreviewFrame(new byte[1],old);
  check(!current.released&&runtime.pauses==pauses&&runtime.frames==0,"stale callbacks must not stop or feed new camera");
  current.preview.onPreviewFrame(new byte[1],current);check(runtime.frames==1,"current frames delivered");
  current.error.onError(100,current);check(current.released&&error[0].contains("100"),"active error stops camera");
  Camera.count=0;check(!camera.start()&&error[0].contains("未检测到"),"no hardware feedback");Camera.count=1;
  Camera.deny=true;check(!camera.start()&&error[0].contains("权限"),"revoked permission feedback");Camera.deny=false;
  Camera.unsupported=true;check(!camera.start()&&Camera.last.released&&error[0].contains("NV21"),"unsupported format closes camera");Camera.unsupported=false;
  check(camera.preview(),"preview");Camera.last.preview.onPreviewFrame(new byte[1],Camera.last);check(runtime.frames==1,"preview must not submit recognition frames");camera.stop();
  Camera.count=2;TerminalCamera dual=new TerminalCamera(new Activity(),runtime,new TextureView(),s->error[0]=s);
  check(dual.start()&&Camera.opened==1,"default rear even when camera zero is front");Camera rear=Camera.last;
  check(dual.selectOtherLens()&&rear.released,"switch releases rear before opening front");
  check(dual.start()&&Camera.opened==0,"manual switch front");
  check(dual.selectOtherLens()&&dual.start()&&Camera.opened==1,"switch back rear");dual.stop();
  Camera.count=1;TerminalCamera single=new TerminalCamera(new Activity(),runtime,new TextureView(),s->error[0]=s);
  check(single.start()&&!single.selectOtherLens()&&Camera.last.released&&error[0].contains("另一方向"),"single camera fallback and explicit message");
  System.out.println("PASS: stale callbacks, cleanup exceptions, restart, permission, missing camera, NV21 and preview-only gate (fake Android)");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='ykt-camera-') as directory:
    root = Path(directory)
    files = []
    for name, contents in SOURCES.items():
        file = root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(contents, encoding='utf-8')
        files.append(str(file))
    production = Path(__file__).resolve().parents[1] / 'overlays/app/src/main/java/com/lingluren/yikatong/terminal/TerminalCamera.java'
    compiler = ['javac'] if shutil.which('javac') else ['java', 'com.sun.tools.javac.Main']
    subprocess.run([*compiler, '-encoding', 'UTF-8', '-d', str(root), *files, str(production)], check=True)
    subprocess.run(['java', '-cp', str(root), 'com.lingluren.yikatong.terminal.CameraLifecycleTest'], check=True)
