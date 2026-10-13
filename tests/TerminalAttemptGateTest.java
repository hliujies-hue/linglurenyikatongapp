import com.lingluren.yikatong.terminal.TerminalAttemptGate;
public final class TerminalAttemptGateTest {
 static void check(boolean value){if(!value)throw new AssertionError("Attempt gate violated");}
 public static void main(String[] args){TerminalAttemptGate g=new TerminalAttemptGate();check(g.face(0));for(int t=1;t<1000;t++)check(!g.face(t));g.absent(1000);g.absent(2499);check(!g.face(2500));g.absent(3000);g.absent(4500);check(g.face(4501));check(!g.face(4502));g.reset();check(g.face(4503));System.out.println("PASS: continuous face, brief occlusion, leave/reenter and camera restart");}
}
