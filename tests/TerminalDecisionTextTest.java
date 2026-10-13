import com.lingluren.yikatong.terminal.TerminalDecisionText;
public final class TerminalDecisionTextTest {
 public static void main(String[] args){
  if(!TerminalDecisionText.describe(false,"NO_MATCH","无权限").contains("本机未匹配"))throw new AssertionError();
  if(!TerminalDecisionText.describe(true,"NO_MATCH","无权限").contains("授权尚未确认"))throw new AssertionError();
  if(!TerminalDecisionText.describe(true,"SCENIC_NOT_AUTHORIZED","未识别到有效用户").contains("无当前景区权限"))throw new AssertionError();
  if(!TerminalDecisionText.describe(true,"EXPIRED","未识别到有效用户").contains("已过期"))throw new AssertionError();
  if(!TerminalDecisionText.describe(false,"LIVENESS_FAILED","无权限").contains("活体"))throw new AssertionError();
  System.out.println("PASS: biometric failures and platform restrictions remain distinct");
 }
}
