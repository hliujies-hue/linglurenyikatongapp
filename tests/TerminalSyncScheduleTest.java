import com.lingluren.yikatong.terminal.TerminalSyncSchedule;
public final class TerminalSyncScheduleTest {
 private static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  for(int j=0;j<5000;j+=113){
   long idle=TerminalSyncSchedule.delay(false,0,j),busy=TerminalSyncSchedule.delay(true,0,j);
   check(idle>=15000&&idle<20000);check(busy>=3000&&busy<4000);
   check(idle<30000); // readiness lease remains renewable while idle
   long previous=0;
   for(int failure=1;failure<=50;failure++){
    long wait=TerminalSyncSchedule.delay(true,failure,j);check(wait>=previous&&wait<65000);previous=wait;
   }
   check(TerminalSyncSchedule.delay(true,0,j)<TerminalSyncSchedule.delay(true,8,j));
  }
  check(TerminalSyncSchedule.delay(false,0,0)!=TerminalSyncSchedule.delay(false,0,4999));
  System.out.println("PASS: idle cadence, backlog cadence, exponential backoff, cap and jitter");
 }
}
