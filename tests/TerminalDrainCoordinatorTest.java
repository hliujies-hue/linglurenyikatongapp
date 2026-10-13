import com.lingluren.yikatong.terminal.TerminalDrainCoordinator;

public class TerminalDrainCoordinatorTest {
    static class Work implements TerminalDrainCoordinator.Work {
        String trace="";int pending=2,code=409;boolean networkFailure;
        public void release() throws Exception {trace+="R";if(networkFailure)throw new TerminalDrainCoordinator.ResponseException(503,"unavailable");if(pending>0)throw new TerminalDrainCoordinator.ResponseException(code,"pending");}
        public void sync(){trace+="S";pending--;}
    }
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        for(int code:new int[]{400,409}) {
            Work work=new Work();work.code=code;
            check(!TerminalDrainCoordinator.run(work));check(work.trace.equals("RSR"));check(work.pending==1);
            check(TerminalDrainCoordinator.run(work));check(work.trace.equals("RSRRSR"));check(work.pending==0);
        }
        Work committed=new Work();committed.pending=0;
        check(TerminalDrainCoordinator.run(committed));check(committed.trace.equals("R"));
        Work unavailable=new Work();unavailable.networkFailure=true;
        try{TerminalDrainCoordinator.run(unavailable);throw new AssertionError();}catch(TerminalDrainCoordinator.ResponseException e){check(e.status==503);}
        check(unavailable.trace.equals("R"));check(unavailable.pending==2);
        Work denied=new Work();denied.code=403;
        try{TerminalDrainCoordinator.run(denied);throw new AssertionError();}catch(TerminalDrainCoordinator.ResponseException e){check(e.status==403);}
        check(denied.trace.equals("R"));
        System.out.println("PASS: 409/400 drain progresses, committed release skips SDK, errors preserve pending work");
    }
}
