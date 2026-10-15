package com.lingluren.yikatong.terminal;

/** Same numeric version semantics as the platform. No lexicographic comparison. */
public final class TerminalAppPolicy {
    private TerminalAppPolicy(){}
    static int[] version(String value){
        if(value==null||value.length()>64||!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9._-]+)?"))throw new IllegalArgumentException("版本号格式错误");
        String[] parts=value.split("-",2)[0].split("\\.");int[] out=new int[3];
        for(int i=0;i<3;i++){out[i]=Integer.parseInt(parts[i]);if(out[i]>9999||!String.valueOf(out[i]).equals(parts[i]))throw new IllegalArgumentException("版本号格式错误");}return out;
    }
    public static boolean older(String current,String target){int[] a=version(current),b=version(target);for(int i=0;i<3;i++)if(a[i]!=b[i])return a[i]<b[i];return false;}
    public static String block(boolean disabled,String reason,String minimum,String current){
        if(disabled)return "APP已被平台暂停核验："+reason;
        if(minimum!=null&&!minimum.isEmpty())try{if(older(current,minimum))return "当前APP版本 "+current+" 低于最低允许版本 "+minimum+"，请更新后核验";}catch(Exception e){return "平台最低版本配置异常，请联系管理员";}return "";
    }
}
