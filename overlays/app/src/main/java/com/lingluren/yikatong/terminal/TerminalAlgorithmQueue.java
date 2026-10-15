package com.lingluren.yikatong.terminal;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One native SDK owner. Never run network IO or wait for the runtime actor here. */
final class TerminalAlgorithmQueue {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(4),r->new Thread(r,"terminal-algorithm"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"terminal-algorithm-watchdog"));
    private final AtomicBoolean stalled=new AtomicBoolean();
    boolean stalled(){return stalled.get();}
    private void requireRunning(){if(stalled.get())throw new IllegalStateException("算法执行已超时，已暂停后续调用；请重启应用，原记录保留");}
    <T> T call(Callable<T> action) throws Exception {
        requireRunning();
        Future<T> task=worker.submit(()->{requireRunning();return action.call();});
        try{return task.get(240,TimeUnit.SECONDS);}
        catch(TimeoutException e){stalled.set(true);throw new IllegalStateException("算法操作超过240秒未返回，已暂停后续调用；请重启应用，原记录保留",e);}
        catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Error)throw (Error)cause;if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException(cause);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
    }
    boolean offer(Runnable action){return offer(action,0,null);}
    /** The watchdog never invokes the SDK, interrupts JNI, or starts a replacement SDK worker. */
    boolean offer(Runnable action,long timeoutMillis,Runnable onTimeout){
        if(stalled.get())return false;
        try{worker.execute(()->{
            if(stalled.get())return;
            final Object guard=new Object();final boolean[] completed={false};
            ScheduledFuture<?> deadline=timeoutMillis<=0?null:watchdog.schedule(()->{
                boolean notify=false;
                synchronized(guard){if(!completed[0])notify=stalled.compareAndSet(false,true);}
                if(notify&&onTimeout!=null)onTimeout.run();
            },timeoutMillis,TimeUnit.MILLISECONDS);
            try{action.run();}
            finally{synchronized(guard){completed[0]=true;}if(deadline!=null)deadline.cancel(false);}
        });return true;}catch(RejectedExecutionException e){return false;}
    }
    void close(){worker.shutdown();watchdog.shutdown();}
}
