package com.lingluren.yikatong.terminal;

import java.util.concurrent.*;

/** One native SDK owner. Never run network IO or wait for the runtime actor here. */
final class TerminalAlgorithmQueue {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(4),r->new Thread(r,"terminal-algorithm"),new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean stalled;
    <T> T call(Callable<T> action) throws Exception {
        if(stalled)throw new IllegalStateException("算法执行已超时，已暂停后续调用；请重启应用，原记录保留");
        Future<T> task=worker.submit(action);
        try{return task.get(240,TimeUnit.SECONDS);}
        catch(TimeoutException e){stalled=true;throw new IllegalStateException("算法操作超过240秒未返回，已暂停后续调用；请重启应用，原记录保留",e);}
        catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Error)throw (Error)cause;if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException(cause);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
    }
    boolean offer(Runnable action){if(stalled)return false;try{worker.execute(action);return true;}catch(RejectedExecutionException e){return false;}}
    void close(){worker.shutdown();}
}
