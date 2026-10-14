package com.lingluren.yikatong.terminal;
import android.content.Context;
import android.graphics.*;
import android.view.View;
public final class TerminalProgressView extends View {
 private final Paint p=new Paint(3);private long loaded,total;
 public TerminalProgressView(Context c){super(c);}
 public void setProgress(long loaded,long total){this.loaded=loaded;this.total=total;setContentDescription(total>0?"已加载"+loaded+"，共"+total:"正在准备检索库");invalidate();}
 protected void onDraw(Canvas c){float size=Math.min(getWidth(),getHeight()),pad=size*.1f;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(size*.07f);p.setColor(0xffe8eff9);RectF r=new RectF(pad,pad,size-pad,size-pad);c.drawOval(r,p);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(0xff187cff);int percent=total>0?(int)Math.min(100,loaded*100/total):0;c.drawArc(r,-90,percent*3.6f,false,p);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(size*.23f);String label=total>0?percent+"%":"准备中";c.drawText(label,size/2,size/2-(p.ascent()+p.descent())/2,p);}
}
