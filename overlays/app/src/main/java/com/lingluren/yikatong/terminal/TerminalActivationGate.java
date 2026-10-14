package com.lingluren.yikatong.terminal;

/** Process-wide single-flight rules; never substitutes a device ID or SDK result. */
public final class TerminalActivationGate {
    public static final int MAX_FINGERPRINT_ATTEMPTS=8;
    public static final int RETRY=0,READY=1,FAILED=2,STALE=3;
    private long generation;
    private String phase="IDLE";
    private int attempts,error;
    public static final class State {
        public final long generation;public final String phase;public final int attempts,error;
        private State(long g,String p,int a,int e){generation=g;phase=p;attempts=a;error=e;}
        public boolean busy(){return phase.equals("PREPARING")||phase.equals("VERIFYING")||phase.equals("SLOW");}
        public boolean authorized(){return phase.equals("AUTHORIZED");}
    }
    public synchronized State state(){return new State(generation,phase,attempts,error);}
    public synchronized long start(){if(state().busy()||state().authorized())return 0;generation++;phase="PREPARING";attempts=0;error=0;return generation;}
    public synchronized int fingerprint(long request,boolean available){
        if(request!=generation||!phase.equals("PREPARING"))return STALE;
        attempts++;if(available){phase="VERIFYING";return READY;}
        if(attempts>=MAX_FINGERPRINT_ATTEMPTS){phase="FAILED";error=1007;return FAILED;}return RETRY;
    }
    public synchronized boolean finish(long request,int code){
        if(request!=generation||!state().busy())return false;
        // Success is only accepted after the real SDK verification was started.
        if(code==0&&!phase.equals("VERIFYING")&&!phase.equals("SLOW"))return false;
        error=code;phase=code==0?"AUTHORIZED":"FAILED";return true;
    }
    public synchronized boolean slow(long request){if(request!=generation||!phase.equals("VERIFYING"))return false;phase="SLOW";return true;}
    public static String normalizeCode(String raw){
        String value=raw==null?"":raw.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s\\u00a0\\u3000]","").replace('－','-').replace('—','-');
        if(value.matches("[A-Z0-9]{16}"))return value.substring(0,4)+"-"+value.substring(4,8)+"-"+value.substring(8,12)+"-"+value.substring(12);
        if(!value.matches("[A-Z0-9]{4}(-[A-Z0-9]{4}){3}"))throw new IllegalArgumentException("授权码应为4组、每组4位字母或数字，请核对输入");return value;
    }
    public static String phaseText(String phase){
        switch(phase){case "PREPARING":return "获取设备指纹";case "VERIFYING":return "校验百度授权";case "SLOW":return "授权回调等待中";case "FAILED":return "激活失败";case "AUTHORIZED":return "已授权";default:return "尚未开始";}
    }
    public static String describe(int code){
        switch(code){
            case 1007:return "百度SDK未取得本机设备指纹（1007），尚未校验授权码。请保持联网后重试；仍失败请导出激活诊断。无需清除数据。";
            case 1006:return "授权组件初始化上下文异常（1006），请重启应用后重试";
            case 1009:case 1011:return "百度授权服务连接失败（"+code+"），平台在线不代表百度授权服务可达，请切换Wi-Fi或热点重试";
            case 1010:return "百度授权服务返回数据异常（1010），请稍后重试";
            case 290004:case 290009:return "此授权码已绑定其他设备（"+code+"），请为本机使用未绑定授权码或由百度处理授权迁移";
            case 290002:return "百度授权码无效（290002），请核对授权产品及序列号";
            case 290003:return "百度拒绝此设备指纹（290003），请导出诊断交由授权服务方核查";
            case 290008:return "授权激活次数已达上限（290008），请联系授权服务方";
            case 11:return "百度SDK授权已到期（11），请联系授权服务方续期";
            case 290006:return "百度授权更新时间异常（290006），请检查系统日期时间并联系授权服务方";
            case 7:return "本机指纹与已有License不匹配（7），请联系授权服务方核查；不要复制其他设备授权文件";
            case 8:return "License应用包名不匹配（8），请核对授权配置和正式安装包";
            case 9:case 14:return "授权时间校验失败（"+code+"），请检查系统日期、时间及时区";
            case 12:return "本机License文件读取失败（12），请检查剩余空间并联系技术人员；不要清除应用数据";
            case 13:return "百度授权数据校验失败（13），请检查网络并联系授权服务方";
            case -2001:return "授权组件无法加载，请核对设备CPU架构与SDK完整性（-2001）";
            case -2002:return "授权组件初始化异常，请重启应用并导出诊断（-2002）";
            default:return "百度SDK激活失败（"+code+"），请导出激活诊断交由技术人员核查";
        }
    }
}
