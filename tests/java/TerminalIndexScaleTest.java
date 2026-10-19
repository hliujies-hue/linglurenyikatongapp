package com.lingluren.yikatong.terminal;
import java.util.*;
import java.lang.management.*;
/** Real production ledger with synthetic 512-byte features and a fake native index.
 * This does not execute Android, SQLite, photos, liveness or Baidu 1:N search. */
public final class TerminalIndexScaleTest {
 static final class Index implements TerminalIndexLedger.NativeIndex {
  final Map<Integer,byte[]> rows=new HashMap<>();int pushes,deletes;boolean failPush,failDelete;
  public int push(int id,byte[] feature){if(failPush||rows.containsKey(id))return -1;rows.put(id,feature.clone());pushes++;return 0;}
  public int delete(int id){if(failDelete)return -1;rows.remove(id);deletes++;return 0;}
 }
 static long assertions;
 static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("index scale invariant failed at assertion "+assertions);}
 static byte[] feature(int id,int version){byte[] b=new byte[TerminalSdkContract.FEATURE_BYTES];for(int i=0;i<4;i++)b[i]=(byte)(id>>>(8*i));b[4]=(byte)version;return b;}
 static double ms(long start){return (System.nanoTime()-start)/1e6;}
 public static void main(String[] args){
  int count=args.length==0?200000:Integer.parseInt(args[0]);if(count<10||count>200000)throw new IllegalArgumentException("count 10..200000");
  TerminalIndexLedger ledger=new TerminalIndexLedger();Index index=new Index();long started=System.nanoTime(),phase=started;
  List<Double> batches=new ArrayList<>();long batch=phase;
  for(int i=1;i<=count;i++){check(ledger.restore(index,i,feature(i,0))==0);if(i%128==0){batches.add(ms(batch));batch=System.nanoTime();}}
  double load=ms(phase);phase=System.nanoTime();
  for(int i=1;i<=count;i++){check(ledger.restore(index,i,feature(i,0))==0);check(Arrays.equals(index.rows.get(i),feature(i,0)));}
  check(index.pushes==count&&index.rows.size()==count);double duplicate=ms(phase);phase=System.nanoTime();int updated=0,deleted=0;
  for(int i=1;i<=count;i+=10){check(ledger.replace(index,i,feature(i,1))==0);check(Arrays.equals(index.rows.get(i),feature(i,1)));updated++;}
  double update=ms(phase);phase=System.nanoTime();
  for(int i=1;i<=count;i+=5){check(ledger.remove(index,i)==0);check(ledger.remove(index,i)==0);check(!index.rows.containsKey(i));deleted++;}
  check(index.rows.size()==count-deleted);double deletion=ms(phase);
  // Inject failures; a failed mutation must not falsely advance ledger membership.
  index.failPush=true;check(ledger.restore(index,count+1,feature(count+1,0))!=0);check(!index.rows.containsKey(count+1));
  index.failPush=false;check(ledger.restore(index,count+1,feature(count+1,0))==0);
  index.failDelete=true;check(ledger.remove(index,count+1)!=0);check(index.rows.containsKey(count+1));
  index.failDelete=false;check(ledger.remove(index,count+1)==0);check(!index.rows.containsKey(count+1));
  check(index.rows.size()==count-deleted);
  long used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),peak=0;
  for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)peak+=pool.getPeakUsage().getUsed();
  Collections.sort(batches);double p95=batches.isEmpty()?0:batches.get(Math.min(batches.size()-1,(int)Math.ceil(batches.size()*0.95)-1));
  System.out.printf(Locale.US,"{\"scope\":\"production-ledger-fake-native-index\",\"records\":%d,\"featureBytes\":%d,\"duplicateRestores\":%d,\"updated\":%d,\"deleted\":%d,\"loadMs\":%.3f,\"duplicateMs\":%.3f,\"updateMs\":%.3f,\"deleteMs\":%.3f,\"batch128P95Ms\":%.3f,\"totalMs\":%.3f,\"heapUsedBytesAtEnd\":%d,\"sumHeapPoolPeaksBytes\":%d,\"assertions\":%d,\"unexpectedFailures\":0,\"expectedInjectedFailures\":2,\"remaining\":%d,\"passed\":true,\"nativeSDKTested\":false,\"phoneTested\":false,\"databaseTested\":false}%n",count,TerminalSdkContract.FEATURE_BYTES,count,updated,deleted,load,duplicate,update,deletion,p95,ms(started),used,peak,assertions,index.rows.size());
 }
}
