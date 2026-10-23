import com.lingluren.yikatong.terminal.TerminalDecisionText;
public final class TerminalDecisionTextTest {
 public static void main(String[] args){
  if(!TerminalDecisionText.describe(false,"NO_MATCH","无权限").contains("本机未匹配"))throw new AssertionError();
  if(!TerminalDecisionText.describe(true,"NO_MATCH","无权限").contains("授权尚未确认"))throw new AssertionError();
  if(!TerminalDecisionText.describe(true,"SCENIC_NOT_AUTHORIZED","未识别到有效用户").contains("无当前景区权限"))throw new AssertionError();
  if(!TerminalDecisionText.describe(true,"EXPIRED","未识别到有效用户").contains("已过期"))throw new AssertionError();
  if(!TerminalDecisionText.describe(false,"LIVENESS_FAILED","无权限").contains("活体"))throw new AssertionError();
  if(!"请至游客中心办理续费".equals(TerminalDecisionText.describe(true,"EXPIRED","请至游客中心办理续费")))throw new AssertionError("configured message discarded");
  if(!"还需等待20秒".equals(TerminalDecisionText.describe(true,"ENTRY_INTERVAL","还需等待20秒")))throw new AssertionError("interval detail discarded");
  if(!TerminalDecisionText.describe(false,"NO_MATCH","请联系现场工作人员录入").contains("请联系现场工作人员录入"))throw new AssertionError("no-match configuration discarded");
  if(!TerminalDecisionText.describe(true,"TERMINAL_SINGLE_POINT","今日已使用单点账号核验，不能更换账号或设备").contains("该游客"))throw new AssertionError("legacy single-point text");
  if(!TerminalDecisionText.normalize("今日已使用普通账号核验，不能改用单点账号").contains("该游客"))throw new AssertionError("legacy ordinary text");
  if(!"自定义提示".equals(TerminalDecisionText.normalize("自定义提示")))throw new AssertionError("custom changed");
  System.out.println("PASS: biometric failures and platform restrictions remain distinct");
 }
}
