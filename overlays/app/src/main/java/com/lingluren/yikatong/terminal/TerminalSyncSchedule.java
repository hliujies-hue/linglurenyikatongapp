package com.lingluren.yikatong.terminal;
/** Network cadence only; authorization and admission remain server controlled. */
public final class TerminalSyncSchedule {
    public static long delay(boolean work,int failures,int jitter){
        long spread=Math.max(0,Math.min(4999,jitter));
        if(failures>0)return Math.min(60000L,5000L*(1L<<Math.min(4,failures-1)))+spread;
        return work?3000L+spread/5:15000L+spread;
    }
    private TerminalSyncSchedule(){}
}
