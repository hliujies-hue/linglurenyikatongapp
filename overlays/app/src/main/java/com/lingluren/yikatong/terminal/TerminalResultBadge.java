package com.lingluren.yikatong.terminal;
import android.content.Context;import android.graphics.*;import android.view.View;
public final class TerminalResultBadge extends View {
 private final boolean pass;private final Paint p=new Paint(3);
 public TerminalResultBadge(Context c,boolean pass){super(c);this.pass=pass;setContentDescription(pass?"核验通过":"本次未放行");}
 protected void onDraw(Canvas canvas){float s=Math.min(getWidth(),getHeight()),x=getWidth()/2f,y=getHeight()/2f;int color=pass?0xff06c77a:0xfff49c32;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(s*.007f);for(int i=0;i<3;i++){p.setColor((color&0xffffff)|((22+i*9)<<24));canvas.drawCircle(x,y,s*(.49f-i*.047f),p);}p.setStyle(Paint.Style.FILL);p.setColor(color);canvas.drawCircle(x,y,s*.32f,p);p.setColor(Color.WHITE);p.setStrokeWidth(s*.058f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);if(pass){Path q=new Path();q.moveTo(x-s*.15f,y);q.lineTo(x-s*.04f,y+s*.1f);q.lineTo(x+s*.16f,y-s*.11f);canvas.drawPath(q,p);}else{canvas.drawLine(x,y-s*.13f,x,y+s*.025f,p);canvas.drawPoint(x,y+s*.13f,p);}}
}
