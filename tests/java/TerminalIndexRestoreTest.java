package com.lingluren.yikatong.terminal;
import java.util.*;
public final class TerminalIndexRestoreTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static final class Source implements TerminalIndexRestore.Source {
        int[] ids={2,5,9,14,20};int opens,closes;boolean corrupt;
        public TerminalIndexRestore.Rows after(int id,int limit){
            opens++;return new TerminalIndexRestore.Rows(){int i=-1,n;
                public boolean next(){while(++i<ids.length){if(ids[i]>id)return ++n<=limit;}return false;}
                public int id(){return ids[i];}
                public byte[] feature(){return corrupt?new byte[1]:new byte[4];}
                public void close(){closes++;}
            };
        }
    }
    static void fails(Runnable r){try{r.run();throw new AssertionError("expected failure");}catch(IllegalStateException expected){}}
    public static void main(String[] args){
        Source s=new Source();TerminalIndexRestore r=new TerminalIndexRestore(5,4);List<Integer> seen=new ArrayList<>();
        TerminalIndexRestore.Writer writer=(id,f)->{seen.add(id);return 0;};
        r.step(s,writer,()->0,2,40);check(r.loaded()==2&&!r.done()&&s.closes==1);
        r.step(s,writer,()->0,2,40);check(r.loaded()==4&&!r.done());
        r.step(s,writer,()->0,2,40);check(r.done()&&seen.equals(Arrays.asList(2,5,9,14,20))&&s.opens==s.closes);
        r.step(s,writer,()->0,2,40);check(s.opens==3);
        TerminalIndexRestore timed=new TerminalIndexRestore(5,4);long[] time={0};
        timed.step(s,writer,()->{long value=time[0];time[0]+=40;return value;},128,40);check(timed.loaded()==1);
        TerminalIndexRestore retry=new TerminalIndexRestore(5,4);
        fails(()->retry.step(s,(id,f)->-1003,()->0,2,40));check(retry.loaded()==0&&!retry.done()&&s.opens==s.closes);
        retry.step(s,writer,()->0,2,40);check(retry.loaded()==2);
        s.corrupt=true;fails(()->retry.step(s,writer,()->0,2,40));check(retry.loaded()==2);s.corrupt=false;
        TerminalIndexRestore missing=new TerminalIndexRestore(6,4);fails(()->missing.step(s,writer,()->0,128,40));check(!missing.done());
        TerminalIndexRestore empty=new TerminalIndexRestore(0,4);int before=s.opens;empty.step(s,writer,()->0,128,40);check(empty.done()&&s.opens==before);
        System.out.println("PASS: bounded keyset batches, time budget, native failure retry, corrupt/missing rows, cursor close and empty library");
    }
}
