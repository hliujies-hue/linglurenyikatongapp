"""Execute the production back handler with page/session doubles (no Android runtime)."""
from pathlib import Path
import subprocess, tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'overlays/app/src/main/java/com/lingluren/yikatong/terminal/TerminalEntryActivity.java').read_text()
method=s[s.index('    @Override public void onBackPressed()'):s.index('    @Override protected void onResume()')]
source='''class BackBase {public void onBackPressed(){throw new AssertionError("unexpected app exit");}}
public class DiagnosticBackTest extends BackBase {
 boolean diagnosticsPage=true,scanPage,resultPage,syncPage,maintenancePage;
 Runnable diagnosticsReturn;String destination="";Runtime runtime=new Runtime();
 static class Runtime {boolean session;boolean hasSession(){return session;}}
 void showLogin(){destination="login";}void showAccount(){destination="home";}void showMaintenance(){destination="maintenance";}
 __METHOD__
 public static void main(String[] args){
  DiagnosticBackTest t=new DiagnosticBackTest();
  t.diagnosticsReturn=()->{throw new AssertionError("expired session restored");};
  t.onBackPressed();if(!t.destination.equals("login"))throw new AssertionError();
  t.runtime.session=true;
  for(String target:new String[]{"home","sync","maintenance"}){
   t.diagnosticsReturn=()->t.destination=target;t.onBackPressed();if(!t.destination.equals(target))throw new AssertionError(target);
  }
  t.diagnosticsReturn=null;t.onBackPressed();if(!t.destination.equals("home"))throw new AssertionError();
  System.out.println("PASS diagnostic back: logged out, expired session, home/sync/maintenance return, fallback");
 }
}'''.replace('__METHOD__',method)
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'DiagnosticBackTest.java';p.write_text(source)
 subprocess.run(['javac','-d',d,str(p)],check=True)
 subprocess.run(['java','-cp',d,'DiagnosticBackTest'],check=True)
