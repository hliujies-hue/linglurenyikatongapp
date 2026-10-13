package com.lingluren.yikatong.terminal;

import java.io.IOException;

/** Release may commit before its response arrives. Probe first, then keep syncing on conflicts. */
public final class TerminalDrainCoordinator {
    private TerminalDrainCoordinator() {}
    public static class ResponseException extends IOException {
        public final int status;
        public ResponseException(int status,String message){super(message);this.status=status;}
    }
    public interface Work {
        void release() throws Exception;
        void sync() throws Exception;
    }
    private static boolean release(Work work) throws Exception {
        try { work.release();return true; }
        catch(ResponseException e){if(e.status!=400&&e.status!=409)throw e;return false;}
    }
    public static boolean run(Work work) throws Exception {
        if(release(work))return true;
        work.sync();
        return release(work);
    }
}
