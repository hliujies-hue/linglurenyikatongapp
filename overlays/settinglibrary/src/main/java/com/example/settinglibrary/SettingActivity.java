package com.example.settinglibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.baidu.idl.main.facesdk.FaceAuth;
import com.baidu.idl.main.facesdk.license.BDFaceLicenseAuthInfo;
import com.example.datalibrary.threshold.BaseConfig;
import com.example.datalibrary.threshold.SingleBaseConfig;
import com.example.settinglibrary.utils.ConfigUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Terminal-only controls. Vendor screens remain packaged but cannot be used to edit inactive modes. */
public class SettingActivity extends Activity {
    private static final int BLUE=0xff1677ff,INK=0xff1f2329,MUTED=0xff646a73;
    private interface Value { double read(BaseConfig config); }
    private static final class Parameter {
        final String key,title,note;
        final double minimum,maximum;
        final boolean integer;
        final Value value;
        EditText input;
        String loaded;
        Parameter(String key,String title,String note,double minimum,double maximum,boolean integer,Value value){
            this.key=key;this.title=title;this.note=note;this.minimum=minimum;this.maximum=maximum;this.integer=integer;this.value=value;
        }
        String format(double number){return integer?String.valueOf((int)number):Float.toString((float)number);}
        String range(){return format(minimum)+"～"+format(maximum);}
    }
    private final ArrayList<Parameter> parameters=new ArrayList<>();
    private TextView status;
    private Button save;
    private String loadedFile="";
    private boolean initialized;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BLUE);getWindow().setNavigationBarColor(Color.WHITE);
        parameters.add(new Parameter("minimumFace","最小人脸尺寸","单位：像素。数值越大，人员需要越靠近镜头。",30,200,true,c->c.getMinimumFace()));
        parameters.add(new Parameter("faceThreshold","人脸检测置信度","数值越高，检测到人脸所需的置信度越高。",0.01,1,false,c->c.getFaceThreshold()));
        parameters.add(new Parameter("rgbLiveScore","RGB活体阈值","本机使用普通手机相机，活体检查始终开启。数值越高，活体判断越严格。",0.01,1,false,c->c.getRgbLiveScore()));
        parameters.add(new Parameter("liveScoreThreshold","人员识别相似度","生活照人员库检索阈值，范围1～100。数值越低，误匹配风险越高。",1,100,false,c->c.getLiveThreshold()));
        parameters.add(new Parameter("gesture","人脸最大偏转角度","单位：度。限制左右转头、抬头低头与歪头。数值越低，要求越正。",1,90,true,c->c.getGesture()));
        parameters.add(new Parameter("blur","最大模糊度","数值越低，对图像清晰度的要求越高。",0,1,false,c->c.getBlur()));
        parameters.add(new Parameter("illum","最低光照分数","数值越高，对现场光照的要求越高。",0,1,false,c->c.getIllumination()));
        parameters.add(new Parameter("leftEye","左眼遮挡上限","数值越低，允许的遮挡越少。",0,1,false,c->c.getLeftEye()));
        parameters.add(new Parameter("rightEye","右眼遮挡上限","数值越低，允许的遮挡越少。",0,1,false,c->c.getRightEye()));
        parameters.add(new Parameter("leftCheek","左脸颊遮挡上限","数值越低，允许的遮挡越少。",0,1,false,c->c.getLeftCheek()));
        parameters.add(new Parameter("rightCheek","右脸颊遮挡上限","数值越低，允许的遮挡越少。",0,1,false,c->c.getRightCheek()));
        parameters.add(new Parameter("nose","鼻部遮挡上限","数值越低，允许的遮挡越少。",0,1,false,c->c.getNose()));
        parameters.add(new Parameter("mouth","嘴部遮挡上限","数值越低，允许的遮挡越少。",0,1,false,c->c.getMouth()));
        parameters.add(new Parameter("chinContour","下巴遮挡上限","数值越低，允许的遮挡越少。",0,1,false,c->c.getChinContour()));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff5f6f8);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(20),dp(16),dp(24));scroll.addView(root);setContentView(scroll);
        TextView title=text("本机算法设置",24,INK);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        TextView explanation=text("只显示本核验端实际使用的参数。质量检测与RGB活体始终开启；红外、深度、模型切换及镜像等未接入项不提供编辑入口。参数影响本机人员照片质量检查与现场识别，保存后返回首页手动开始核验。",14,MUTED);explanation.setPadding(0,dp(12),0,dp(16));root.addView(explanation);
        status=text("正在读取本机配置…",14,MUTED);status.setPadding(0,0,0,dp(16));root.addView(status);
        try{
            if(!ConfigUtils.isConfigExit(getApplicationContext())||!Boolean.TRUE.equals(ConfigUtils.initConfig(getApplicationContext())))throw new IllegalStateException("本机算法配置文件缺失或内容无法读取");
            loadedFile=readConfig();new JSONObject(loadedFile);initialized=true;
        }catch(Exception error){status.setText("设置未加载："+safe(error)+"。为避免覆盖原参数，本页暂不允许保存；请返回设备诊断排查。");status.setTextColor(0xffb54724);}
        BaseConfig current=SingleBaseConfig.getBaseConfig();
        for(int i=0;i<parameters.size();i++){
            if(i==0)section(root,"检测与识别");else if(i==4)section(root,"质量与遮挡");
            Parameter parameter=parameters.get(i);LinearLayout box=card(root);
            TextView label=text(parameter.title,16,INK);label.setTypeface(null,Typeface.BOLD);box.addView(label);
            TextView hint=text(parameter.note+" 可填范围："+parameter.range()+"。",14,MUTED);hint.setPadding(0,dp(8),0,dp(10));box.addView(hint);
            EditText input=new EditText(this);parameter.input=input;input.setTextSize(16);input.setTextColor(INK);input.setSingleLine();input.setMinHeight(dp(52));input.setPadding(dp(12),dp(12),dp(12),dp(12));input.setBackground(shape(0xfff7f8fa,0xffdfe4eb));input.setInputType(InputType.TYPE_CLASS_NUMBER|(parameter.integer?0:InputType.TYPE_NUMBER_FLAG_DECIMAL));input.setContentDescription(parameter.title+"，范围"+parameter.range());parameter.loaded=parameter.format(parameter.value.read(current));input.setText(parameter.loaded);input.setEnabled(initialized);box.addView(input,new LinearLayout.LayoutParams(-1,-2));
        }
        if(initialized)status.setText("已读取本机参数。当前页面修改需点击“保存本机参数”后生效。");
        save=button(root,"保存本机参数",this::confirmSave,true);save.setEnabled(initialized);
        Button defaults=button(root,"填入出厂默认值",()->new AlertDialog.Builder(this).setTitle("填入默认参数？").setMessage("仅替换本页输入框，仍需点击保存后生效。不会修改算法授权、人员库或待确认记录。").setNegativeButton("取消",null).setPositiveButton("填入默认值",(d,w)->{BaseConfig baseline=new BaseConfig();for(Parameter p:parameters){p.input.setText(p.format(p.value.read(baseline)));p.input.setError(null);}status.setText("已填入默认值，尚未保存。请检查参数并点击保存。");}).show(),false);defaults.setEnabled(initialized);
        button(root,"查看最近参数修改",this::showHistory,false);
        button(root,"返回核验端",this::onBackPressed,false);
        LinearLayout license=card(root);section(license,"算法授权");TextView licenseText=text(authText(),14,MUTED);license.addView(licenseText);
    }
    private JSONObject entered(){
        JSONObject values=new JSONObject();boolean valid=true;
        for(Parameter p:parameters){
            try{
                String raw=p.input.getText().toString().trim();
                if(raw.isEmpty())throw new IllegalArgumentException("请输入"+p.title);
                double number=Double.parseDouble(raw);
                if(Double.isNaN(number)||Double.isInfinite(number)||number<p.minimum||number>p.maximum||(p.integer&&number!=Math.rint(number)))throw new IllegalArgumentException(p.title+"必须是"+p.range()+(p.integer?"之间的整数":"之间的数字"));
                if(p.integer)values.put(p.key,(int)number);else values.put(p.key,number);
                p.input.setError(null);
            }catch(NumberFormatException error){p.input.setError(p.title+"只能填写"+p.range()+(p.integer?"之间的整数":"之间的数字"));if(valid)p.input.requestFocus();valid=false;}
            catch(Exception error){p.input.setError(safe(error));if(valid)p.input.requestFocus();valid=false;}
        }
        return valid?values:null;
    }
    private void confirmSave(){
        if(!initialized)return;
        JSONObject values=entered();if(values==null){status.setText("尚未保存：请按输入框下的具体原因修正参数。");return;}
        try{
            JSONObject before=new JSONObject(loadedFile);StringBuilder changes=new StringBuilder();
            for(Parameter p:parameters){double previous=before.optDouble(p.key,Double.NaN);if(Double.isNaN(previous)||Double.isInfinite(previous)||Math.abs(previous-values.getDouble(p.key))>0.000001)changes.append(p.title).append("：").append(p.loaded).append(" → ").append(p.format(values.getDouble(p.key))).append('\n');}
            if(changes.length()==0){status.setText("参数未发生变化，无需重复保存。");return;}
            new AlertDialog.Builder(this).setTitle("确认修改本机算法参数").setMessage(changes+"\n阈值会影响识别与活体判断。请确认修改符合现场要求；返回后手动开始核验。").setNegativeButton("继续编辑",null).setPositiveButton("保存修改",(d,w)->persist(values,changes.toString())).show();
        }catch(Exception error){status.setText("无法生成参数变更："+safe(error));}
    }
    private void persist(JSONObject values,String summary){
        save.setEnabled(false);boolean written=false;
        try{
            String current=readConfig();if(!current.equals(loadedFile))throw new IllegalStateException("本机配置已在其他操作中更新，请返回后重新进入设置，避免覆盖新的参数");
            JSONObject config=new JSONObject(current);
            for(Parameter p:parameters)config.put(p.key,values.get(p.key));
            JSONArray old=config.optJSONArray("_terminalChanges"),history=new JSONArray();
            history.put(new JSONObject().put("time",System.currentTimeMillis()).put("changes",summary));
            if(old!=null)for(int i=0;i<Math.min(19,old.length());i++)history.put(old.get(i));
            config.put("_terminalChanges",history);config.put("_terminalRevision",config.optLong("_terminalRevision",0)+1);
            String output=config.toString();writeConfig(output);written=true;loadedFile=output;
            for(Parameter p:parameters){p.loaded=p.format(values.getDouble(p.key));p.input.setText(p.loaded);}
            if(!Boolean.TRUE.equals(ConfigUtils.initConfig(getApplicationContext())))throw new IllegalStateException("参数文件已写入，但算法模块重读失败");
            status.setText("本机参数已保存，并记录修改时间与前后值。返回首页后点击开始核验。");status.setTextColor(0xff167a50);
        }catch(Exception error){
            status.setText((written?"参数已写入，暂不可核验：":"参数未保存：")+safe(error)+(written?"。请返回设备诊断查看算法状态，不要重复提交。":"。原文件和输入框已保留。"));status.setTextColor(0xffb54724);
        }finally{save.setEnabled(initialized);}
    }
    private String readConfig() throws Exception {
        if(ConfigUtils.filePath==null)throw new IllegalStateException("本机配置路径尚未初始化");
        try(FileInputStream in=new FileInputStream(ConfigUtils.filePath);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count,total=0;while((count=in.read(buffer))!=-1){total+=count;if(total>128*1024)throw new IllegalStateException("本机配置文件超过128KB，请联系技术支持检查内容");out.write(buffer,0,count);}return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private void writeConfig(String json) throws Exception {
        File target=new File(ConfigUtils.filePath),temporary=new File(target.getParentFile(),target.getName()+".terminal.tmp");
        try{
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(json.getBytes(StandardCharsets.UTF_8));out.flush();out.getFD().sync();}
            if(!temporary.renameTo(target))throw new IllegalStateException("无法替换本机配置文件，请检查应用存储剩余空间");
        }finally{if(temporary.exists())temporary.delete();}
    }
    private void showHistory(){
        try{
            JSONArray history=new JSONObject(readConfig()).optJSONArray("_terminalChanges");StringBuilder content=new StringBuilder();
            SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA);format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            if(history!=null)for(int i=0;i<history.length();i++){JSONObject item=history.getJSONObject(i);content.append(format.format(new Date(item.optLong("time")))).append("\n").append(item.optString("changes")).append("\n");}
            new AlertDialog.Builder(this).setTitle("本机最近20次参数修改").setMessage(content.length()==0?"当前没有通过本机设置页保存的修改记录。":content.toString()).setPositiveButton("关闭",null).show();
        }catch(Exception error){new AlertDialog.Builder(this).setTitle("修改记录读取失败").setMessage(safe(error)).setPositiveButton("关闭",null).show();}
    }
    private String authText(){
        try{BDFaceLicenseAuthInfo auth=new FaceAuth().getAuthInfo(this);if(auth==null)return "本机算法尚未授权，请返回登录页恢复授权或首次激活。";if(auth.expireTime<=0)return "已读取本机算法授权，授权服务未返回到期日期。";SimpleDateFormat format=new SimpleDateFormat("yyyy年MM月dd日",Locale.CHINA);format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return "授权有效期至："+format.format(new Date(auth.expireTime*1000L));}catch(Exception error){return "授权有效期暂时无法读取，请返回设备诊断查看授权状态。";}
    }
    @Override public void onBackPressed(){
        boolean changed=false;for(Parameter p:parameters)if(!p.loaded.equals(p.input.getText().toString().trim())){changed=true;break;}
        if(!changed){super.onBackPressed();return;}
        new AlertDialog.Builder(this).setTitle("有尚未保存的参数").setMessage("返回将放弃本页尚未保存的输入，已保存的本机参数仍保留。").setNegativeButton("继续编辑",null).setPositiveButton("放弃并返回",(d,w)->finish()).show();
    }
    private String safe(Exception error){String message=error.getMessage();return message==null||message.trim().isEmpty()?"未能完成该操作，请检查本机配置和存储空间":message;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int border){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(14));if(border!=0)shape.setStroke(dp(1),border);return shape;}
    private TextView text(String value,int size,int color){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setIncludeFontPadding(false);text.setLineSpacing(dp(4),1);return text;}
    private void section(LinearLayout parent,String title){TextView heading=text(title,18,INK);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,dp(12),0,dp(12));parent.addView(heading);}
    private LinearLayout card(LinearLayout parent){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(16),dp(16),dp(16));box.setBackground(shape(Color.WHITE,0xffe8edf3));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(12);parent.addView(box,params);return box;}
    private Button button(LinearLayout parent,String title,Runnable action,boolean primary){Button button=new Button(this);button.setText(title);button.setTextSize(16);button.setTextColor(primary?Color.WHITE:BLUE);button.setAllCaps(false);button.setMinHeight(dp(52));button.setPadding(dp(12),dp(12),dp(12),dp(12));button.setBackground(shape(primary?BLUE:Color.WHITE,primary?0:0xffc6dcff));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(12);parent.addView(button,params);button.setOnClickListener(v->action.run());return button;}
}
