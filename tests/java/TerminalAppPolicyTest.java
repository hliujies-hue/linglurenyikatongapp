import com.lingluren.yikatong.terminal.TerminalAppPolicy;
public final class TerminalAppPolicyTest {
 public static void main(String[] args){
  if(!TerminalAppPolicy.older("0.7.9-security","0.7.10-config"))throw new AssertionError("numeric version");
  if(TerminalAppPolicy.older("1.0.0","0.9999.9999"))throw new AssertionError("major");
  if(TerminalAppPolicy.older("0.7.10-config","0.7.10-other"))throw new AssertionError("suffix");
  if(!TerminalAppPolicy.block(true,"设备维护","","0.7.10").contains("设备维护"))throw new AssertionError("reason");
  if(!TerminalAppPolicy.block(false,"","0.7.11","0.7.10").contains("请更新"))throw new AssertionError("minimum");
  if(!TerminalAppPolicy.block(false,"","0.7.10","0.7.10").isEmpty())throw new AssertionError("allowed");
  for(String value:new String[]{"0.07.10","0.7","0.7.10-","0.7.10000","+0.7.10"}){
   boolean failed=false;try{TerminalAppPolicy.older(value,"1.0.0");}catch(Exception e){failed=true;}if(!failed)throw new AssertionError(value);
  }
  System.out.println("TerminalAppPolicyTest passed");
 }
}
