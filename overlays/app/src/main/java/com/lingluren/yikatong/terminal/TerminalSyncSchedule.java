package com.lingluren.yikatong.terminal;
/** Network cadence only; authorization and admission remain server controlled. */
public final class TerminalSyncSchedule {
    public static long delay(boolean work,int failures,int jitter){
        long spread=Math.max(0,Math.min(4999,jitter));
        if(failures>0)return Math.min(60000L,5000L*(1L<<Math.min(4,failures-1)))+spread;
        return work?3000L+spread/5:15000L+spread;
    }
    public static String stage(boolean ready,boolean roster,boolean engine,long people,long receipts,long events){
        if(ready)return "信息与人员同步已完成，可以开始核验";
        if(!roster)return "平台正在生成人员清单，APP将自动检查；持续无进展请联系管理员检查人员同步任务";
        if(!engine)return "正在初始化算法与加载本地特征，请稍候";
        if(people>0)return "正在同步人员，待处理："+people+"；请保持网络连接";
        if(receipts>0)return "正在确认人员同步回执，剩余："+receipts;
        if(events>0)return "正在补传并确认入园记录，剩余："+events;
        return "正在等待平台确认核验条件，暂不可开始核验";
    }
    private TerminalSyncSchedule(){}
}
