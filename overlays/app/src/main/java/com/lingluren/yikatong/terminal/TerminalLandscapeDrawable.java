package com.lingluren.yikatong.terminal;
import android.graphics.*;
import android.graphics.drawable.Drawable;
/** Center-crop preserves the landscape aspect ratio in short headers. */
public final class TerminalLandscapeDrawable extends Drawable {
 private final Bitmap bitmap;private final boolean login;private final Paint p=new Paint(3);
 public TerminalLandscapeDrawable(Bitmap bitmap){this(bitmap,false);}
 public TerminalLandscapeDrawable(Bitmap bitmap,boolean login){this.bitmap=bitmap;this.login=login;}
 public void draw(Canvas canvas){Rect r=getBounds();float scale=Math.max((float)r.width()/bitmap.getWidth(),(float)r.height()/bitmap.getHeight());float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;canvas.save();canvas.clipRect(r);canvas.drawBitmap(bitmap,null,new RectF(r.centerX()-w/2,r.top-(h-r.height())*.68f,r.centerX()+w/2,r.top-(h-r.height())*.68f+h),p);if(!login){p.setShader(new LinearGradient(r.left,r.top,r.right,r.top,new int[]{0xe60079ff,0x802d93ff,0x003b9fff},null,Shader.TileMode.CLAMP));canvas.drawRect(r,p);p.setShader(null);}canvas.restore();}
 public void setAlpha(int alpha){p.setAlpha(alpha);}public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}public int getOpacity(){return PixelFormat.OPAQUE;}
}
