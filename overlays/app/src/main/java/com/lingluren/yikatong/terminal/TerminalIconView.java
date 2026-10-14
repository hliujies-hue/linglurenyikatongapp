package com.lingluren.yikatong.terminal;
import android.content.Context;import android.graphics.*;import android.view.View;
/** Small code-native icons, independent of device emoji/font substitution. */
public final class TerminalIconView extends View {
 private final String name;private final int color;private final Paint p=new Paint(3);
 public TerminalIconView(Context c,String name,int color){super(c);this.name=name;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
 protected void onDraw(Canvas canvas){float s=Math.min(getWidth(),getHeight());canvas.save();canvas.translate((getWidth()-s)/2,(getHeight()-s)/2);canvas.scale(s/24,s/24);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.9f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path q=new Path();
 switch(name){
 case "check":canvas.drawCircle(12,12,10,p);q.moveTo(7,12);q.lineTo(10.5f,15.5f);q.lineTo(17,8.5f);break;
 case "person":canvas.drawCircle(12,7,3.5f,p);q.moveTo(5,21);q.lineTo(5,19);q.cubicTo(5,11,19,11,19,19);q.lineTo(19,21);break;
 case "clock":canvas.drawCircle(12,12,9,p);q.moveTo(12,6);q.lineTo(12,12);q.lineTo(16,14);break;
 case "alert":canvas.drawCircle(12,12,9,p);q.moveTo(12,6);q.lineTo(12,13);canvas.drawPoint(12,17,p);break;
 case "wifi":canvas.drawArc(2,4,22,24,220,100,false,p);canvas.drawArc(6,9,18,21,220,100,false,p);canvas.drawCircle(12,17,1,p);break;
 case "home":q.moveTo(3,11);q.lineTo(12,3);q.lineTo(21,11);q.moveTo(6,9);q.lineTo(6,21);q.lineTo(10,21);q.lineTo(10,15);q.lineTo(14,15);q.lineTo(14,21);q.lineTo(18,21);q.lineTo(18,9);break;
 case "record":canvas.drawRoundRect(5,3,19,21,2,2,p);for(int y=8;y<18;y+=4)canvas.drawLine(9,y,15,y,p);break;
 case "settings":canvas.drawCircle(12,12,6,p);canvas.drawCircle(12,12,2,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*7,12+(float)Math.sin(a)*7,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,p);}break;
 case "sdk":q.moveTo(12,2);q.lineTo(21,7);q.lineTo(21,17);q.lineTo(12,22);q.lineTo(3,17);q.lineTo(3,7);q.close();q.moveTo(3,7);q.lineTo(12,12);q.lineTo(21,7);q.moveTo(12,12);q.lineTo(12,22);break;
 case "back":q.moveTo(15,4);q.lineTo(7,12);q.lineTo(15,20);break;
 case "pause":canvas.drawLine(8,5,8,19,p);canvas.drawLine(16,5,16,19,p);break;
 case "retry":canvas.drawArc(4,4,20,20,40,290,false,p);q.moveTo(20,5);q.lineTo(20,11);q.lineTo(14,11);break;
 default:canvas.drawCircle(12,12,8,p);break;
 }canvas.drawPath(q,p);canvas.restore();}
}
