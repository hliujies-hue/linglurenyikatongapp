package com.lingluren.yikatong.terminal;
/** Stable category independent of configurable wording; retries never become a new pass. */
public final class TerminalResultType {
 public static String of(String result,String reason,boolean duplicate){
  if(duplicate)return "duplicate";if("PASS".equals(result))return "pass";
  switch(reason==null?"":reason){case "NOT_EFFECTIVE":case "NOT_YET_EFFECTIVE":return "notEffective";
   case "ENTRY_INTERVAL":return "interval";case "NO_MATCH":return "noMatch";
   case "SINGLE_POINT_DAILY":case "SINGLE_POINT_NORMAL_ONLY":case "SINGLE_POINT_OTHER_GATE":case "TERMINAL_SINGLE_POINT":case "TERMINAL_NORMAL_ONLY":return "singlePoint";
   case "DAILY_LIMIT":case "MONTHLY_LIMIT":case "YEARLY_LIMIT":case "TOTAL_LIMIT":case "PERSONNEL_TOTAL_LIMIT":return "quota";
   case "BLACKLISTED":case "USER_BLACKLIST":case "USER_DISABLED":case "CARD_DISABLED":case "NO_ENTITLEMENT":case "SCENIC_NOT_AUTHORIZED":case "CARD_TYPE_DISABLED":case "REFUND_HOLD":return "restricted";
   case "LIVENESS_FAILED":return "liveness";case "EXPIRED":return "expired";
   case "NETWORK_UNAVAILABLE":case "PLATFORM_TIMEOUT":return "network";case "SDK_NOT_READY":return "sdk";default:return "denied";}
 }
 public static String title(String type){switch(type){case "pass":return "核验通过";case "notEffective":return "年票未生效";case "interval":return "核验间隔较短";case "noMatch":return "异常未匹配到";case "singlePoint":return "单点入园限制";case "quota":return "入园次数限制";case "restricted":return "无入园权限";case "liveness":return "活体未通过";case "expired":return "年票已过期";case "network":return "网络异常";case "sdk":return "设备未就绪";case "duplicate":return "记录已处理";default:return "本次未放行";}}
 private TerminalResultType(){}
}
