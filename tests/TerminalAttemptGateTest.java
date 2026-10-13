import com.lingluren.yikatong.terminal.TerminalAttemptGate;
public final class TerminalAttemptGateTest {
 static void check(boolean value){if(!value)throw new AssertionError("Attempt gate violated");}
 public static void main(String[] args){TerminalAttemptGate g=new TerminalAttemptGate();check(g.face(0));for(int t=1;t<1000;t++)check(!g.face(t));g.absent(1000);g.absent(2499);check(!g.face(2500));g.absent(3000);g.absent(4500);check(g.face(4501));check(!g.face(4502));g.reset();check(g.face(4503));g.reset();check(!g.decision(0,"NO_MATCH",0));check(g.observing());check(!g.decision(42,"",100));check(g.decision(42,"",300));check(!g.observing());check(!g.decision(42,"",500));
 g.absent(1000);g.absent(2500);check(!g.decision(0,"NO_MATCH",2501));check(!g.decision(0,"NO_MATCH",2801));check(g.decision(0,"NO_MATCH",3101));
 g.reset();check(!g.decision(42,"",0));check(!g.decision(43,"",250));check(g.decision(43,"",450));
 g.reset();check(!g.decision(42,"",0));check(!g.decision(42,"",2000));check(g.decision(42,"",2200));
 System.out.println("PASS: stable identity, transient rejection, quality guidance, stale frame gap and presentation rearm");}
}
