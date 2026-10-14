package com.lingluren.yikatong.terminal;
import android.graphics.*;
import android.graphics.drawable.Drawable;
/** Resolution-independent blue landscape header; no remote artwork dependency. */
public final class TerminalHeaderDrawable extends Drawable {
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 @Override public void draw(Canvas canvas){Rect r=getBounds();float w=r.width(),h=r.height();canvas.save();canvas.translate(r.left,r.top);
 paint.setShader(new LinearGradient(0,0,w,h,Color.rgb(61,156,255),Color.rgb(24,110,242),Shader.TileMode.CLAMP));canvas.drawRoundRect(0,0,w,h,22,22,paint);paint.setShader(null);
 for(int layer=0;layer<2;layer++){Path p=new Path();p.moveTo(0,h);p.lineTo(0,h*.82f);for(int i=0;i<=12;i++)p.lineTo(w*i/12f,h*(.72f+(float)Math.sin(i*1.7+layer)*.13f+layer*.13f));p.lineTo(w,h);p.close();paint.setColor(layer==0?0x185075ba:0x207ad8ff);canvas.drawPath(p,paint);}canvas.restore();
 }
 @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
 @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
 @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
