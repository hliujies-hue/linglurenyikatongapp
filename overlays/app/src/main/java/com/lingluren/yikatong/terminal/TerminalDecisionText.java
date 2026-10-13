package com.lingluren.yikatong.terminal;
/** Keep configurable feedback, but never confuse biometric failure with policy rejection. */
public final class TerminalDecisionText {
    public static String describe(boolean matched,String reason,String configured){
        if("LIVENESS_FAILED".equals(reason))return "活体检测未通过，请本人正对摄像头";
        if("NO_MATCH".equals(reason))return matched?"本地已匹配，平台人员授权尚未确认，请同步后重试":"本机未匹配到已录入人员，请调整姿态后重新核验";
        if(!matched)return configured;
        String detail;
        switch(reason){
            case "EXPIRED":detail="年票已过期";break;
            case "NOT_EFFECTIVE":case "NOT_YET_EFFECTIVE":detail="年票尚未生效";break;
            case "NO_ENTITLEMENT":detail="无有效年票权益";break;
            case "SCENIC_NOT_AUTHORIZED":detail="无当前景区权限";break;
            case "BLACKLISTED":case "USER_BLACKLIST":case "USER_DISABLED":detail="人员已被限制入园";break;
            case "DAILY_LIMIT":case "MONTHLY_LIMIT":case "YEARLY_LIMIT":case "TOTAL_LIMIT":case "PERSONNEL_TOTAL_LIMIT":detail="已达入园次数限制";break;
            case "ENTRY_INTERVAL":detail="未达到再次入园间隔";break;
            case "TERMINAL_SINGLE_POINT":detail="今日需使用原单点账号或设备";break;
            case "TERMINAL_NORMAL_ONLY":detail="今日需使用普通核验账号";break;
            default:detail=configured;
        }
        return "本地已匹配；平台未放行："+detail;
    }
    private TerminalDecisionText(){}
}
