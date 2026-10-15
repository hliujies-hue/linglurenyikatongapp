package com.lingluren.yikatong.terminal;
import java.util.*;
/** Host-only scale regression. The fake index is NOT the native algorithm. */
public final class TerminalIndexScaleTest {
 static final class Index implements TerminalIndexLedger.NativeIndex {
  final Map<Integer,byte[]> rows=new HashMap<>();int pushes;
  public int push(int id,byte[] feature){if(rows.containsKey(id))return -1;rows.put(id,feature.clone());pushes++;return 0;}
  public int delete(int id){rows.remove(id);return 0;}
 }
 static void check(boolean ok){if(!ok)throw new AssertionError("index scale invariant failed");}
 public static void main(String[] args){
  int count=args.length==0?200000:Integer.parseInt(args[0]);if(count<1||count>200000)throw new IllegalArgumentException("count 1..200000");
  TerminalIndexLedger ledger=new TerminalIndexLedger();Index index=new Index();byte[] feature=new byte[TerminalSdkContract.FEATURE_BYTES];
  long start=System.nanoTime();
  for(int i=1;i<=count;i++)check(ledger.restore(index,i,feature)==0);
  long loaded=System.nanoTime();
  for(int i=1;i<=count;i++)check(ledger.restore(index,i,feature)==0);
  check(index.pushes==count&&index.rows.size()==count);
  for(int i=1;i<=count;i+=10){feature[0]=1;check(ledger.replace(index,i,feature)==0);check(index.rows.get(i)[0]==1);}
  for(int i=1;i<=count;i+=5){check(ledger.remove(index,i)==0);check(ledger.remove(index,i)==0);}
  check(index.rows.size()==count-(count+4)/5);
  long used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
  System.out.printf(Locale.US,"{\"scope\":\"host-ledger-fake-native-index\",\"records\":%d,\"featureBytes\":%d,\"loadMs\":%.3f,\"totalMs\":%.3f,\"heapUsedBytesAtEnd\":%d,\"remaining\":%d,\"passed\":true,\"nativeSDKTested\":false,\"phoneTested\":false}%n",count,feature.length,(loaded-start)/1e6,(System.nanoTime()-start)/1e6,used,index.rows.size());
 }
}
