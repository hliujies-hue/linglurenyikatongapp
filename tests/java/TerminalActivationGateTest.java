package com.lingluren.yikatong.terminal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
public final class TerminalActivationGateTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        check(TerminalActivationGate.normalizeCode("abcd ef12 gh34 jk56").equals("ABCD-EF12-GH34-JK56"),"paste normalization");
        check(TerminalActivationGate.normalizeCode("abcd－ef12－gh34－jk56").equals("ABCD-EF12-GH34-JK56"),"fullwidth hyphen");
        for(String bad:new String[]{"", "abc", "ABCD-EF12-GH34-JK5!","ABCD-EF12-GH34-JK56-x"}){
            try{TerminalActivationGate.normalizeCode(bad);throw new AssertionError("invalid accepted");}catch(IllegalArgumentException expected){}
        }
        TerminalActivationGate gate=new TerminalActivationGate();long first=gate.start();check(first>0,"first request");check(gate.start()==0,"double tap denied");
        check(!gate.finish(first,0),"cannot authorize before vendor call");
        for(int i=1;i<TerminalActivationGate.MAX_FINGERPRINT_ATTEMPTS;i++)check(gate.fingerprint(first,false)==TerminalActivationGate.RETRY,"warmup retry");
        check(gate.fingerprint(first,false)==TerminalActivationGate.FAILED&&gate.state().error==1007,"bounded empty fingerprint");
        long second=gate.start();check(second>first,"retry generation");check(!gate.finish(first,0),"stale success rejected");
        check(gate.fingerprint(second,true)==TerminalActivationGate.READY,"real fingerprint readiness");
        check(gate.slow(second)&&gate.state().busy()&&gate.start()==0,"timeout must not overlap native activation");
        check(gate.finish(second,0)&&gate.state().authorized(),"late success accepted for same in-flight request");
        check(!gate.finish(second,1007)&&gate.start()==0,"duplicate callback cannot revoke success or rebind");
        TerminalActivationGate denied=new TerminalActivationGate();long id=denied.start();denied.fingerprint(id,true);denied.finish(id,290004);
        check(!denied.state().authorized()&&denied.start()>id,"bound license rejected and new code retry allowed");
        check(TerminalActivationGate.describe(1007).contains("尚未校验授权码"),"1007 must not blame license");
        check(TerminalActivationGate.describe(290006).contains("时间异常"),"update time must not be labelled expired");
        check(!TerminalActivationGate.describe(1007).contains("未绑定"),"fingerprint is distinct from license binding");
        TerminalActivationGate concurrent=new TerminalActivationGate();CountDownLatch ready=new CountDownLatch(1);AtomicInteger starts=new AtomicInteger();Thread[] threads=new Thread[12];
        for(int i=0;i<threads.length;i++){threads[i]=new Thread(()->{try{ready.await();if(concurrent.start()!=0)starts.incrementAndGet();}catch(InterruptedException e){throw new RuntimeException(e);}});threads[i].start();}
        ready.countDown();for(Thread t:threads)t.join();check(starts.get()==1,"single process owner under concurrency");
        System.out.println("PASS: empty fingerprint retry, single activation, stale/late callbacks, code validation and error mapping");
    }
}
