package com.lingluren.yikatong.terminal;
import android.graphics.*;
import android.graphics.drawable.Drawable;
/** Center-crop preserves the landscape aspect ratio in short headers. */
public final class TerminalLandscapeDrawable extends Drawable {
 private final Bitmap bitmap;private final Paint p=new Paint(3);
 public TerminalLandscapeDrawable(Bitmap bitmap){this.bitmap=bitmap;}
 public void draw(Canvas canvas){Rect r=getBounds();float scale=Math.max((float)r.width()/bitmap.getWidth(),(float)r.height()/bitmap.getHeight());float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;canvas.save();canvas.clipRect(r);canvas.drawBitmap(bitmap,null,new RectF(r.centerX()-w/2,r.top-(h-r.height())*.68f,r.centerX()+w/2,r.top-(h-r.height())*.68f+h),p);canvas.restore();}
 public void setAlpha(int alpha){p.setAlpha(alpha);}public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}public int getOpacity(){return PixelFormat.OPAQUE;}
}
