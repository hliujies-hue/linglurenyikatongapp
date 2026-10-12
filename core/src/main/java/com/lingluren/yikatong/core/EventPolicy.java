package com.lingluren.yikatong.core;

/** Pure domain rules shared by Android and command-line regression checks. */
public final class EventPolicy {
 public static final long SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000;
 private EventPolicy() {}
 public static boolean mayDelete(long now, long created, boolean platformAck) {
  return platformAck && now >= created && now - created >= SEVEN_DAYS;
 }
 public static boolean mayPresentPass(String currentEvent, String responseEvent, long elapsed,
                                      boolean foreground, boolean serverAllow) {
  return serverAllow && foreground && currentEvent != null && currentEvent.equals(responseEvent)
      && elapsed >= 0 && elapsed <= 8000;
 }
 // Android must never apply shared scenic admission quotas locally.
 public static String defaultText(String status) {
  return switch (status) {
   case "PASS" -> "正常通行";
   case "LIMIT_EXCEEDED" -> "异常003";
   case "REPEAT_60S" -> "请勿重复核验";
   case "SINGLE_POINT_CONFLICT" -> "今日已核验，不可重复通行";
   case "NETWORK_ERROR" -> "网络异常，暂不可核验";
   case "NOT_READY" -> "数据准备中，请稍候";
   case "QUALITY" -> "请正对镜头";
   case "MULTIPLE_FACES" -> "请逐人核验";
   case "LIVE_FAILED" -> "核验未通过，请重试";
   case "LOGIN_REQUIRED" -> "请重新登录";
   case "DEVICE_ERROR" -> "设备异常，请联系工作人员";
   default -> "未识别到有效用户";
  };
 }
}
