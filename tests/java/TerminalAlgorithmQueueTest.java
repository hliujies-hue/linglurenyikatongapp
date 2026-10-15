package com.lingluren.yikatong.terminal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class TerminalAlgorithmQueueTest {
 public static void main(String[] args)throws Exception{
  TerminalAlgorithmQueue q=new TerminalAlgorithmQueue();ExecutorService network=Executors.newSingleThreadExecutor();
  CountDownLatch networkStarted=new CountDownLatch(1),releaseNetwork=new CountDownLatch(1),frameDone=new CountDownLatch(1);
  try{
   network.submit(()->{networkStarted.countDown();try{releaseNetwork.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   if(!networkStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("network fixture");
   if(!q.offer(frameDone::countDown)||!frameDone.await(2,TimeUnit.SECONDS))throw new AssertionError("blocked by network");
   AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();CountDownLatch start=new CountDownLatch(1),finish=new CountDownLatch(1);
   q.offer(()->{max.accumulateAndGet(active.incrementAndGet(),Math::max);start.countDown();try{finish.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}active.decrementAndGet();});
   if(!start.await(2,TimeUnit.SECONDS))throw new AssertionError("start");
   for(int i=0;i<4;i++)if(!q.offer(()->{max.accumulateAndGet(active.incrementAndGet(),Math::max);active.decrementAndGet();}))throw new AssertionError("queue capacity");
   if(q.offer(()->{}))throw new AssertionError("unbounded queue");finish.countDown();
   network.shutdown();releaseNetwork.countDown();
   // Drain without attempting to add to a full queue.
   long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);CountDownLatch drained=new CountDownLatch(1);
   while(!q.offer(drained::countDown)){if(System.nanoTime()>until)throw new AssertionError("did not drain");Thread.yield();}
   if(!drained.await(2,TimeUnit.SECONDS)||max.get()!=1)throw new AssertionError("SDK calls concurrent");
   try{q.call(()->{throw new IllegalStateException("specific");});throw new AssertionError("lost exception");}catch(IllegalStateException e){if(!"specific".equals(e.getMessage()))throw e;}
   System.out.println("PASS: network independence, serialized SDK, bounded queue and error propagation");
  }finally{releaseNetwork.countDown();network.shutdownNow();q.close();}
 }
}
