package com.lingluren.yikatong.terminal;

/** Bounded keyset restore. Checkpoints advance only after a successful native write. */
final class TerminalIndexRestore {
    interface Rows extends AutoCloseable {
        boolean next(); int id(); byte[] feature(); void close();
    }
    interface Source { Rows after(int id,int limit); }
    interface Writer { int push(int id,byte[] feature); }
    interface Clock { long now(); }
    private final long total;
    private final int featureBytes;
    private int lastId;
    private long loaded;
    TerminalIndexRestore(long total,int featureBytes){
        if(total<0||featureBytes<=0)throw new IllegalArgumentException("无效的人员库信息");
        this.total=total;this.featureBytes=featureBytes;
    }
    long loaded(){return loaded;}
    long total(){return total;}
    boolean done(){return loaded==total;}
    void step(Source source,Writer writer,Clock clock,int limit,long budgetMillis){
        if(limit<=0||budgetMillis<=0)throw new IllegalArgumentException("无效的加载批次");
        if(done())return;
        long started=clock.now();
        try(Rows rows=source.after(lastId,limit)){
            for(int count=0;count<limit&&!done();count++){
                if(count>0&&clock.now()-started>=budgetMillis)return;
                if(!rows.next())throw new IllegalStateException("本地人员库数量不一致，请保留数据并联系技术支持");
                int id=rows.id();byte[] feature=rows.feature();
                if(id<=lastId||feature==null||feature.length!=featureBytes)
                    throw new IllegalStateException("本地特征数据异常，请保留数据并联系技术支持（编号 "+id+"）");
                int code=writer.push(id,feature);
                if(code!=0)throw new IllegalStateException("检索库恢复失败（"+code+"，编号 "+id+"），请保留数据并联系技术支持");
                lastId=id;loaded++;
            }
        }
    }
}
