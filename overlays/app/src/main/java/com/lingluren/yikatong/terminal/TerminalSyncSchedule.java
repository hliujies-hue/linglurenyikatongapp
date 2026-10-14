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
    public static String uninstallNotice(long pending,boolean session){
        if(pending>0)return "本机会话仍有"+pending+"条入园记录未完成平台回执确认。请保持联网并完成同步后退出账号；现在卸载或清除数据会删除本机记录。现有备份只支持原会话恢复，不能保证卸载后恢复。";
        if(session)return "本机会话待确认记录为0条，但账号尚未退出。请返回设备维护，点击“同步后退出账号”，确认退出完成后再卸载。卸载会删除人员特征与本机设置；重装后需重新登录和同步。";
        return "当前未登录账号。卸载会删除本机人员特征、历史缓存及设置；本检查不代表后台所有历史记录均已核对。重装后需重新激活验证、登录和同步。";
    }
    private TerminalSyncSchedule(){}
}
