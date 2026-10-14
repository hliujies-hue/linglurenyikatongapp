package com.lingluren.yikatong.terminal;
import android.content.Context;
import android.graphics.*;
import android.view.View;
/** Decorative guide only; never represents a detected face or a match result. */
public final class TerminalFaceGuide extends View {
 private final Paint p=new Paint(3);private final boolean scanning;
 public TerminalFaceGuide(Context c,boolean scanning){super(c);this.scanning=scanning;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
 protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),left=w*.2f,right=w*.8f,top=h*.17f,bottom=h*.78f;float d=getResources().getDisplayMetrics().density;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*d);p.setColor(scanning?0xff48b7ff:Color.WHITE);
 if(!scanning){c.drawOval(w*.34f,h*.13f,w*.66f,h*.72f,p);Path shoulders=new Path();shoulders.moveTo(w*.23f,h*.94f);shoulders.cubicTo(w*.23f,h*.69f,w*.38f,h*.86f,w*.4f,h*.68f);shoulders.moveTo(w*.6f,h*.68f);shoulders.cubicTo(w*.62f,h*.86f,w*.77f,h*.69f,w*.77f,h*.94f);c.drawPath(shoulders,p);return;}
 float k=24*d;for(int x=0;x<2;x++)for(int y=0;y<2;y++){float xx=x==0?left:right,yy=y==0?top:bottom;Path corner=new Path();corner.moveTo(xx+(x==0?k:-k),yy);corner.lineTo(xx,yy);corner.lineTo(xx,yy+(y==0?k:-k));c.drawPath(corner,p);}
 }
}
