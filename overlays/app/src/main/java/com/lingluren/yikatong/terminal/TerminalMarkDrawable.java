package com.lingluren.yikatong.terminal;
import android.graphics.*;import android.graphics.drawable.Drawable;
/** Built-in scan mark remains available before remote branding has loaded. */
public final class TerminalMarkDrawable extends Drawable {
 private final Paint p=new Paint(3);
 public void draw(Canvas c){Rect r=getBounds();c.save();c.translate(r.left,r.top);c.scale(r.width()/64f,r.height()/64f);p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,0,64,64,0xff3257ff,0xff008fff,Shader.TileMode.CLAMP));c.drawRoundRect(0,0,64,64,13,13,p);p.setShader(null);p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setStrokeCap(Paint.Cap.ROUND);for(int x=0;x<2;x++)for(int y=0;y<2;y++){float xx=x==0?11:53,yy=y==0?11:53;Path q=new Path();q.moveTo(xx+(x==0?9:-9),yy);q.lineTo(xx,yy);q.lineTo(xx,yy+(y==0?9:-9));c.drawPath(q,p);}c.drawOval(21,18,43,43,p);c.drawArc(24,29,40,39,20,140,false,p);c.drawPoint(27,28,p);c.drawPoint(37,28,p);p.setStyle(Paint.Style.FILL);p.setColor(0xff00c780);c.drawCircle(49,48,10,p);p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);Path q=new Path();q.moveTo(44,48);q.lineTo(48,52);q.lineTo(55,44);c.drawPath(q,p);c.restore();}
 public void setAlpha(int a){p.setAlpha(a);}public void setColorFilter(ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
