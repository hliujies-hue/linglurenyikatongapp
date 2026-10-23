package com.lingluren.yikatong.terminal;
/** Keep configurable feedback, but never confuse biometric failure with policy rejection. */
public final class TerminalDecisionText {
    public static String describe(boolean matched,String reason,String configured){
        String custom=configured==null?"":normalize(configured.trim());
        boolean usable=!custom.isEmpty()&&!"异常003".equals(custom)&&!"未识别到有效用户".equals(custom)&&!"无权限".equals(custom);
        if("LIVENESS_FAILED".equals(reason))return append("活体检测未通过，请本人正对摄像头",usable?custom:"");
        if("NO_MATCH".equals(reason))return append(matched?"本地已匹配，平台人员授权尚未确认，请同步后重试":"本机未匹配到已录入人员，请调整姿态后重新核验",usable?custom:"");
        if(usable)return custom;
        if(!matched)return configured;
        String detail;
        switch(reason){
            case "EXPIRED":detail="年票已过期";break;
            case "NOT_EFFECTIVE":case "NOT_YET_EFFECTIVE":detail="年票尚未生效";break;
            case "NO_ENTITLEMENT":detail="无有效年票权益";break;
            case "SCENIC_NOT_AUTHORIZED":detail="无当前景区权限";break;
            case "BLACKLISTED":case "USER_BLACKLIST":case "USER_DISABLED":detail="人员已被限制入园";break;
            case "DAILY_LIMIT":case "MONTHLY_LIMIT":case "YEARLY_LIMIT":case "TOTAL_LIMIT":case "PERSONNEL_TOTAL_LIMIT":detail="已达入园次数限制";break;
            case "ENTRY_INTERVAL":detail=configured;break;
            case "SINGLE_POINT_DAILY":case "SINGLE_POINT_OTHER_GATE":case "TERMINAL_SINGLE_POINT":detail="该游客今日已在本景区其他账号或设备下完成单点核验，本次不予放行";break;
            case "SINGLE_POINT_NORMAL_ONLY":case "TERMINAL_NORMAL_ONLY":detail="该游客今日已使用普通核验账号入园，本次不能使用单点账号核验";break;
            default:detail=configured;
        }
        return detail;
    }
    private static String append(String detail,String custom){return custom.isEmpty()||detail.contains(custom)?detail:custom+"；"+detail;}
    public static String normalize(String text){
        if("今日已使用单点账号核验，不能更换账号或设备".equals(text))return "该游客今日已在本景区其他账号或设备下完成单点核验，本次不予放行";
        if("今日已使用普通账号核验，不能改用单点账号".equals(text))return "该游客今日已使用普通核验账号入园，本次不能使用单点账号核验";
        return text;
    }
    private TerminalDecisionText(){}
}
