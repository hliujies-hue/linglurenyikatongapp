package com.lingluren.yikatong.terminal;
/** One event per continuous presentation; no face for 1.5s rearms the camera. */
public final class TerminalAttemptGate {
    private boolean consumed;
    private long absentSince=-1;
    public boolean face(long now){absentSince=-1;if(consumed)return false;consumed=true;return true;}
    public void absent(long now){if(absentSince<0)absentSince=now;if(now-absentSince>=1500)consumed=false;}
    public void reset(){consumed=false;absentSince=-1;}
}
