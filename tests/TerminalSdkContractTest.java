import com.lingluren.yikatong.terminal.TerminalSdkContract;
public final class TerminalSdkContractTest {
    private static void check(boolean result){if(!result)throw new AssertionError();}
    public static void main(String[] args){
        check(TerminalSdkContract.FEATURE_BYTES==512);
        check(TerminalSdkContract.featureSucceeded(128));
        for(float invalid:new float[]{512,-1,0,127,Float.NaN,Float.POSITIVE_INFINITY})check(!TerminalSdkContract.featureSucceeded(invalid));
        check(TerminalSdkContract.canStart(true,true,false,0,100,200));
        check(!TerminalSdkContract.canStart(true,false,false,0,100,200));
        check(!TerminalSdkContract.canStart(false,true,false,0,100,200));
        check(!TerminalSdkContract.canStart(true,true,true,0,100,200));
        check(!TerminalSdkContract.canStart(true,true,false,1,100,200));
        check(!TerminalSdkContract.canStart(true,true,false,0,100,30100));
        check(!TerminalSdkContract.canStart(true,true,false,0,0,200));
        check(!TerminalSdkContract.canStart(true,true,false,0,200,100));
        System.out.println("PASS: SDK feature units and sync readiness gate");
    }
}
